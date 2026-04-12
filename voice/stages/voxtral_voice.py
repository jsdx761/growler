"""
Stage 1 — Create a Voxtral voice clone and generate preview samples.

Uploads the denoised combined reference recording to the Mistral Voxtral API
to create a custom voice, then generates 5 preview samples for evaluation
using two approaches for comparison:

Directory names are derived automatically: voxtral_preview_{n}c[_snr].

Outputs:
    voxtral_voice/
        voice_info.json                — Voice ID and metadata from the API
    voxtral_preview_1c/
        preview_01.wav … 05.wav        — Preview samples for sound-checking

Resume-safe: reuses an existing voice if voice_info.json is present and
skips previews that already exist.
"""

import base64
import json
import logging
import os
import statistics
import subprocess
import tempfile
import time
from pathlib import Path

import requests

log = logging.getLogger("pipeline.voxtral_voice")

API_BASE = "https://api.mistral.ai"

PREVIEW_PROMPTS = [
    "Ka Band detected ahead. Reduce your speed.",
    "After 0.2 miles, turn right onto Nanette Drive and then turn right.",
    "Speed camera ahead in 500 metres. Current limit: 30 miles per hour.",
    "Continue straight for 3 miles on the M25 motorway.",
    "Caution. Laser alert detected. Proceed with care.",
]


# Same filter chain used for segment denoising in voxtral_segments.py
DENOISE_FILTER = (
    "highpass=f=80,"
    "afftdn=nr=20:nf=-35:tn=1,"
    "afftdn=nr=15:nf=-40:tn=1,"
    "anlmdn=s=5:p=0.02:r=0.06:m=11,"
    "agate=threshold=0.01:ratio=2:attack=5:release=50"
)


def measure_rms(wav_path: Path) -> float:
    """Return the overall RMS level (dB) of a WAV file."""
    result = subprocess.run(
        ["ffmpeg", "-i", str(wav_path), "-af",
         "astats=metadata=1:reset=0,ametadata=print:key=lavfi.astats.Overall.RMS_level",
         "-f", "null", "-"],
        capture_output=True, text=True,
    )
    for line in reversed(result.stderr.splitlines()):
        if "RMS_level" in line:
            return float(line.split("=")[-1])
    raise RuntimeError(f"Could not measure RMS for {wav_path}")


def filter_segments(segments: list[Path],
                    threshold_db: float) -> list[Path]:
    """Exclude segments whose RMS is more than threshold_db below the median."""
    rms_values = {}
    for seg in segments:
        rms = measure_rms(seg)
        rms_values[seg] = rms
        log.info(f"  {seg.name}: RMS = {rms:.1f} dB")

    median_rms = statistics.median(rms_values.values())
    cutoff = median_rms - threshold_db
    log.info(f"  Median RMS: {median_rms:.1f} dB, "
             f"cutoff: {cutoff:.1f} dB (threshold: {threshold_db} dB)")

    kept = []
    for seg in segments:
        rms = rms_values[seg]
        if rms >= cutoff:
            kept.append(seg)
        else:
            log.info(f"  Excluding {seg.name} "
                     f"(RMS {rms:.1f} dB < cutoff {cutoff:.1f} dB)")

    if not kept:
        raise RuntimeError("All segments were excluded by quality filter")
    return kept


