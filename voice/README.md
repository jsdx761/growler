# Voice Segment Generation Pipeline

Generates pre-recorded voice segments for the Growler Android app using
voice cloning and text-to-speech APIs. Two independent TTS providers are
supported — Mistral Voxtral (stages 1–3) and ElevenLabs (stages 4–6) —
each producing equivalent output from the same segment manifest. Choose
one based on voice quality preference and cost.

The app's `PreRecordedTtsEngine` loads these segments at runtime and
concatenates them with crossfading to produce natural-sounding alert
announcements — similar to how high-end car navigation systems work.

## Prerequisites

- [pyenv](https://github.com/pyenv/pyenv#installation) with the
  [pyenv-virtualenv](https://github.com/pyenv/pyenv-virtualenv) plugin
- A [Mistral API key](https://console.mistral.ai/) with Voxtral access,
  an [ElevenLabs API key](https://elevenlabs.io/), or both
- Reference voice recordings (see below)

## Setup

### 1. Reference Recordings

WAV files are gitignored. On a fresh clone, place your reference voice
recordings in `voice/reference_recordings/`:

```
voice/reference_recordings/
├── segment_0001.wav          # Individual voice clips
├── segment_0002.wav
├── ...
├── combined_raw.wav          # All segments concatenated (raw)
└── combined_raw-esv2-speech-100p.wav  # Adobe Enhanced Speech V2
```

The pipeline uses `combined_raw-esv2-speech-100p.wav` as the primary
reference for voice cloning (configured in `config.yaml`). Generate the
combined files by concatenating the individual segments, then run them
through [Adobe Podcast Enhance Speech](https://podcast.adobe.com/enhance).

The setup script will warn if no WAV files are found in this directory.

### 2. Python Environment

Run the setup script once to create the Python environment and install
dependencies:

```bash
cd voice/
./setup.sh
```

This will:
1. Install Python 3.12 via pyenv (if not already present)
2. Create a `growler-voice` pyenv virtualenv
3. Install the required Python packages

### 3. API Keys

Set your API keys in `voice/.env`:

```bash
cp .env.example .env
# Edit .env and set your keys (Mistral and/or ElevenLabs)
```

The `.env` file is gitignored. The pipeline loads it automatically.

## Generating Voice Segments

All pipeline commands must be run from the `voice/` directory:

```bash
cd voice/
python pipeline.py
```

Or run individual stages (Voxtral pipeline):

```bash
python pipeline.py --stage 1    # Create voice clone + preview samples
python pipeline.py --stage 2    # Generate all voice segments
python pipeline.py --stage 3    # Package segments into Android assets
```

Or use the ElevenLabs pipeline instead:

```bash
python pipeline.py --stage 4    # Create voice clone + preview samples
python pipeline.py --stage 5    # Generate all voice segments
python pipeline.py --stage 6    # Package segments for Android assets
```

List available stages:

```bash
python pipeline.py --list
```

### Stage Details

#### Stage 1: Create Voice Clone (Voxtral)

Uploads the configured reference recording (default:
`reference_recordings/combined_raw-esv2-speech-100p.wav`) to the Mistral
Voxtral API to create a custom voice. Generates preview samples for
evaluation.

- **Output:** `output/voxtral_voice/voice_info.json` + `preview_*.wav`
- **Resume-safe:** Reuses an existing voice if `voice_info.json` exists
- **Listen** to the preview samples before proceeding to stage 2

#### Stage 2: Generate Segments (Voxtral)

Reads the segment manifest (`data/voice_segments.txt`) and synthesises each
segment via the Voxtral API using the cloned voice. Includes RMS volume
consistency checking — outlier samples are retried and the most consistent
take is kept.

- **Output:** `output/voxtral_segments/*.wav` (one WAV per segment)
- **Resume-safe:** Already-generated files are skipped

#### Stage 3: Package for Android (Voxtral)

Converts generated WAV segments to OGG Vorbis and copies them with a
`segments_index.txt` manifest into `app/src/main/assets/voice_segments/`,
ready for the Android build.

- **Output:** `../app/src/main/assets/voice_segments/`
- After this stage, build the Android app normally with Gradle

#### Stage 4: Create Voice Clone (ElevenLabs)

Uploads the reference recording to the ElevenLabs Instant Voice Cloning
API. Generates preview samples for evaluation and comparison against
the Voxtral pipeline.

- **Output:** `output/elevenlabs_voice/voice_info.json` + preview WAVs
- **Resume-safe:** Reuses an existing voice if `voice_info.json` exists

#### Stage 5: Generate Segments (ElevenLabs)

Reads the same segment manifest and synthesises each segment via the
ElevenLabs TTS API using the cloned voice.

- **Output:** `output/elevenlabs_segments_*/*.wav` (one WAV per segment)
- **Resume-safe:** Already-generated files are skipped

#### Stage 6: Package for Android (ElevenLabs)

Converts generated WAV segments to OGG Vorbis and copies them with a
manifest into the Android assets directory. Supports packaging multiple
speed variants (configured via `package_speeds` in `config.yaml`).

- **Output:** `../app/src/main/assets/voice_segments_elevenlabs_*/`
- After this stage, build the Android app normally with Gradle

## Customisation

### Reference Voice

The WAV files in `reference_recordings/` are the voice samples used for
cloning (gitignored — see setup instructions above). To use a different voice:

1. Replace the WAV files in `reference_recordings/`
2. Regenerate the combined/enhanced files
3. Delete the voice clone output (`output/voxtral_voice/` or
   `output/elevenlabs_voice/`) to force a new clone
4. Re-run the pipeline from stage 1 (Voxtral) or stage 4 (ElevenLabs)

### Adding or Modifying Segments

Voice segments are defined in `data/voice_segments.txt`. Each line has
the format:

```
segment_id|Text to be spoken
```

Lines starting with `#` are comments. The `segment_id` is used as the
filename and as the lookup key in the Android app.

After editing the manifest:

1. Delete the WAV files in `output/voxtral_segments/` that you want
   to regenerate (or delete the whole directory to regenerate all)
2. Re-run stages 2 and 3: `python pipeline.py --stage 2 3`

### Configuration

Edit `config.yaml` to change TTS provider settings — model, voice name,
rate limiting, and ElevenLabs-specific options like stability, similarity
boost, and speed variants.

## Directory Structure

```
voice/
├── config.yaml              # Pipeline configuration
├── pipeline.py              # Pipeline entry point
├── setup.sh                 # One-time environment setup (pyenv)
├── requirements.txt         # Python dependencies
├── README.md                # This file
├── data/
│   └── voice_segments.txt   # Segment manifest (segment_id|text)
├── reference_recordings/    # Voice reference samples for cloning (gitignored)
│   ├── combined_raw-esv2-speech-100p.wav  # Primary reference (Enhanced Speech V2)
│   ├── segment_0001.wav
│   └── ...
├── stages/                     # Pipeline stage modules
│   ├── voxtral_voice.py        # Stage 1: voice creation (Voxtral)
│   ├── voxtral_segments.py     # Stage 2: segment generation (Voxtral)
│   ├── segments_package.py     # Stage 3: Android packaging (Voxtral)
│   ├── elevenlabs_voice.py     # Stage 4: voice creation (ElevenLabs)
│   ├── elevenlabs_segments.py  # Stage 5: segment generation (ElevenLabs)
│   └── elevenlabs_package.py   # Stage 6: Android packaging (ElevenLabs)
└── output/                     # Generated files (gitignored)
    ├── voxtral_voice/          # Voxtral voice clone info + previews
    ├── voxtral_segments_*/     # Voxtral generated WAV segments
    ├── elevenlabs_voice/       # ElevenLabs voice clone info + previews
    └── elevenlabs_segments_*/  # ElevenLabs generated WAV segments
```
