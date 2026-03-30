#!/usr/bin/env python3
"""
Voice optimization pipeline using Optuna.

Searches TTS voice / pitch / rate / DSP-filter combinations and scores each
against reference voice recordings.  Produces optimal parameters for both
local (offline) and network TTS voices.

Usage:
    # List available voices on the device
    python optimize_voice.py --list-voices

    # List all voices including network
    python optimize_voice.py --list-all-voices

    # Run optimization for local voices with filter tuning
    python optimize_voice.py \
        --reference voice_tuning/reference/turn_left.wav \
        --text "In 200 yards, turn left" \
        --mode local

    # Run optimization for network voices with filter tuning
    python optimize_voice.py \
        --reference voice_tuning/reference/turn_left.wav \
        --text "In 200 yards, turn left" \
        --mode network

    # Multiple reference recordings
    python optimize_voice.py \
        --reference voice_tuning/reference/turn_left.wav \
                    voice_tuning/reference/speed_camera.wav \
        --text "In 200 yards, turn left" \
             "Speed camera ahead" \
        --mode local

    # Restrict to specific voices and more trials
    python optimize_voice.py \
        --reference voice_tuning/reference/turn_left.wav \
        --text "In 200 yards, turn left" \
        --voices 0 2 5 \
        --trials 300

    # Classify voice gender
    python optimize_voice.py --classify-gender \
        --reference voice_tuning/reference/turn_left.wav \
        --text "In 200 yards, turn left"

Architecture:
    ┌─────────────────────────┐       ADB broadcasts        ┌─────────────────────┐
    │  Python on PC           │ ──────────────────────────▶  │  Growler on phone   │
    │                         │   SET_VOICE, SET_PITCH,      │                     │
    │  Optuna optimizer       │   SET_RATE,                  │  SpeechService      │
    │  Resemblyzer scoring    │   SYNTHESIZE_TO_FILE         │  synthesizeToFile() │
    │  Biquad filters         │                              │                     │
    │                         │  ◀──────────────────────────  │  writes WAV to      │
    │  adb pull WAV           │       adb pull               │  external storage   │
    └─────────────────────────┘                              └─────────────────────┘

    For each Optuna trial:
      1. If voice/pitch/rate changed → synthesize on device → adb pull
         (otherwise reuse cached raw WAV)
      2. Apply DSP filter chain in Python (fast, no round-trip)
      3. Score against reference using speaker embeddings + MCD
      4. Feed score back to Optuna

    The DSP filter chain in Python exactly matches AudioPostProcessor.java:
      - Low shelf biquad
      - Peaking EQ band 1
      - Peaking EQ band 2
      - High shelf biquad
      - Compressor
"""

import argparse
import hashlib
import os
import re
import subprocess
import sys
import time

import numpy as np
import optuna
import soundfile as sf
from scipy.signal import sosfilt

# Suppress Optuna info logs
optuna.logging.set_verbosity(optuna.logging.WARNING)

PACKAGE = "com.jsd.x761.growler"
DEVICE_WAV_DIR = "/sdcard/Android/data/com.jsd.x761.growler.Growler/files"


# ---------------------------------------------------------------------------
# ADB helpers
# ---------------------------------------------------------------------------

def adb(args, timeout=30):
    """Run an adb command and return stdout.  args is a string or list."""
    if isinstance(args, str):
        full_cmd = f"adb {args}"
        result = subprocess.run(
            full_cmd, shell=True, capture_output=True, text=True, timeout=timeout
        )
    else:
        result = subprocess.run(
            ["adb"] + args, capture_output=True, text=True, timeout=timeout
        )
    return result.stdout.strip()