def combine_segments(recordings_dir: Path, output_path: Path,
                     gap_ms: int = 750,
                     rms_threshold_db: float | None = None) -> Path:
    """Concatenate individual segment WAVs with silence gaps between them.

    If rms_threshold_db is set, segments whose RMS is more than that many dB
    below the median are excluded first.

    Returns the path to the combined file.
    """
    segments = sorted(recordings_dir.glob("segment_*.wav"))
    if not segments:
        raise FileNotFoundError(
            f"No segment_*.wav files found in {recordings_dir}")

    if rms_threshold_db is not None:
        log.info("Measuring segment quality …")
        segments = filter_segments(segments, rms_threshold_db)
        log.info(f"  Keeping {len(segments)} segments")

    # Probe sample rate and channel layout from the first segment
    probe = subprocess.run(
        ["ffprobe", "-v", "quiet", "-show_entries",
         "stream=sample_rate,channels", "-of", "csv=p=0", str(segments[0])],
        capture_output=True, text=True,
    )
    sr, ch = probe.stdout.strip().split(",")
    ch_layout = "mono" if ch == "1" else f"{ch}c"

    # Build ffmpeg filter: interleave segments with generated silence
    inputs = []
    for s in segments:
        inputs += ["-i", str(s)]

    gap_s = gap_ms / 1000.0
    n_gaps = len(segments) - 1
    filter_parts = []
    for i in range(n_gaps):
        label = f"g{i}"
        filter_parts.append(
            f"aevalsrc=0:d={gap_s}:s={sr}:c={ch_layout}[{label}]")

    # Build concat chain: seg0 gap0 seg1 gap1 ... segN
    chain = ""
    for i in range(len(segments)):
        chain += f"[{i}:a]"
        if i < n_gaps:
            chain += f"[g{i}]"
    n_parts = len(segments) + n_gaps
    filter_parts.append(f"{chain}concat=n={n_parts}:v=0:a=1[out]")

    result = subprocess.run(
        ["ffmpeg", "-y"] + inputs +
        ["-filter_complex", ";".join(filter_parts), "-map", "[out]",
         str(output_path)],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"ffmpeg combine failed: {result.stderr.splitlines()[-1] if result.stderr.strip() else '(no stderr)'}")

    log.info(f"Combined {len(segments)} segments with {gap_ms}ms gaps -> "
             f"{output_path.name} ({output_path.stat().st_size / 1024:.0f} KB)")
    return output_path


def denoise_wav(wav_path: Path) -> bytes:
    """Denoise a WAV file and return the cleaned bytes."""
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as dst:
        dst_path = dst.name

    try:
        result = subprocess.run(
            ["ffmpeg", "-y", "-i", str(wav_path), "-af", DENOISE_FILTER, dst_path],
            capture_output=True, text=True,
        )
        if result.returncode != 0:
            log.warning(f"ffmpeg denoise failed, using original: "
                        f"{result.stderr.splitlines()[-1] if result.stderr.strip() else '(no stderr)'}")
            return wav_path.read_bytes()
        return Path(dst_path).read_bytes()
    finally:
        os.unlink(dst_path)


def get_api_key() -> str:
    key = os.environ.get("MISTRAL_API_KEY")
    if not key:
        raise RuntimeError(
            "MISTRAL_API_KEY environment variable is not set. "
            "Export it before running the pipeline."
        )
    return key


def normalize_sample_rate(wav_path: Path, target_sr: int = 22050) -> bytes:
    """Resample a WAV to the target rate if needed, returning the bytes."""
    probe = subprocess.run(
        ["ffprobe", "-v", "quiet", "-show_entries",
         "stream=sample_rate", "-of", "csv=p=0", str(wav_path)],
        capture_output=True, text=True,
    )
    current_sr = int(probe.stdout.strip())
    if current_sr == target_sr:
        return wav_path.read_bytes()

    log.info(f"Resampling {wav_path.name} from {current_sr}Hz to {target_sr}Hz")
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as dst:
        dst_path = dst.name
    try:
        result = subprocess.run(
            ["ffmpeg", "-y", "-i", str(wav_path), "-ar", str(target_sr),
             dst_path],
            capture_output=True, text=True,
        )
        if result.returncode != 0:
            raise RuntimeError(
                f"Resample failed: {result.stderr.splitlines()[-1] if result.stderr.strip() else '(no stderr)'}")
        return Path(dst_path).read_bytes()
    finally:
        os.unlink(dst_path)


def create_voice(cfg: dict, api_key: str) -> dict:
    """Upload reference audio and create a custom voice via the Voxtral API."""
    ref_path = Path(cfg["voxtral"]["_reference_file"])
    if not ref_path.exists():
        raise FileNotFoundError(f"Reference file not found: {ref_path}")

    log.info(f"Uploading reference audio: {ref_path.name} "
             f"({ref_path.stat().st_size / 1024:.0f} KB)")
    audio_bytes = normalize_sample_rate(ref_path)
    audio_b64 = base64.b64encode(audio_bytes).decode("ascii")

    vcfg = cfg["voxtral"]
    resp = requests.post(
        f"{API_BASE}/v1/audio/voices",
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
        json={
            "name": vcfg.get("voice_name", "uk-voice-clone"),
            "sample_audio": audio_b64,
            "sample_filename": ref_path.name,
            "gender": vcfg.get("gender"),
            "languages": vcfg.get("languages", ["en"]),
            "tags": vcfg.get("tags", ["cloned", "navigation"]),
        },
        timeout=120,
    )
    resp.raise_for_status()
    voice_data = resp.json()

    log.info(f"Voice created — id: {voice_data['id']}, name: {voice_data['name']}")
    return voice_data


