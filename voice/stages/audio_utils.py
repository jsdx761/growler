"""Shared audio utility functions for voice pipeline stages."""

import io
import os
import subprocess
import tempfile
from pathlib import Path

import numpy as np
import scipy.io.wavfile as wavfile


def get_elevenlabs_api_key() -> str:
    key = os.environ.get("ELEVENLABS_API_KEY")
    if not key:
        raise RuntimeError(
            "ELEVENLABS_API_KEY environment variable is not set. "
            "Add it to .env or export it before running the pipeline."
        )
    return key


def get_mistral_api_key() -> str:
    key = os.environ.get("MISTRAL_API_KEY")
    if not key:
        raise RuntimeError(
            "MISTRAL_API_KEY environment variable is not set. "
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
    src_path = None
    dst_path = None
    try:
        with tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as src:
            src.write(mp3_bytes)
            src.flush()
            src_path = src.name
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as dst:
            dst_path = dst.name

        subprocess.run(
            ["ffmpeg", "-y", "-i", src_path, "-ar", str(target_sr),
             "-ac", "1", dst_path],
            capture_output=True, text=True, check=True,
        )
        return Path(dst_path).read_bytes()
    finally:
        if src_path and os.path.exists(src_path):
            os.unlink(src_path)
        if dst_path and os.path.exists(dst_path):
            os.unlink(dst_path)


def ffmpeg_last_error(result) -> str:
    """Safely extract the last line of stderr from a subprocess result."""
    if result.stderr and result.stderr.strip():
        return result.stderr.splitlines()[-1]
    return "(no stderr output)"
