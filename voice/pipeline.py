#!/usr/bin/env python3
"""
Voice Segment Generation Pipeline

Two independent TTS pipelines for creating pre-recorded audio segments
for the DS1-Pace Android app's concatenative speech engine.

Voxtral pipeline (stages 1–4):
  1. Create voice clone + preview samples (Mistral Voxtral API)
  2. Generate all voice segments (Voxtral API)
  3. Generate speed variants (Rubberband time-stretch)
  4. Package segments for Android assets

ElevenLabs pipeline (stages 5–7):
  5. Create voice clone + preview samples (ElevenLabs IVC API)
  6. Generate all voice segments (ElevenLabs TTS API)
  7. Package segments for Android assets

Usage:
    python pipeline.py --stage 1 2 3 4   # run Voxtral pipeline
    python pipeline.py --stage 5 6 7     # run ElevenLabs pipeline
    python pipeline.py --stage 5         # clone voice + previews only
    python pipeline.py --list            # list stage descriptions
    python pipeline.py --config my.yaml  # custom config file
"""

import argparse
import importlib
import logging
import os
import sys
import time
from pathlib import Path

import yaml


def load_dotenv(env_path: Path):
    """Load KEY=VALUE lines from a .env file into os.environ."""
    if not env_path.exists():
        return
    with open(env_path) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in ('"', "'"):
                value = value[1:-1]
            os.environ.setdefault(key.strip(), value)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("pipeline")

# ── Pipeline stages ────────────────────────────────────────────────────────

STAGES = [
    (1,  "Create voice clone + preview (Voxtral)",      "stages.voxtral_voice"),
    (2,  "Generate pre-recorded segments (Voxtral)",     "stages.voxtral_segments"),
    (3,  "Generate speed variants (Voxtral/Rubberband)", "stages.voxtral_speed"),
    (4,  "Package segments for Android (Voxtral)",       "stages.segments_package"),
    (5,  "Create voice clone + preview (ElevenLabs)",    "stages.elevenlabs_voice"),
    (6,  "Generate pre-recorded segments (ElevenLabs)",  "stages.elevenlabs_segments"),
    (7,  "Package segments for Android (ElevenLabs)",    "stages.elevenlabs_package"),
]


# ── Config handling ────────────────────────────────────────────────────────

def load_config(config_path: str) -> dict:
    with open(config_path) as f:
        return yaml.safe_load(f)


def resolve_paths(cfg: dict, base_dir: Path) -> dict:
    """Turn every relative path in the config into an absolute one."""
    cfg["_base_dir"] = str(base_dir)

    out = (base_dir / cfg["general"]["output_dir"]).resolve()
    cfg["_output_dir"] = str(out)

    # Derive segments output paths from generation options
    for key in ["voxtral_segments", "elevenlabs_segments"]:
        section = cfg.get(key, {})
        candidates = section.get("candidates_per_segment", 1)
        subdir = f"{key}_{candidates}c"
        if candidates > 1:
            subdir += "_snr"
        # ElevenLabs: include voice settings in directory name
        if key == "elevenlabs_segments":
            ecfg = cfg.get("elevenlabs", {})
            sta = int(ecfg.get("stability", 1.0) * 100)
            sim = int(ecfg.get("similarity_boost", 1.0) * 100)
            spd = int(ecfg.get("speed", 0.82) * 100)
            subdir += f"_sta_{sta:03d}_sim_{sim:03d}_spd_{spd:03d}"
        # Voxtral: append _spd_100 for base segments
        if key == "voxtral_segments":
            subdir += "_spd_100"
        section["_output_path"] = str(out / subdir)

    # Voxtral voice output path
    vcfg = cfg["voxtral"]
    if "voice_output_subdir" in vcfg:
        vcfg["_voice_output_path"] = str(out / vcfg["voice_output_subdir"])
    ref = vcfg.get("reference_file")
    if ref:
        vcfg["_reference_file"] = str((base_dir / ref).resolve())
    rec_dir = vcfg.get("reference_recordings_dir")
    if rec_dir:
        vcfg["_reference_recordings_dir"] = str(
            (base_dir / rec_dir).resolve())

    # Voxtral segments file
    seg_file = cfg.get("voxtral_segments", {}).get("segments_file")
    if seg_file:
        cfg["voxtral_segments"]["_segments_file"] = str(
            (base_dir / seg_file).resolve()
        )

    # ElevenLabs voice output path + reference file
    ecfg = cfg.get("elevenlabs", {})
    if "voice_output_subdir" in ecfg:
        ecfg["_voice_output_path"] = str(out / ecfg["voice_output_subdir"])
    ref = ecfg.get("reference_file")
    if ref:
        ecfg["_reference_file"] = str((base_dir / ref).resolve())

    # ElevenLabs segments file
    seg_file = cfg.get("elevenlabs_segments", {}).get("segments_file")
    if seg_file:
        cfg["elevenlabs_segments"]["_segments_file"] = str(
            (base_dir / seg_file).resolve()
        )

    return cfg


# ── Stage runner ───────────────────────────────────────────────────────────

def run_stage(stage_num: int, desc: str, module_path: str, cfg: dict):
    log.info("=" * 64)
    log.info(f"  Stage {stage_num}: {desc}")
    log.info("=" * 64)

    mod = importlib.import_module(module_path)
    t0 = time.time()
    mod.run(cfg)
    elapsed = time.time() - t0

    minutes, seconds = divmod(int(elapsed), 60)
    if minutes:
        log.info(f"Stage {stage_num} finished in {minutes}m {seconds}s")
    else:
        log.info(f"Stage {stage_num} finished in {elapsed:.1f}s")


def main():
    parser = argparse.ArgumentParser(
        description="Voice Segment Generation Pipeline",
    )
    parser.add_argument(
        "--config", default="config.yaml",
        help="Path to YAML config file (default: config.yaml)",
    )
    parser.add_argument(
        "--stage", type=int, nargs="+",
        help="Run only the specified stage(s), e.g. --stage 1 2",
    )
    parser.add_argument(
        "--list", action="store_true",
        help="Print stage descriptions and exit",
    )
    args = parser.parse_args()

    stage_map = {num: (desc, mod) for num, desc, mod in STAGES}

    if args.list:
        print("\nVoice Segment Generation Pipeline\n")
        for num, desc, _ in STAGES:
            print(f"  {num}. {desc}")
        print()
        return

    # Load .env from the voice/ directory
    config_path = Path(args.config).resolve()
    load_dotenv(config_path.parent / ".env")

    # Load and resolve config
    if not config_path.exists():
        log.error(f"Config not found: {config_path}")
        sys.exit(1)

    cfg = load_config(str(config_path))
    cfg = resolve_paths(cfg, config_path.parent)

    stages_to_run = args.stage or [num for num, _, _ in STAGES]
    for s in stages_to_run:
        if s not in stage_map:
            log.error(
                f"Unknown stage {s}. "
                f"Valid: {list(stage_map.keys())}"
            )
            sys.exit(1)

    log.info("Voice Segment Generation Pipeline")
    log.info(f"  Config : {config_path}")
    log.info(f"  Output : {cfg['_output_dir']}")
    log.info(f"  Stages : {stages_to_run}")
    print()

    for s in stages_to_run:
        desc, mod = stage_map[s]
        run_stage(s, desc, mod, cfg)
        print()

    log.info("All requested stages complete.")


if __name__ == "__main__":
    main()
