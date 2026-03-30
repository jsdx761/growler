#!/usr/bin/env python3
"""Regenerate a batch of voice segments.

Usage:
    python regen_batch.py                    # regenerate default set
    python regen_batch.py bearing_1 bearing_2  # specific segments
    python regen_batch.py --all-bearings --all-accidents  # by category
"""

import argparse
import base64
import io
import os
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

import numpy as np
import requests
import scipy.io.wavfile as wavfile

VOICE_ID = "ec49ab12-051e-4853-926a-67d1ef129457"
MODEL = "voxtral-mini-tts-2603"
API_KEY = os.environ.get("MISTRAL_API_KEY", "Lm5um6wAYdSHuuxXYTjQXfXSfy7JqOKG")
API_BASE = "https://api.mistral.ai"
OUTPUT_DIR = Path(__file__).parent / "output" / "voxtral_segments"
ASSETS_DIR = Path(__file__).parent.parent / "app" / "src" / "main" / "assets" / "voice_segments"
SEGMENTS_FILE = Path(__file__).parent / "data" / "voice_segments.txt"

CANDIDATES = 5
SILENCE_PAD_MS = 50
DENOISE_FILTER = (
    "highpass=f=80,"
    "afftdn=nr=20:nf=-35:tn=1,"
    "afftdn=nr=15:nf=-40:tn=1,"
    "anlmdn=s=5:p=0.02:r=0.06:m=11,"
    "agate=threshold=0.01:ratio=2:attack=5:release=50"
)


def gen(text):
    resp = requests.post(
        f"{API_BASE}/v1/audio/speech",
        headers={"Authorization": f"Bearer {API_KEY}", "Content-Type": "application/json"},
        json={"input": text, "model": MODEL, "voice_id": VOICE_ID, "response_format": "wav"},
        timeout=60,
    )
    if resp.status_code == 429:
        time.sleep(5)
        return gen(text)
    resp.raise_for_status()
    return base64.b64decode(resp.json()["audio_data"])


def wav_snr(wav_bytes):
    sr, data = wavfile.read(io.BytesIO(wav_bytes))
    data_f = data.astype(np.float64)
    rms = np.sqrt(np.mean(data_f ** 2))
    if rms < 1.0:
        return 0.0
    frame_len = int(sr * 20 / 1000)
    n_frames = len(data_f) // frame_len
    if n_frames < 2:
        return 0.0
    frame_rms = np.array([
        np.sqrt(np.mean(data_f[i * frame_len:(i + 1) * frame_len] ** 2))
        for i in range(n_frames)
    ])
    frame_rms.sort()
    n_quiet = max(1, int(n_frames * 0.10))
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
            return wav_bytes
        return Path(dst_path).read_bytes()
    finally:
        os.unlink(src_path)
        os.unlink(dst_path)


def load_segments():
    segments = {}
    with open(SEGMENTS_FILE) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "|" not in line:
                continue
            seg_id, text = line.split("|", 1)
            segments[seg_id.strip()] = text.strip()
    return segments


def generate_segment(seg_id, text):
    best_audio = None
    best_snr = -1.0

    for i in range(CANDIDATES):
        if i > 0:
            time.sleep(0.5)
        try:
            audio = gen(text)
            snr = wav_snr(audio)
            print(f"  Candidate {i + 1}: SNR {snr:.1f} dB")
            if snr > best_snr:
                best_snr = snr
                best_audio = audio
        except Exception as e:
            print(f"  Candidate {i + 1} failed: {e}")

    return best_audio, best_snr


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("segments", nargs="*", help="Specific segment IDs")
    parser.add_argument("--all-bearings", action="store_true")
    parser.add_argument("--all-accidents", action="store_true")
    parser.add_argument("--all-reports", action="store_true")
    args = parser.parse_args()

    all_segments = load_segments()

    targets = {}
    if args.segments:
        for s in args.segments:
            if s in all_segments:
                targets[s] = all_segments[s]
            else:
                print(f"Unknown segment: {s}")
    if args.all_bearings:
        targets.update({k: v for k, v in all_segments.items() if k.startswith("bearing_")})
    if args.all_accidents:
        targets.update({k: v for k, v in all_segments.items() if k.startswith("report_accident")})
    if args.all_reports:
        targets.update({k: v for k, v in all_segments.items() if k.startswith("report_")})

    if not targets:
        targets.update({k: v for k, v in all_segments.items() if k.startswith("bearing_")})
        targets.update({k: v for k, v in all_segments.items() if k.startswith("report_accident")})

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    print(f"Regenerating {len(targets)} segments\n")

    for seg_id, text in targets.items():
        print(f"{seg_id}: \"{text}\"")
        audio, snr = generate_segment(seg_id, text)
        if audio is None:
            print(f"  FAILED\n")
            continue

        final = denoise_wav(pad_silence(audio))
        out_path = OUTPUT_DIR / f"{seg_id}.wav"
        out_path.write_bytes(final)

        asset_path = ASSETS_DIR / f"{seg_id}.wav"
        shutil.copy2(out_path, asset_path)
        print(f"  -> {asset_path} (SNR {snr:.1f} dB)\n")


if __name__ == "__main__":
    main()
