"""
Stage 6 — Package configured ElevenLabs voice segment sets for Android.

Reads the package_speeds list from config and packages each matching
elevenlabs_segments_* directory into its own Android asset directory
(e.g. speed 0.82 → voice_segments_elevenlabs_082/).

Converts WAV to OGG Vorbis and generates a segments_index.txt manifest.
The original WAV files are preserved in the output directory.
"""

import logging
import subprocess
from pathlib import Path

from tqdm import tqdm

log = logging.getLogger("pipeline.elevenlabs_package")

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
            f"{result.stderr.splitlines()[-1] if result.stderr.strip() else '(no stderr)'}")


def package_set(src_dir: Path, assets_dir: Path):
    """Package a single set of WAV segments to OGG in the given assets dir."""
    assets_dir.mkdir(parents=True, exist_ok=True)

    wav_files = sorted(src_dir.glob("*.wav"))
    if not wav_files:
        log.warning(f"No WAV files found in {src_dir}, skipping")
        return

    log.info(f"Packaging {len(wav_files)} segments from {src_dir.name}")
    log.info("Converting WAV → OGG Vorbis (quality %s)", VORBIS_QUALITY)

    segment_ids = []
    for wav in tqdm(wav_files, desc=f"Converting {src_dir.name}"):
        ogg_dst = assets_dir / (wav.stem + ".ogg")
        wav_to_ogg(wav, ogg_dst)
        segment_ids.append(wav.stem)

    index_path = assets_dir / "segments_index.txt"
    index_path.write_text("\n".join(segment_ids) + "\n")

    wav_size = sum(w.stat().st_size for w in wav_files)
    ogg_size = sum((assets_dir / (w.stem + ".ogg")).stat().st_size
                   for w in wav_files)
    ratio = wav_size / ogg_size if ogg_size > 0 else 0

    log.info(f"  → {assets_dir.name}/ "
             f"({wav_size / 1024 / 1024:.1f} MB WAV → "
             f"{ogg_size / 1024 / 1024:.1f} MB OGG, {ratio:.1f}x reduction)")


def run(cfg: dict):
    output_dir = Path(cfg["_output_dir"])
    base_dir = Path(cfg["_base_dir"])
    project_root = base_dir.parent
    assets_base = project_root / "app" / "src" / "main" / "assets"

    ecfg = cfg["elevenlabs"]
    scfg = cfg["elevenlabs_segments"]

    # Use package_speeds list from config; fall back to current speed
    speeds = ecfg.get("package_speeds", [ecfg.get("speed", 0.82)])

    # Derive the base segment dir pattern from the current config
    # (candidates, stability, similarity are shared across speed variants)
    candidates = scfg.get("candidates_per_segment", 1)
    sta = int(ecfg.get("stability", 1.0) * 100)
    sim = int(ecfg.get("similarity_boost", 1.0) * 100)
    base_pattern = f"elevenlabs_segments_{candidates}c_sta_{sta:03d}_sim_{sim:03d}"

    log.info(f"Packaging {len(speeds)} ElevenLabs speed variant(s): {speeds}")

    for speed in speeds:
        spd = int(speed * 100)
        src_dir = output_dir / f"{base_pattern}_spd_{spd:03d}"
        if not src_dir.exists():
            log.error(f"Segments directory not found: {src_dir}")
            log.error("Generate segments with speed=%.2f first (stage 5).", speed)
            continue
        assets_dir = assets_base / f"voice_segments_elevenlabs_{spd:03d}"
        package_set(src_dir, assets_dir)

    log.info("All configured ElevenLabs segment sets packaged.")