def adb_broadcast(action, extras_str="", extras_list=None):
    """Send a broadcast intent to the app.

    For extras with spaces (like text strings), use extras_list to build
    a properly quoted shell command.  The entire command is passed as a
    single arg to 'adb shell' (via subprocess list mode) so that only
    the device shell interprets the quotes — the local shell never sees them.
    """
    if extras_list:
        # Build a single shell command with proper quoting for the device shell
        parts = [f"am broadcast -a {PACKAGE}.{action}"]
        i = 0
        while i < len(extras_list):
            flag = extras_list[i]
            if flag in ("--es", "--ei", "--ef") and i + 2 < len(extras_list):
                key = extras_list[i + 1]
                val = extras_list[i + 2]
                parts.append(f"{flag} {key} '{val}'")
                i += 3
            else:
                parts.append(flag)
                i += 1
        # Pass as list to avoid local shell interpretation of quotes
        shell_cmd = " ".join(parts)
        result = subprocess.run(
            ["adb", "shell", shell_cmd],
            capture_output=True, text=True, timeout=30
        )
        return result.stdout.strip()
    else:
        cmd = f'shell am broadcast -a {PACKAGE}.{action} {extras_str}'
        return adb(cmd)


def set_voice(index):
    adb_broadcast("SET_VOICE", f"--ei index {index}")
    time.sleep(0.3)


def set_voice_by_name(name):
    """Set voice on main TTS by name (works for any voice including network)."""
    adb_broadcast("SET_VOICE_BY_NAME", extras_list=["--es", "voice", name])
    time.sleep(0.3)


def set_pitch(pitch):
    adb_broadcast("SET_PITCH", f"--ef pitch {pitch:.4f}")
    time.sleep(0.1)


def set_pitch_silent(pitch):
    """Set pitch without triggering a test announcement."""
    adb_broadcast("SET_PITCH_SILENT", f"--ef pitch {pitch:.4f}")
    time.sleep(0.1)


def set_rate(rate):
    adb_broadcast("SET_RATE", f"--ef rate {rate:.4f}")
    time.sleep(0.1)


def set_rate_silent(rate):
    """Set rate without triggering a test announcement."""
    adb_broadcast("SET_RATE_SILENT", f"--ef rate {rate:.4f}")
    time.sleep(0.1)


def set_tuned_voice(voice_name, network=False):
    mode = "network" if network else "local"
    adb_broadcast("SET_TUNED_VOICE",
                  extras_list=["--es", "voice", voice_name, "--es", "mode", mode])
    time.sleep(0.3)


def set_tuned_pitch(pitch, network=False):
    mode = "network" if network else "local"
    adb_broadcast("SET_TUNED_PITCH",
                  extras_list=["--ef", "pitch", f"{pitch:.4f}", "--es", "mode", mode])
    time.sleep(0.1)


def set_tuned_rate(rate, network=False):
    mode = "network" if network else "local"
    adb_broadcast("SET_TUNED_RATE",
                  extras_list=["--ef", "rate", f"{rate:.4f}", "--es", "mode", mode])
    time.sleep(0.1)


def set_tuned_filter(params_str, network=False):
    mode = "network" if network else "local"
    adb_broadcast("SET_TUNED_FILTER",
                  extras_list=["--es", "params", params_str, "--es", "mode", mode])
    time.sleep(0.1)


def synthesize_to_file(text, filename):
    """Synthesize text on device and pull the WAV file."""
    adb_broadcast("SYNTHESIZE_TO_FILE",
                  extras_list=["--es", "text", text, "--es", "filename", filename])
    # Wait for synthesis to complete
    device_path = f"{DEVICE_WAV_DIR}/{filename}"
    for _ in range(40):
        time.sleep(0.5)
        result = adb(f"shell ls -la {device_path} 2>/dev/null")
        if result and "No such file" not in result:
            # Check if file size is stable (synthesis done)
            time.sleep(0.5)
            break
    # Pull the file
    local_path = os.path.join("voice_tuning", "output", filename)
    os.makedirs(os.path.dirname(local_path), exist_ok=True)
    adb(f"pull {device_path} {local_path}")
    # Clean up on device
    adb(f"shell rm {device_path}")
    return local_path


def list_voices():
    """List available UK offline voices on device."""
    adb_broadcast("LIST_VOICES")
    time.sleep(1)
    output = adb("logcat -d -s SPEECH_SERVICE:I --format=brief")
    voices = []
    for line in output.split("\n"):
        m = re.search(r"VOICE_LIST (\d+) (\S+)", line)
        if m:
            voices.append({"index": int(m.group(1)), "name": m.group(2)})
    return voices


