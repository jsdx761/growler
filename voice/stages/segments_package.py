"""
Stage 3 — Package Voxtral voice segments for Android app deployment.

Converts generated WAV segments to OGG Vorbis (transparent quality at
~96 kbps, ~8x smaller than PCM WAV) and copies them into the Android
app's assets/voice_segments/ directory.  Generates a segments_index.txt
manifest listing all available segment IDs.

The original WAV files are preserved in the output directory.
"""

import logging
import subprocess
from pathlib import Path

from tqdm import tqdm

log = logging.getLogger("pipeline.segments_package")

# Vorbis quality 5 at 24 kHz mono ≈ 96 kbps — perceptually transparent
# for speech.  Preserves the 24 kHz sample rate (unlike Opus which
# always decodes to 48 kHz).
VORBIS_QUALITY = "5"


def wav_to_ogg(wav_path: Path, ogg_path: Path):
    """Convert a WAV file to OGG Vorbis."""
    result = subprocess.run(
        ["ffmpeg", "-y", "-i", str(wav_path),
         "-c:a", "libvorbis", "-q:a", VORBIS_QUALITY, str(ogg_path)],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"ffmpeg WAV→OGG failed for {wav_path.name}: "
            f"{result.stderr.splitlines()[-1]}")


def run(cfg: dict):
    src_dir = Path(cfg["voxtral_segments"]["_output_path"])
    base_dir = Path(cfg["_base_dir"])
    project_root = base_dir.parent
    assets_dir = (project_root / "app" / "src" / "main"
                  / "assets" / "voice_segments_voxtral")
    assets_dir.mkdir(parents=True, exist_ok=True)

    if not src_dir.exists():
        raise FileNotFoundError(
            f"Segments directory not found at {src_dir}\n"
            "Run stage 2 (voxtral_segments) first."
        )

    wav_files = sorted(src_dir.glob("*.wav"))
    if not wav_files:
        raise FileNotFoundError(f"No WAV files found in {src_dir}")

    log.info(f"Packaging {len(wav_files)} segments from {src_dir}")
    log.info("Converting WAV → OGG Vorbis (quality %s)", VORBIS_QUALITY)

    segment_ids = []
    for wav in tqdm(wav_files, desc="Converting to OGG"):
        ogg_dst = assets_dir / (wav.stem + ".ogg")
        wav_to_ogg(wav, ogg_dst)
        segment_ids.append(wav.stem)

    index_path = assets_dir / "segments_index.txt"
    index_path.write_text("\n".join(segment_ids) + "\n")

    wav_size = sum(w.stat().st_size for w in wav_files)
    ogg_size = sum((assets_dir / (w.stem + ".ogg")).stat().st_size
                   for w in wav_files)
    ratio = wav_size / ogg_size if ogg_size > 0 else 0

    log.info(f"Packaged {len(segment_ids)} segments to {assets_dir}")
    log.info(f"Size: {wav_size / 1024 / 1024:.1f} MB WAV → "
             f"{ogg_size / 1024 / 1024:.1f} MB OGG ({ratio:.1f}x reduction)")
    log.info(f"Index written to {index_path}")
