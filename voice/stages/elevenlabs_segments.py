"""
Stage 5 — Generate pre-recorded voice segments using ElevenLabs TTS.

Reads the voice ID created by elevenlabs_voice and the segment manifest
(data/voice_segments.txt), then synthesises each segment via the ElevenLabs
Text-to-Speech API.  Outputs are WAV files named by segment ID for direct
use as Android app assets.

Quality control: when candidates_per_segment > 1, generates multiple takes
and selects the one with the best SNR.  Default is 1 candidate to conserve
credits on the Starter plan (~20K credits for all 863 segments).

Resume-safe: already-generated files are skipped automatically.
"""

import io
import json
import logging
import os
import subprocess
import tempfile
import time
from pathlib import Path

import numpy as np
import requests
import scipy.io.wavfile as wavfile
from tqdm import tqdm

from .audio_utils import get_elevenlabs_api_key as get_api_key, pcm_to_wav, mp3_to_wav

log = logging.getLogger("pipeline.elevenlabs_segments")

API_BASE = "https://api.elevenlabs.io/v1"

# Prepend silence to prevent clipping on Android AudioTrack startup
SILENCE_PAD_MS = 50

# SNR analysis parameters (used when candidates_per_segment > 1)
SNR_FRAME_MS = 20
SNR_QUIET_FRACTION = 0.10


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
                    voice_settings: dict, api_key: str,
                    output_format: str = "pcm_24000",
                    max_retries: int = 3) -> bytes:
    """Generate speech audio and return WAV bytes."""
    for attempt in range(1, max_retries + 1):
        try:
            resp = requests.post(
                f"{API_BASE}/text-to-speech/{voice_id}",
                headers={
                    "xi-api-key": api_key,
                    "Content-Type": "application/json",
                },
                params={"output_format": output_format},
                json={
                    "text": text,
                    "model_id": model,
                    "voice_settings": voice_settings,
                },
                timeout=60,
            )
            if resp.status_code == 429:
                wait = min(2 ** attempt, 30)
                log.warning(f"Rate limited — waiting {wait}s "
                            f"(attempt {attempt}/{max_retries})")
                time.sleep(wait)
                continue
            resp.raise_for_status()

            if output_format.startswith("pcm_"):
                sr = int(output_format.split("_")[1])
                return pcm_to_wav(resp.content, sample_rate=sr)
            else:
                return mp3_to_wav(resp.content)

        except requests.exceptions.RequestException as e:
            if attempt == max_retries:
                raise
            wait = min(2 ** attempt, 30)
            log.warning(f"Request error: {e} — retrying in {wait}s")
            time.sleep(wait)
    raise RuntimeError(f"All {max_retries} retries exhausted due to rate limiting")


def wav_snr(wav_bytes: bytes) -> float:
    """Estimate SNR in dB from WAV bytes.

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


def generate_best_candidate(text: str, seg_id: str, voice_id: str,
                            model: str, voice_settings: dict,
                            api_key: str, output_format: str,
                            n_candidates: int,
                            delay: float) -> tuple[bytes, float]:
    """Generate n_candidates takes and return the one with the best SNR."""
    best_audio = None
    best_snr = -1.0

    for i in range(n_candidates):
        if i > 0 and delay > 0:
            time.sleep(delay)
        try:
            audio = generate_speech(text, voice_id, model, voice_settings,
                                    api_key, output_format)
            if n_candidates > 1:
                snr = wav_snr(audio)
                if snr > best_snr:
                    best_snr = snr
                    best_audio = audio
            else:
                best_audio = audio
        except Exception as e:
            log.warning(f"  Candidate {i + 1} failed for {seg_id}: {e}")

    return best_audio, best_snr


def run(cfg: dict):
    ecfg = cfg["elevenlabs"]
    voice_dir = Path(ecfg["_voice_output_path"])
    out_dir = Path(cfg["elevenlabs_segments"]["_output_path"])
    out_dir.mkdir(parents=True, exist_ok=True)

    # ── load voice info ─────────────────────────────────────────────
    voice_info_path = voice_dir / "voice_info.json"
    if not voice_info_path.exists():
        raise FileNotFoundError(
            f"Voice info not found at {voice_info_path}\n"
            "Run the elevenlabs_voice stage (stage 4) first."
        )
    voice_data = json.loads(voice_info_path.read_text())
    voice_id = voice_data["voice_id"]
    log.info(f"Using voice: {voice_id}")

    api_key = get_api_key()
    model = ecfg.get("model", "eleven_multilingual_v2")
    output_format = ecfg.get("output_format", "pcm_24000")
    delay = ecfg.get("request_delay_s", 0.5)
    candidates = cfg["elevenlabs_segments"].get("candidates_per_segment", 1)

    voice_settings = {
        "stability": ecfg.get("stability", 1.00),
        "similarity_boost": ecfg.get("similarity_boost", 1.00),
        "style": ecfg.get("style", 0.0),
        "use_speaker_boost": ecfg.get("use_speaker_boost", True),
        "speed": ecfg.get("speed", 0.75),
    }

    segments_file = cfg["elevenlabs_segments"]["_segments_file"]
    segments = parse_segments(segments_file)
    log.info(f"Loaded {len(segments)} segments from {segments_file}")
    log.info(f"Model: {model}, candidates/segment: {candidates}")
    log.info(f"Voice settings: stability={voice_settings['stability']}, "
             f"similarity_boost={voice_settings['similarity_boost']}, "
             f"use_speaker_boost={voice_settings['use_speaker_boost']}")

    # ── estimate credit usage ──────────────────────────────────────
    already_done = sum(1 for sid, _ in segments
                       if (out_dir / f"{sid}.wav").exists())
    remaining = [(sid, txt) for sid, txt in segments
                 if not (out_dir / f"{sid}.wav").exists()]
    total_chars = sum(len(text) for _, text in remaining)
    credit_mult = 0.5 if "flash" in model else 1.0
    est_credits = int(total_chars * candidates * credit_mult)
    log.info(f"Segments: {len(segments)} total, {already_done} already done, "
             f"{len(remaining)} remaining")
    log.info(f"Estimated credit usage for remaining: ~{est_credits:,} "
             f"({total_chars:,} chars × {candidates} candidates"
             f"{' × 0.5 (flash)' if credit_mult < 1 else ''})")

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
                text, seg_id, voice_id, model, voice_settings,
                api_key, output_format, candidates, delay)

            if audio is None:
                errors += 1
                log.warning(f"Segment {seg_id}: all candidates failed")
                continue

            wav_path.write_bytes(pad_silence(audio))
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