def list_all_voices():
    """List all English voices (local + network) on device."""
    adb_broadcast("LIST_ALL_VOICES")
    time.sleep(1)
    output = adb("logcat -d -s SPEECH_SERVICE:I --format=brief")
    voices = []
    for line in output.split("\n"):
        m = re.search(r"VOICE_LIST_ALL (\d+) (\S+) (\S+) network=(\w+)", line)
        if m:
            voices.append({
                "index": int(m.group(1)),
                "name": m.group(2),
                "locale": m.group(3),
                "network": m.group(4) == "true",
            })
    return voices


# ---------------------------------------------------------------------------
# DSP filter chain (matches AudioPostProcessor.java exactly)
# ---------------------------------------------------------------------------

def biquad_low_shelf(freq_hz, gain_db, sample_rate):
    """Compute biquad SOS coefficients for a low shelf filter."""
    A = 10 ** (gain_db / 40.0)
    w0 = 2 * np.pi * freq_hz / sample_rate
    cos_w0 = np.cos(w0)
    sin_w0 = np.sin(w0)
    alpha = sin_w0 / 2.0 * np.sqrt((A + 1.0 / A) * 1.0 + 2.0)
    sqrt_a_2alpha = 2.0 * np.sqrt(A) * alpha

    b0 = A * ((A + 1) - (A - 1) * cos_w0 + sqrt_a_2alpha)
    b1 = 2 * A * ((A - 1) - (A + 1) * cos_w0)
    b2 = A * ((A + 1) - (A - 1) * cos_w0 - sqrt_a_2alpha)
    a0 = (A + 1) + (A - 1) * cos_w0 + sqrt_a_2alpha
    a1 = -2 * ((A - 1) + (A + 1) * cos_w0)
    a2 = (A + 1) + (A - 1) * cos_w0 - sqrt_a_2alpha

    return np.array([b0 / a0, b1 / a0, b2 / a0, 1.0, a1 / a0, a2 / a0])


def biquad_high_shelf(freq_hz, gain_db, sample_rate):
    """Compute biquad SOS coefficients for a high shelf filter."""
    A = 10 ** (gain_db / 40.0)
    w0 = 2 * np.pi * freq_hz / sample_rate
    cos_w0 = np.cos(w0)
    sin_w0 = np.sin(w0)
    alpha = sin_w0 / 2.0 * np.sqrt((A + 1.0 / A) * 1.0 + 2.0)
    sqrt_a_2alpha = 2.0 * np.sqrt(A) * alpha

    b0 = A * ((A + 1) + (A - 1) * cos_w0 + sqrt_a_2alpha)
    b1 = -2 * A * ((A - 1) + (A + 1) * cos_w0)
    b2 = A * ((A + 1) + (A - 1) * cos_w0 - sqrt_a_2alpha)
    a0 = (A + 1) - (A - 1) * cos_w0 + sqrt_a_2alpha
    a1 = 2 * ((A - 1) - (A + 1) * cos_w0)
    a2 = (A + 1) - (A - 1) * cos_w0 - sqrt_a_2alpha

    return np.array([b0 / a0, b1 / a0, b2 / a0, 1.0, a1 / a0, a2 / a0])


def biquad_peaking_eq(freq_hz, gain_db, Q, sample_rate):
    """Compute biquad SOS coefficients for a peaking (bell) EQ filter."""
    A = 10 ** (gain_db / 40.0)
    w0 = 2 * np.pi * freq_hz / sample_rate
    cos_w0 = np.cos(w0)
    sin_w0 = np.sin(w0)
    alpha = sin_w0 / (2.0 * Q)

    b0 = 1 + alpha * A
    b1 = -2 * cos_w0
    b2 = 1 - alpha * A
    a0 = 1 + alpha / A
    a1 = -2 * cos_w0
    a2 = 1 - alpha / A

    return np.array([b0 / a0, b1 / a0, b2 / a0, 1.0, a1 / a0, a2 / a0])


