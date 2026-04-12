"""
Stage — Generate speed variants of Voxtral segments using Rubberband.

Uses FFmpeg's rubberband filter to time-stretch existing Voxtral WAV
segments (and previews) at different speeds without changing pitch.
Each speed variant is written to its own output directory.

Resume-safe: already-generated files are skipped automatically.
"""

import logging
import subprocess
from pathlib import Path

from tqdm import tqdm

log = logging.getLogger("pipeline.voxtral_speed")


def time_stretch(src_path: Path, dst_path: Path, tempo: float):
    """Time-stretch a WAV file using FFmpeg's rubberband filter.

    Uses speech-optimized settings:
      window=short    — reduces phase artifacts on monophonic speech
      detector=soft   — soft-onset detection suited to voice
      formant=preserved — keeps vocal formants stable
    """
    af = (f"rubberband=tempo={tempo}"
          ":window=short:detector=soft:formant=preserved")
    result = subprocess.run(
        ["ffmpeg", "-y", "-i", str(src_path), "-af", af, str(dst_path)],
        capture_output=True, text=True,
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"ffmpeg rubberband failed for {src_path.name}: "
            f"{result.stderr.splitlines()[-1] if result.stderr.strip() else '(no stderr)'}")


def process_dir(src_dir: Path, dst_dir: Path, tempo: float, label: str):
    """Time-stretch all WAV files from src_dir into dst_dir."""
    dst_dir.mkdir(parents=True, exist_ok=True)

    wav_files = sorted(src_dir.glob("*.wav"))
    if not wav_files:
        log.warning(f"No WAV files found in {src_dir}, skipping")
        return

    # Skip files that already exist
    remaining = [w for w in wav_files if not (dst_dir / w.name).exists()]
    if not remaining:
        log.info(f"  {label}: all {len(wav_files)} files already exist, "
                 "skipping")
        return

    log.info(f"  {label}: processing {len(remaining)} of {len(wav_files)} "
             f"files at {tempo:.2f}x tempo")

    for wav in tqdm(remaining, desc=f"{label} ({tempo:.2f}x)"):
        dst = dst_dir / wav.name
        time_stretch(wav, dst, tempo)


def run(cfg: dict):
    output_dir = Path(cfg["_output_dir"])

    vcfg = cfg.get("voxtral_speed", {})
    speeds = vcfg.get("speeds", [])
    if not speeds:
        log.info("No voxtral speed variants configured, skipping")
        return

    # Source directories (original 1.0x speed)
    segments_src = Path(cfg["voxtral_segments"]["_output_path"])
    candidates = cfg.get("voxtral_segments", {}).get(
        "candidates_per_segment", 1)
    preview_subdir = f"voxtral_preview_{candidates}c"
    preview_src = output_dir / preview_subdir

    if not segments_src.exists():
        raise FileNotFoundError(
            f"Segments directory not found at {segments_src}\n"
            "Run stage 2 (voxtral_segments) first."
        )

    # Only process speeds != 1.0 (1.0 is the source)
    variant_speeds = [s for s in speeds if s != 1.0]
    if not variant_speeds:
        log.info("No non-1.0 speed variants to generate, skipping")
        return

    log.info(f"Generating {len(variant_speeds)} Voxtral speed variant(s): "
             f"{variant_speeds}")

    for speed in variant_speeds:
        spd = int(speed * 100)
        log.info(f"Speed variant {speed:.2f}x (spd_{spd:03d}):")

        # Time-stretch segments
        seg_dst = output_dir / f"voxtral_segments_{candidates}c_spd_{spd:03d}"
        process_dir(segments_src, seg_dst, speed, "segments")

        # Time-stretch previews if they exist
        if preview_src.exists():
            prev_dst = output_dir / f"voxtral_preview_{candidates}c_spd_{spd:03d}"
            process_dir(preview_src, prev_dst, speed, "previews")

    log.info("All Voxtral speed variants generated.")
