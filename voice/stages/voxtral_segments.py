"""
Stage — Generate pre-recorded voice segments using Voxtral TTS.

Reads the voice ID created by voxtral_voice and the segment manifest
(data/voice_segments.txt), then synthesises each segment via the Mistral
Voxtral API.  Outputs are WAV files named by segment ID for direct use
as Android app assets.

Quality control: for each segment, generates CANDIDATES_PER_SEGMENT
takes and selects the one with the best SNR (signal-to-noise ratio),
ensuring consistently high audio quality across all segments.

Resume-safe: already-generated files are skipped automatically.
"""

import base64
import io
import json
import logging
import os
import subprocess
import tempfile
import time
from pathlib import Path

import numpy as np
import scipy.io.wavfile as wavfile
import requests
from tqdm import tqdm

log = logging.getLogger("pipeline.voxtral_segments")

API_BASE = "https://api.mistral.ai"

# Default number of candidate takes per segment (overridden by config)
DEFAULT_CANDIDATES_PER_SEGMENT = 1

# Prepend this much silence to every segment to prevent clipping when
# AudioTrack starts playback on Android
SILENCE_PAD_MS = 50

# SNR analysis: frame length for noise floor estimation
SNR_FRAME_MS = 20
# Fraction of quietest frames used to estimate noise floor
SNR_QUIET_FRACTION = 0.10

# FFmpeg filter chain for post-generation denoising:
#   highpass  — remove sub-speech rumble
#   afftdn x2 — two-pass FFT denoising with noise-floor tracking
#   anlmdn    — non-local-means broadband denoising
#   agate     — noise gate to silence gaps between speech
DENOISE_FILTER = (
    "highpass=f=80,"
    "afftdn=nr=20:nf=-35:tn=1,"
    "afftdn=nr=15:nf=-40:tn=1,"
    "anlmdn=s=5:p=0.02:r=0.06:m=11,"
    "agate=threshold=0.01:ratio=2:attack=5:release=50"
)


def get_api_key() -> str:
    key = os.environ.get("MISTRAL_API_KEY")
    if not key:
        raise RuntimeError(
            "MISTRAL_API_KEY environment variable is not set. "
            "Export it before running the pipeline."
        )
    return key


def parse_segments(segments_file: str) -> list[tuple[str, str]]:
    """Return [(segment_id, text), …] for every non-comment, non-blank line."""
    segments: list[tuple[str, str]] = []
    seen: set[str] = set()
    with open(segments_file) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "|" not in line:
                log.warning(f"Skipping malformed line: {line}")
                continue
            seg_id, text = line.split("|", 1)
            seg_id = seg_id.strip()
            text = text.strip()
            if seg_id in seen:
                continue
            seen.add(seg_id)
            segments.append((seg_id, text))
    return segments


def generate_speech(text: str, voice_id: str, model: str,
                    api_key: str, max_retries: int = 3) -> bytes:
    """Generate speech audio with retry on transient failures."""
    for attempt in range(1, max_retries + 1):
        try:
            resp = requests.post(
                f"{API_BASE}/v1/audio/speech",
                headers={
                    "Authorization": f"Bearer {api_key}",
                    "Content-Type": "application/json",
                },
                json={
                    "input": text,
                    "model": model,
                    "voice_id": voice_id,
                    "response_format": "wav",
                },
                timeout=60,
            )
            if resp.status_code == 429:
                wait = min(2 ** attempt, 30)
                log.warning(f"Rate limited — waiting {wait}s (attempt {attempt})")
                time.sleep(wait)
                continue
            resp.raise_for_status()
            return base64.b64decode(resp.json()["audio_data"])
        except requests.exceptions.RequestException as e:
            if attempt == max_retries:
                raise
            wait = min(2 ** attempt, 30)
            log.warning(f"Request error: {e} — retrying in {wait}s")
            time.sleep(wait)


def wav_snr(wav_bytes: bytes) -> float:
    """Estimate SNR in dB from raw WAV bytes.

    Splits the audio into short frames, uses the quietest 10% of frames
    to estimate the noise floor, and computes SNR as the ratio of overall
    RMS to noise-floor RMS.
    """
    sr, data = wavfile.read(io.BytesIO(wav_bytes))
    data_f = data.astype(np.float64)
    rms = np.sqrt(np.mean(data_f ** 2))
    if rms < 1.0:
        return 0.0

    frame_len = int(sr * SNR_FRAME_MS / 1000)
    n_frames = len(data_f) // frame_len
    if n_frames < 2:
        return 0.0

    frame_rms = np.array([
        np.sqrt(np.mean(data_f[i * frame_len:(i + 1) * frame_len] ** 2))
        for i in range(n_frames)
    ])
    frame_rms.sort()
    n_quiet = max(1, int(n_frames * SNR_QUIET_FRACTION))
    noise_floor = np.mean(frame_rms[:n_quiet])

    if noise_floor < 1.0:
        return 99.0
    return float(20 * np.log10(rms / noise_floor))