def apply_compressor(samples, sample_rate, threshold_db, ratio,
                     attack_ms=5.0, release_ms=50.0):
    """Apply a simple feedforward compressor matching AudioPostProcessor.java."""
    if ratio <= 1.0 or threshold_db >= 0.0:
        return samples

    threshold_lin = 10 ** (threshold_db / 20.0)
    attack_coeff = np.exp(-1.0 / (attack_ms * 0.001 * sample_rate))
    release_coeff = np.exp(-1.0 / (release_ms * 0.001 * sample_rate))

    output = samples.copy()
    envelope = 0.0
    for i in range(len(output)):
        abs_val = abs(output[i])
        if abs_val > envelope:
            envelope = attack_coeff * envelope + (1.0 - attack_coeff) * abs_val
        else:
            envelope = release_coeff * envelope + (1.0 - release_coeff) * abs_val

        if envelope > threshold_lin:
            over_db = 20.0 * np.log10(envelope / threshold_lin)
            gain_reduction_db = over_db * (1.0 - 1.0 / ratio)
            gain = 10 ** (-gain_reduction_db / 20.0)
            output[i] *= gain

    return output


def apply_filter_chain(samples, sample_rate, params):
    """Apply the full DSP filter chain matching AudioPostProcessor.java."""
    # Build SOS filter stages
    sos_sections = []
    if params["low_shelf_gain"] != 0.0:
        sos_sections.append(
            biquad_low_shelf(params["low_shelf_freq"], params["low_shelf_gain"], sample_rate)
        )
    if params["peak1_gain"] != 0.0:
        sos_sections.append(
            biquad_peaking_eq(params["peak1_freq"], params["peak1_gain"], params["peak1_q"], sample_rate)
        )
    if params["peak2_gain"] != 0.0:
        sos_sections.append(
            biquad_peaking_eq(params["peak2_freq"], params["peak2_gain"], params["peak2_q"], sample_rate)
        )
    if params["high_shelf_gain"] != 0.0:
        sos_sections.append(
            biquad_high_shelf(params["high_shelf_freq"], params["high_shelf_gain"], sample_rate)
        )

    output = samples.astype(np.float64)
    if sos_sections:
        sos = np.array(sos_sections)
        output = sosfilt(sos, output)

    # Compressor
    output = apply_compressor(
        output, sample_rate,
        params["comp_threshold"], params["comp_ratio"]
    )

    # Peak normalization
    peak = np.max(np.abs(output))
    if peak > 0.001:
        output = output * (0.95 / peak)

    return output


def filter_params_to_string(params):
    """Convert filter params dict to the comma-separated format used by the app."""
    return ",".join([
        f"{params['low_shelf_gain']:.4f}",
        f"{params['low_shelf_freq']:.1f}",
        f"{params['peak1_gain']:.4f}",
        f"{params['peak1_freq']:.1f}",
        f"{params['peak1_q']:.4f}",
        f"{params['peak2_gain']:.4f}",
        f"{params['peak2_freq']:.1f}",
        f"{params['peak2_q']:.4f}",
        f"{params['high_shelf_gain']:.4f}",
        f"{params['high_shelf_freq']:.1f}",
        f"{params['comp_threshold']:.4f}",
        f"{params['comp_ratio']:.4f}",
    ])


# ---------------------------------------------------------------------------
# Scoring
# ---------------------------------------------------------------------------

_encoder = None

def _get_encoder():
    global _encoder
    if _encoder is None:
        from resemblyzer import VoiceEncoder
        _encoder = VoiceEncoder()
    return _encoder


def compute_similarity(candidate_wav, candidate_sr, reference_wav, reference_sr):
    """Compute voice similarity score (higher = better match).

    Primary metric: speaker embedding cosine similarity via resemblyzer.
    Secondary: Mel-Cepstral Distance (inverted and weighted).
    """
    from resemblyzer import preprocess_wav

    encoder = _get_encoder()

    ref_processed = preprocess_wav(reference_wav, source_sr=reference_sr)
    cand_processed = preprocess_wav(candidate_wav, source_sr=candidate_sr)

    if len(ref_processed) == 0 or len(cand_processed) == 0:
        return 0.0

    ref_embed = encoder.embed_utterance(ref_processed)
    cand_embed = encoder.embed_utterance(cand_processed)

    # Cosine similarity (range -1 to 1, typically 0.5-0.9 for same-gender voices)
    cosine_sim = float(np.dot(ref_embed, cand_embed) /
                       (np.linalg.norm(ref_embed) * np.linalg.norm(cand_embed)))

    # MCD (lower = better, invert for maximization)
    mcd = compute_mcd(candidate_wav, reference_wav)
    mcd_score = max(0, 1.0 - mcd / 20.0)  # normalize: MCD 0 → 1.0, MCD 20 → 0.0

    # Weighted combination: 80% embedding similarity, 20% MCD
    score = 0.8 * cosine_sim + 0.2 * mcd_score
    return score