def generate_speech(text: str, voice_id: str, model: str,
                    api_key: str, retries: int = 3,
                    retry_delay: float = 5.0) -> bytes:
    """Generate speech audio from text using a Voxtral voice."""
    for attempt in range(1, retries + 1):
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
        if resp.status_code == 400 and attempt < retries:
            log.warning(f"  Speech API returned 400, retrying in "
                        f"{retry_delay}s (attempt {attempt}/{retries})")
            time.sleep(retry_delay)
            continue
        resp.raise_for_status()
        return base64.b64decode(resp.json()["audio_data"])


def run(cfg: dict):
    out_dir = Path(cfg["voxtral"]["_voice_output_path"])
    out_dir.mkdir(parents=True, exist_ok=True)

    api_key = get_api_key()
    vcfg = cfg["voxtral"]
    model = vcfg.get("model", "voxtral-mini-tts-2603")

    # ── combine reference segments ─────────────────────────────────
    recordings_dir = Path(vcfg.get(
        "_reference_recordings_dir",
        str(Path(vcfg["_reference_file"]).parent)))
    gap_ms = vcfg.get("reference_gap_ms", 750)
    rms_threshold = vcfg.get("reference_rms_threshold_db")
    ref_path = Path(vcfg["_reference_file"])
    raw_path = ref_path.with_name("combined_raw.wav")

    auto_denoised_path = raw_path.with_name("combined_auto_denoised.wav")

    log.info("Combining reference segments …")
    combine_segments(recordings_dir, raw_path, gap_ms=gap_ms,
                     rms_threshold_db=rms_threshold)

    log.info("Denoising combined reference -> combined_auto_denoised.wav")
    clean_audio = denoise_wav(raw_path)
    auto_denoised_path.write_bytes(clean_audio)
    log.info(f"Saved {auto_denoised_path.name} "
             f"({len(clean_audio) / 1024:.0f} KB)")

    # ── create or reuse voice ───────────────────────────────────────
    voice_info_path = out_dir / "voice_info.json"
    if voice_info_path.exists():
        voice_data = json.loads(voice_info_path.read_text())
        voice_id = voice_data["id"]
        log.info(f"Reusing existing voice: {voice_id} ({voice_data['name']})")
    else:
        voice_data = create_voice(cfg, api_key)
        voice_id = voice_data["id"]
        voice_info_path.write_text(json.dumps(voice_data, indent=2) + "\n")
        log.info(f"Voice info saved to {voice_info_path}")

    # ── generate preview samples ────────────────────────────────────
    from stages.voxtral_segments import wav_snr

    delay = vcfg.get("request_delay_s", 0.1)
    output_dir = Path(cfg["_output_dir"])

    preview_configs = [
        (1, False),  # single candidate (default — best quality/speed tradeoff)
    ]

    for n_candidates, use_snr in preview_configs:
        subdir = f"voxtral_preview_{n_candidates}c" + ("_snr" if use_snr else "")
        preview_dir = output_dir / subdir
        preview_dir.mkdir(parents=True, exist_ok=True)

        label = f"{n_candidates}c" + (" + SNR" if use_snr else "")
        log.info(f"Generating {len(PREVIEW_PROMPTS)} previews ({label}) "
                 f"-> {subdir}/")

        for i, text in enumerate(PREVIEW_PROMPTS, 1):
            wav_path = preview_dir / f"preview_{i:02d}.wav"
            if wav_path.exists():
                log.info(f"  preview_{i:02d}.wav — skipped (exists)")
                continue

            log.info(f"  preview_{i:02d}.wav — \"{text[:60]}\"")
            best_audio = None
            best_snr = -1.0

            for c in range(n_candidates):
                if c > 0 and delay > 0:
                    time.sleep(delay)
                audio = generate_speech(text, voice_id, model, api_key)
                if use_snr:
                    snr = wav_snr(audio)
                    if snr > best_snr:
                        best_snr = snr
                        best_audio = audio
                else:
                    best_audio = audio

            if best_audio:
                wav_path.write_bytes(best_audio)
                if use_snr:
                    log.info(f"    SNR: {best_snr:.1f} dB "
                             f"(best of {n_candidates})")

            if delay > 0:
                time.sleep(delay)

    log.info(f"Done — voice info in {out_dir}/")
    log.info("Listen to the previews before running stage 2.")