def pad_silence(wav_bytes: bytes) -> bytes:
    """Prepend silence to WAV audio to prevent playback clipping."""
    sr, data = wavfile.read(io.BytesIO(wav_bytes))
    silence_samples = int(sr * SILENCE_PAD_MS / 1000)
    silence = np.zeros(silence_samples, dtype=data.dtype)
    padded = np.concatenate([silence, data])
    buf = io.BytesIO()
    wavfile.write(buf, sr, padded)
    return buf.getvalue()


def denoise_wav(wav_bytes: bytes) -> bytes:
    """Remove background noise from WAV audio using ffmpeg filters.

    Applies a multi-stage filter chain: high-pass to remove rumble,
    two-pass FFT denoising, non-local-means broadband denoising, and
    a noise gate for silence between speech.
    """
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as src, \
         tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as dst:
        src.write(wav_bytes)
        src.flush()
        src_path, dst_path = src.name, dst.name

    try:
        result = subprocess.run(
            ["ffmpeg", "-y", "-i", src_path, "-af", DENOISE_FILTER, dst_path],
            capture_output=True, text=True,
        )
        if result.returncode != 0:
            log.warning(f"ffmpeg denoise failed: {result.stderr.splitlines()[-1]}")
            return wav_bytes  # fall back to original
        return Path(dst_path).read_bytes()
    finally:
        os.unlink(src_path)
        os.unlink(dst_path)


def generate_best_candidate(text: str, seg_id: str, voice_id: str,
                            model: str, api_key: str,
                            n_candidates: int,
                            delay: float) -> tuple[bytes, float]:
    """Generate n_candidates takes and return the one with the best SNR."""
    best_audio = None
    best_snr = -1.0

    for i in range(n_candidates):
        if i > 0 and delay > 0:
            time.sleep(delay)
        try:
            audio = generate_speech(text, voice_id, model, api_key)
            if n_candidates > 1:
                snr = wav_snr(audio)
                if snr > best_snr:
                    best_snr = snr
                    best_audio = audio
            else:
                best_audio = audio
        except Exception as e:
            log.warning(f"  Candidate {i + 1} failed: {e}")

    return best_audio, best_snr


def run(cfg: dict):
    voice_dir = Path(cfg["voxtral"]["_voice_output_path"])
    out_dir = Path(cfg["voxtral_segments"]["_output_path"])
    out_dir.mkdir(parents=True, exist_ok=True)

    # ── load voice info ─────────────────────────────────────────────
    voice_info_path = voice_dir / "voice_info.json"
    if not voice_info_path.exists():
        raise FileNotFoundError(
            f"Voice info not found at {voice_info_path}\n"
            "Run the voxtral_voice stage first."
        )
    voice_data = json.loads(voice_info_path.read_text())
    voice_id = voice_data["id"]
    log.info(f"Using voice: {voice_id} ({voice_data['name']})")

    api_key = get_api_key()
    model = cfg["voxtral"].get("model", "voxtral-mini-tts-2603")
    delay = cfg["voxtral"].get("request_delay_s", 0.1)
    candidates = cfg["voxtral_segments"].get(
        "candidates_per_segment", DEFAULT_CANDIDATES_PER_SEGMENT)
    do_denoise = cfg["voxtral_segments"].get("denoise", False)

    segments_file = cfg["voxtral_segments"]["_segments_file"]
    segments = parse_segments(segments_file)
    log.info(f"Loaded {len(segments)} segments from {segments_file}")
    log.info(f"Candidates per segment: {candidates}, denoise: {do_denoise}")

    # ── generate ────────────────────────────────────────────────────
    generated = 0
    skipped = 0
    errors = 0
    snr_values: list[float] = []

    for seg_id, text in tqdm(segments, desc="Generating segments"):
        wav_path = out_dir / f"{seg_id}.wav"

        if wav_path.exists():
            skipped += 1
            continue

        try:
            audio, snr = generate_best_candidate(
                text, seg_id, voice_id, model, api_key,
                candidates, delay)

            if audio is None:
                errors += 1
                log.warning(f"Segment {seg_id}: all candidates failed")
                continue

            processed = pad_silence(audio)
            if do_denoise:
                processed = denoise_wav(processed)
            wav_path.write_bytes(processed)
            if candidates > 1:
                snr_values.append(snr)
            generated += 1

            if generated % 50 == 0 and snr_values:
                log.info(f"  Progress: {generated} generated, "
                         f"median SNR {np.median(snr_values):.1f} dB")

        except Exception as e:
            errors += 1
            log.warning(f"Segment {seg_id} failed: {e}")

        if delay > 0:
            time.sleep(delay)

    if snr_values:
        log.info(f"SNR stats — min: {min(snr_values):.1f} dB, "
                 f"median: {np.median(snr_values):.1f} dB, "
                 f"max: {max(snr_values):.1f} dB")

    log.info(
        f"Done — {generated} generated, {skipped} skipped (existing), "
        f"{errors} errors"
    )
    log.info(f"Output directory: {out_dir}")