def compute_mcd(candidate_wav, reference_wav):
    """Compute Mel-Cepstral Distance between two WAV arrays."""
    try:
        from mel_cepstral_distance import get_metrics_wavs
        mcd, _ = get_metrics_wavs(reference_wav, candidate_wav)
        return float(mcd)
    except Exception:
        return 10.0  # neutral fallback


def classify_gender(voices, reference_path, text):
    """Classify voices by similarity to a female reference recording.

    Returns list of (voice, similarity) tuples sorted by similarity.
    """
    ref_data, ref_sr = sf.read(reference_path)
    if ref_data.ndim > 1:
        ref_data = ref_data[:, 0]

    results = []
    for voice in voices:
        print(f"  Testing voice {voice['index']}: {voice['name']}...", end=" ", flush=True)
        set_voice_by_name(voice["name"])
        time.sleep(0.5)

        wav_path = synthesize_to_file(text, f"gender_test_{voice['index']}.wav")
        if not os.path.exists(wav_path):
            print("FAILED (no output)")
            continue

        cand_data, cand_sr = sf.read(wav_path)
        if cand_data.ndim > 1:
            cand_data = cand_data[:, 0]

        sim = compute_similarity(cand_data, cand_sr, ref_data, ref_sr)
        results.append((voice, sim))
        print(f"similarity={sim:.4f}")
        os.unlink(wav_path)

    results.sort(key=lambda x: x[1], reverse=True)
    return results


# ---------------------------------------------------------------------------
# Optimizer
# ---------------------------------------------------------------------------

