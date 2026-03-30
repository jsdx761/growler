#!/usr/bin/env python3
"""Regenerate a single voice segment without touching the rest."""

import base64
import io
import os
import subprocess
import tempfile
import time
from pathlib import Path

import numpy as np
import requests
import scipy.io.wavfile as wavfile

# ── Config ────────────────────────────────────────────────────────────────
SEGMENT_ID = "radar_ka_34_7"
TEXT = "K    A  band thirty four point seven Radar"
VOICE_ID = "ec49ab12-051e-4853-926a-67d1ef129457"
MODEL = "voxtral-mini-tts-2603"
API_KEY = os.environ.get("MISTRAL_API_KEY", "Lm5um6wAYdSHuuxXYTjQXfXSfy7JqOKG")
OUTPUT = Path(__file__).parent / "output" / "voxtral_segments" / f"{SEGMENT_ID}.wav"

CANDIDATES = 3
SILENCE_PAD_MS = 50
SNR_FRAME_MS = 20
SNR_QUIET_FRACTION = 0.10
DENOISE_FILTER = (
    "highpass=f=80,"
    "afftdn=nr=20:nf=-35:tn=1,"
    "afftdn=nr=15:nf=-40:tn=1,"
    "anlmdn=s=5:p=0.02:r=0.06:m=11,"
    "agate=threshold=0.01:ratio=2:attack=5:release=50"
)

API_BASE = "https://api.mistral.ai"


def generate_speech(text, max_retries=3):
    for attempt in range(1, max_retries + 1):
        resp = requests.post(
            f"{API_BASE}/v1/audio/speech",
            headers={
                "Authorization": f"Bearer {API_KEY}",
                "Content-Type": "application/json",
            },
            json={
                "input": text,
                "model": MODEL,
                "voice_id": VOICE_ID,
                "response_format": "wav",
            },
            timeout=60,
        )
        if resp.status_code == 429:
            wait = min(2 ** attempt, 30)
            print(f"  Rate limited — waiting {wait}s")
            time.sleep(wait)
            continue
        resp.raise_for_status()
        return base64.b64decode(resp.json()["audio_data"])
    raise RuntimeError("All retries exhausted")


def wav_snr(wav_bytes):
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


def pad_silence(wav_bytes):
    sr, data = wavfile.read(io.BytesIO(wav_bytes))
    silence = np.zeros(int(sr * SILENCE_PAD_MS / 1000), dtype=data.dtype)
    buf = io.BytesIO()
    wavfile.write(buf, sr, np.concatenate([silence, data]))
    return buf.getvalue()


def denoise_wav(wav_bytes):
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
            print(f"  ffmpeg denoise failed, using raw audio")
            return wav_bytes
        return Path(dst_path).read_bytes()
    finally:
        os.unlink(src_path)
        os.unlink(dst_path)


def main():
    print(f"Regenerating {SEGMENT_ID}: \"{TEXT}\"")
    print(f"Generating {CANDIDATES} candidates, selecting best SNR...")

    best_audio = None
    best_snr = -1.0

    for i in range(CANDIDATES):
        if i > 0:
            time.sleep(0.1)
        try:
            audio = generate_speech(TEXT)
            snr = wav_snr(audio)
            print(f"  Candidate {i + 1}: SNR {snr:.1f} dB")
            if snr > best_snr:
                best_snr = snr
                best_audio = audio
        except Exception as e:
            print(f"  Candidate {i + 1} failed: {e}")

    if best_audio is None:
        print("All candidates failed!")
        return

    final = denoise_wav(pad_silence(best_audio))
    OUTPUT.write_bytes(final)
    print(f"Wrote {OUTPUT} (SNR {best_snr:.1f} dB, {len(final)} bytes)")


if __name__ == "__main__":
    main()
