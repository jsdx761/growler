"""
Stage 4 — Clone a voice via ElevenLabs Instant Voice Cloning + generate previews.

Uploads the Adobe-denoised reference recording to the ElevenLabs API
to create an Instant Voice Clone (IVC), then generates 5 preview samples
for evaluation and comparison against the Voxtral pipeline.

Outputs:
    elevenlabs_voice/
        voice_info.json                — Voice ID and metadata from the API
    elevenlabs_preview_1c/
        preview_01.wav … 05.wav        — Single candidate preview samples

Resume-safe: reuses an existing voice if voice_info.json is present and
skips previews that already exist.
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

log = logging.getLogger("pipeline.elevenlabs_voice")

API_BASE = "https://api.elevenlabs.io/v1"

# Same preview prompts used by the Voxtral pipeline for direct comparison
PREVIEW_PROMPTS = [
    "Ka Band detected ahead. Reduce your speed.",
    "After 0.2 miles, turn right onto Nanette Drive and then turn right.",
    "Speed camera ahead in 500 metres. Current limit: 30 miles per hour.",
    "Continue straight for 3 miles on the M25 motorway.",
    "Caution. Laser alert detected. Proceed with care.",
]


def get_api_key() -> str:
    key = os.environ.get("ELEVENLABS_API_KEY")
    if not key:
        raise RuntimeError(
            "ELEVENLABS_API_KEY environment variable is not set. "
            "Add it to .env or export it before running the pipeline."
        )
    return key


def pcm_to_wav(pcm_bytes: bytes, sample_rate: int = 24000) -> bytes:
    """Convert raw S16LE PCM bytes to WAV format."""
    data = np.frombuffer(pcm_bytes, dtype=np.int16)
    buf = io.BytesIO()
    wavfile.write(buf, sample_rate, data)
    return buf.getvalue()


def mp3_to_wav(mp3_bytes: bytes, target_sr: int = 24000) -> bytes:
    """Convert MP3 bytes to mono WAV at the target sample rate."""
    with tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as src, \
         tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as dst:
        src.write(mp3_bytes)
        src.flush()
        src_path, dst_path = src.name, dst.name

    try:
        subprocess.run(
            ["ffmpeg", "-y", "-i", src_path, "-ar", str(target_sr),
             "-ac", "1", dst_path],
            capture_output=True, text=True, check=True,
        )
        return Path(dst_path).read_bytes()
    finally:
        os.unlink(src_path)
        os.unlink(dst_path)


def create_voice(cfg: dict, api_key: str) -> dict:
    """Upload reference audio and create an IVC voice via the ElevenLabs API.

    Uses Instant Voice Cloning — available on Starter plan ($5/mo) and above.
    The reference file should be 1–2 minutes of clean, single-speaker audio.
    """
    ecfg = cfg["elevenlabs"]
    ref_path = Path(ecfg["_reference_file"])
    if not ref_path.exists():
        raise FileNotFoundError(f"Reference file not found: {ref_path}")

    log.info(f"Uploading reference audio: {ref_path.name} "
             f"({ref_path.stat().st_size / 1024:.0f} KB)")

    suffix = ref_path.suffix.lower().lstrip(".")
    mime = {"wav": "audio/wav", "mp3": "audio/mpeg"}.get(suffix, "audio/wav")

    labels = {}
    if ecfg.get("gender"):
        labels["gender"] = ecfg["gender"]
    if ecfg.get("accent"):
        labels["accent"] = ecfg["accent"]
    if ecfg.get("languages"):
        labels["language"] = ecfg["languages"][0]

    with open(ref_path, "rb") as f:
        resp = requests.post(
            f"{API_BASE}/voices/add",
            headers={"xi-api-key": api_key},
            data={
                "name": ecfg.get("voice_name", "uk-voice-clone-elevenlabs"),
                "description": ecfg.get(
                    "voice_description",
                    "UK female navigation and alert voice"),
                "labels": json.dumps(labels),
                "remove_background_noise": str(ecfg.get(
                    "remove_background_noise", False)).lower(),
            },
            files=[("files", (ref_path.name, f, mime))],
            timeout=120,
        )
    resp.raise_for_status()
    voice_data = resp.json()

    log.info(f"Voice created — id: {voice_data['voice_id']}")
    return voice_data


def generate_speech(text: str, voice_id: str, model: str,
                    voice_settings: dict, api_key: str,
                    output_format: str = "pcm_24000",
                    max_retries: int = 3) -> bytes:
    """Generate speech audio from text using an ElevenLabs voice.

    Returns WAV bytes (converted from PCM or MP3 depending on output_format).
    """
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


def run(cfg: dict):
    ecfg = cfg["elevenlabs"]
    out_dir = Path(ecfg["_voice_output_path"])
    out_dir.mkdir(parents=True, exist_ok=True)

    api_key = get_api_key()
    model = ecfg.get("model", "eleven_multilingual_v2")
    output_format = ecfg.get("output_format", "pcm_24000")

    voice_settings = {
        "stability": ecfg.get("stability", 1.00),
        "similarity_boost": ecfg.get("similarity_boost", 1.00),
        "style": ecfg.get("style", 0.0),
        "use_speaker_boost": ecfg.get("use_speaker_boost", True),
        "speed": ecfg.get("speed", 0.75),
    }

    # ── create or reuse voice ───────────────────────────────────────
    voice_info_path = out_dir / "voice_info.json"
    if voice_info_path.exists():
        voice_data = json.loads(voice_info_path.read_text())
        voice_id = voice_data["voice_id"]
        log.info(f"Reusing existing voice: {voice_id}")
    else:
        voice_data = create_voice(cfg, api_key)
        voice_id = voice_data["voice_id"]
        voice_info_path.write_text(json.dumps(voice_data, indent=2) + "\n")
        log.info(f"Voice info saved to {voice_info_path}")

    # ── generate preview samples ────────────────────────────────────
    delay = ecfg.get("request_delay_s", 0.5)
    sta = int(voice_settings["stability"] * 100)
    sim = int(voice_settings["similarity_boost"] * 100)
    spd = int(voice_settings["speed"] * 100)
    preview_dir = Path(cfg["_output_dir"]) / f"elevenlabs_preview_1c_sta_{sta:03d}_sim_{sim:03d}_spd_{spd:03d}"
    preview_dir.mkdir(parents=True, exist_ok=True)

    log.info(f"Generating {len(PREVIEW_PROMPTS)} previews (1c) "
             f"-> {preview_dir.name}/")
    log.info(f"  Model: {model}")
    log.info(f"  Output format: {output_format}")
    log.info(f"  Voice settings: stability={voice_settings['stability']}, "
             f"similarity_boost={voice_settings['similarity_boost']}, "
             f"speed={voice_settings['speed']}, "
             f"use_speaker_boost={voice_settings['use_speaker_boost']}")

    for i, text in enumerate(PREVIEW_PROMPTS, 1):
        wav_path = preview_dir / f"preview_{i:02d}.wav"
        if wav_path.exists():
            log.info(f"  preview_{i:02d}.wav — skipped (exists)")
            continue

        log.info(f"  preview_{i:02d}.wav — \"{text[:60]}\"")
        audio = generate_speech(text, voice_id, model, voice_settings,
                                api_key, output_format)
        wav_path.write_bytes(audio)

        if i < len(PREVIEW_PROMPTS) and delay > 0:
            time.sleep(delay)

    log.info(f"Done — voice info in {out_dir}/, "
             f"previews in {preview_dir}/")
    log.info("Listen to the previews and compare against Voxtral "
             "before running stage 5.")