class VoiceOptimizer:
    """Optuna-based optimizer for TTS voice + DSP filter parameters."""

    def __init__(self, reference_paths, texts, voices, mode="local"):
        self.reference_paths = reference_paths
        self.texts = texts
        self.voices = voices
        self.mode = mode  # "local" or "network"
        self.wav_cache = {}  # cache raw TTS output by (voice, pitch, rate)

        # Load reference recordings
        self.references = []
        for path in reference_paths:
            data, sr = sf.read(path)
            if data.ndim > 1:
                data = data[:, 0]
            self.references.append((data, sr))

    def _cache_key(self, voice_name, pitch, rate):
        return (voice_name, round(pitch, 3), round(rate, 3))

    def _get_raw_wav(self, voice_name, pitch, rate, text, trial_num):
        """Get raw TTS WAV, using cache if available."""
        key = self._cache_key(voice_name, pitch, rate)
        text_hash = hashlib.md5(text.encode()).hexdigest()[:8]
        full_key = (key, text_hash)

        if full_key in self.wav_cache:
            return self.wav_cache[full_key]

        # Synthesize on device (use silent setters to avoid queuing announcements)
        set_voice_by_name(voice_name)
        set_pitch_silent(pitch)
        set_rate_silent(rate)
        filename = f"trial_{trial_num}_{text_hash}.wav"
        wav_path = synthesize_to_file(text, filename)

        if not os.path.exists(wav_path):
            return None

        try:
            data, sr = sf.read(wav_path)
        except Exception as e:
            print(f"  Warning: bad WAV {wav_path}: {e}", flush=True)
            os.unlink(wav_path)
            return None
        if data.ndim > 1:
            data = data[:, 0]
        if len(data) == 0:
            os.unlink(wav_path)
            return None
        self.wav_cache[full_key] = (data, sr)
        os.unlink(wav_path)
        return (data, sr)

    def objective(self, trial):
        """Optuna objective: maximize voice similarity score."""
        # TTS parameters — use voice name for flexibility (works with network voices)
        voice_names = [v["name"] for v in self.voices]
        voice_name = trial.suggest_categorical("voice", voice_names)
        pitch = trial.suggest_float("pitch", 0.5, 2.0, step=0.05)
        rate = trial.suggest_float("rate", 0.5, 2.0, step=0.05)

        # DSP filter parameters
        filter_params = {
            "low_shelf_gain": trial.suggest_float("low_shelf_gain", -6.0, 6.0, step=0.5),
            "low_shelf_freq": trial.suggest_float("low_shelf_freq", 100.0, 400.0, step=25.0),
            "peak1_gain": trial.suggest_float("peak1_gain", -6.0, 6.0, step=0.5),
            "peak1_freq": trial.suggest_float("peak1_freq", 500.0, 1500.0, step=50.0),
            "peak1_q": trial.suggest_float("peak1_q", 0.5, 3.0, step=0.25),
            "peak2_gain": trial.suggest_float("peak2_gain", -6.0, 6.0, step=0.5),
            "peak2_freq": trial.suggest_float("peak2_freq", 1500.0, 4000.0, step=100.0),
            "peak2_q": trial.suggest_float("peak2_q", 0.5, 3.0, step=0.25),
            "high_shelf_gain": trial.suggest_float("high_shelf_gain", -6.0, 6.0, step=0.5),
            "high_shelf_freq": trial.suggest_float("high_shelf_freq", 3000.0, 8000.0, step=250.0),
            "comp_threshold": trial.suggest_float("comp_threshold", -30.0, 0.0, step=2.0),
            "comp_ratio": trial.suggest_float("comp_ratio", 1.0, 8.0, step=0.5),
        }

        scores = []
        for i, (ref_data, ref_sr) in enumerate(self.references):
            text = self.texts[i] if i < len(self.texts) else self.texts[0]
            raw = self._get_raw_wav(voice_name, pitch, rate, text, trial.number)
            if raw is None:
                return 0.0

            raw_data, raw_sr = raw

            # Apply DSP filter chain in Python (fast, no device round-trip)
            filtered = apply_filter_chain(raw_data, raw_sr, filter_params)

            # Score against reference
            score = compute_similarity(filtered, raw_sr, ref_data, ref_sr)
            scores.append(score)

        return float(np.mean(scores))

    def optimize(self, n_trials=150):
        """Run the optimization and return best parameters."""
        study = optuna.create_study(direction="maximize")
        study.optimize(self.objective, n_trials=n_trials, show_progress_bar=True)

        best = study.best_trial
        print(f"\n{'='*60}")
        print(f"Best score: {best.value:.4f}")
        print(f"Best parameters:")
        for key, value in best.params.items():
            print(f"  {key}: {value}")
        print(f"{'='*60}")

        return best


def apply_best_params(best_trial, voices, network=False):
    """Apply the best parameters to the device and print ADB commands."""
    params = best_trial.params
    voice_name = params["voice"]

    mode = "network" if network else "local"
    filter_params = {
        "low_shelf_gain": params["low_shelf_gain"],
        "low_shelf_freq": params["low_shelf_freq"],
        "peak1_gain": params["peak1_gain"],
        "peak1_freq": params["peak1_freq"],
        "peak1_q": params["peak1_q"],
        "peak2_gain": params["peak2_gain"],
        "peak2_freq": params["peak2_freq"],
        "peak2_q": params["peak2_q"],
        "high_shelf_gain": params["high_shelf_gain"],
        "high_shelf_freq": params["high_shelf_freq"],
        "comp_threshold": params["comp_threshold"],
        "comp_ratio": params["comp_ratio"],
    }
    filter_str = filter_params_to_string(filter_params)

    print(f"\nApplying best parameters to device ({mode})...")

    # Apply via ADB
    set_tuned_voice(voice_name, network)
    set_tuned_pitch(params["pitch"], network)
    set_tuned_rate(params["rate"], network)
    set_tuned_filter(filter_str, network)

    # Print ADB commands for reference
    print(f"\nADB commands to reproduce:")
    print(f'  adb shell am broadcast -a {PACKAGE}.SET_TUNED_VOICE '
          f'--es voice "{voice_name}" --es mode "{mode}"')
    print(f'  adb shell am broadcast -a {PACKAGE}.SET_TUNED_PITCH '
          f'--ef pitch {params["pitch"]:.4f} --es mode "{mode}"')
    print(f'  adb shell am broadcast -a {PACKAGE}.SET_TUNED_RATE '
          f'--ef rate {params["rate"]:.4f} --es mode "{mode}"')
    print(f'  adb shell am broadcast -a {PACKAGE}.SET_TUNED_FILTER '
          f'--es params "{filter_str}" --es mode "{mode}"')
    print(f'\n  # To activate this voice mode:')
    mode_int = 3 if network else 2
    print(f'  adb shell am broadcast -a {PACKAGE}.SET_VOICE_MODE '
          f'--ei mode {mode_int}')


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Optimize TTS voice parameters against reference recordings"
    )
    parser.add_argument("--list-voices", action="store_true",
                        help="List available UK offline voices on device")
    parser.add_argument("--list-all-voices", action="store_true",
                        help="List all English voices including network")
    parser.add_argument("--classify-gender", action="store_true",
                        help="Classify voice gender by similarity to reference")
    parser.add_argument("--reference", nargs="+",
                        help="Path(s) to reference voice WAV recording(s)")
    parser.add_argument("--text", nargs="+",
                        help="Text matching each reference recording")
    parser.add_argument("--mode", choices=["local", "network"], default="local",
                        help="Optimize local or network voices (default: local)")
    parser.add_argument("--voices", nargs="+", type=int,
                        help="Restrict to specific voice indices")
    parser.add_argument("--voice-names", nargs="+",
                        help="Restrict to specific voice names (e.g. en-gb-x-gba-local)")
    parser.add_argument("--trials", type=int, default=150,
                        help="Number of Optuna trials (default: 150)")
    args = parser.parse_args()

    # Clear logcat to get fresh voice list output
    adb("logcat -c")

    if args.list_voices:
        print("Listing UK offline voices on device...")
        voices = list_voices()
        for v in voices:
            print(f"  {v['index']}: {v['name']}")
        if not voices:
            print("  (no voices found — is the app running?)")
        return

    if args.list_all_voices:
        print("Listing all English voices on device...")
        voices = list_all_voices()
        for v in voices:
            net = " [NETWORK]" if v["network"] else " [LOCAL]"
            print(f"  {v['index']}: {v['name']} ({v['locale']}){net}")
        if not voices:
            print("  (no voices found — is the app running?)")
        return

    if not args.reference or not args.text:
        parser.error("--reference and --text are required for optimization")

    if len(args.reference) != len(args.text):
        parser.error("Number of --reference and --text arguments must match")

    # Verify reference files exist
    for ref_path in args.reference:
        if not os.path.exists(ref_path):
            parser.error(f"Reference file not found: {ref_path}")

    if args.classify_gender:
        print("Classifying voice gender...")
        if args.mode == "network":
            voices = list_all_voices()
        else:
            voices = list_voices()
        results = classify_gender(voices, args.reference[0], args.text[0])
        print("\nVoices ranked by similarity to reference:")
        for voice, sim in results:
            net = " [NET]" if voice.get("network") else ""
            print(f"  {voice['index']}: {voice['name']}{net} — similarity={sim:.4f}")
        return

    # Get available voices
    print(f"Getting available voices (mode={args.mode})...")
    if args.mode == "network":
        all_voices = list_all_voices()
    else:
        all_voices = list_voices()

    if not all_voices:
        print("ERROR: No voices found. Is the app running?")
        sys.exit(1)

    # Filter to requested voices
    if args.voice_names:
        voices = [v for v in all_voices if v["name"] in args.voice_names]
    elif args.voices:
        voices = [v for v in all_voices if v["index"] in args.voices]
    else:
        voices = all_voices

    print(f"Optimizing with {len(voices)} voices, {args.trials} trials...")
    for v in voices:
        net = " [NET]" if v.get("network") else ""
        print(f"  {v['index']}: {v['name']}{net}")

    optimizer = VoiceOptimizer(
        reference_paths=args.reference,
        texts=args.text,
        voices=voices,
        mode=args.mode,
    )

    best = optimizer.optimize(n_trials=args.trials)
    apply_best_params(best, voices, network=(args.mode == "network"))


if __name__ == "__main__":
    main()
