/*
 * Copyright (c) 2023 jsdx761
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.jsd.x761.ds1pace;

/**
 * Audio post-processor that applies a chain of biquad IIR filters and
 * dynamics compression to PCM audio.  Used to shape TTS output toward
 * a reference voice profile.
 *
 * The filter chain consists of:
 *   1. Low shelf  — controls warmth / body (~200 Hz)
 *   2. Peaking EQ — adjusts first formant region (~800 Hz)
 *   3. Peaking EQ — adjusts second formant / presence (~2500 Hz)
 *   4. High shelf — controls air / brightness (~5000 Hz)
 *   5. Compressor — dynamic range control
 *   6. Peak normalization
 *
 * Biquad coefficient formulas follow Robert Bristow-Johnson's Audio EQ
 * Cookbook (direct form II transposed).  The Python optimizer uses the
 * same formulas via scipy so results are interchangeable.
 */
public class AudioPostProcessor {

  // --- Filter parameters (set by optimizer, stored in SharedPreferences) ---

  // Low shelf filter
  public float lowShelfGainDb = 0.0f;
  public float lowShelfFreqHz = 200.0f;

  // Peaking EQ band 1 (first formant region)
  public float peak1GainDb = 0.0f;
  public float peak1FreqHz = 800.0f;
  public float peak1Q = 1.0f;

  // Peaking EQ band 2 (second formant / presence)
  public float peak2GainDb = 0.0f;
  public float peak2FreqHz = 2500.0f;
  public float peak2Q = 1.0f;

  // High shelf filter
  public float highShelfGainDb = 0.0f;
  public float highShelfFreqHz = 5000.0f;

  // Compressor
  public float compressorThresholdDb = 0.0f;  // 0 = disabled
  public float compressorRatio = 1.0f;        // 1.0 = no compression
  public float compressorAttackMs = 5.0f;
  public float compressorReleaseMs = 50.0f;

  // Peak normalization target
  public float targetPeak = 0.95f;

  /**
   * Returns true if all filter gains are zero and compressor is disabled,
   * meaning processing would be a no-op (except normalization).
   */
  public boolean isNeutral() {
    return lowShelfGainDb == 0.0f
      && peak1GainDb == 0.0f
      && peak2GainDb == 0.0f
      && highShelfGainDb == 0.0f
      && (compressorRatio <= 1.0f || compressorThresholdDb >= 0.0f);
  }

  /**
   * Process PCM audio through the full filter chain.
   *
   * @param samples  16-bit PCM samples (modified in place for efficiency)
   * @param sampleRate  sample rate in Hz
   * @return processed samples (same array, modified in place)
   */
  public short[] process(short[] samples, int sampleRate) {
    if(samples == null || samples.length == 0) return samples;

    // Convert to float for processing
    float[] floats = new float[samples.length];
    for(int i = 0; i < samples.length; i++) {
      floats[i] = samples[i] / 32768.0f;
    }

    // Apply biquad filter chain
    if(lowShelfGainDb != 0.0f) {
      applyLowShelf(floats, sampleRate, lowShelfFreqHz, lowShelfGainDb);
    }
    if(peak1GainDb != 0.0f) {
      applyPeakingEQ(floats, sampleRate, peak1FreqHz, peak1GainDb, peak1Q);
    }
    if(peak2GainDb != 0.0f) {
      applyPeakingEQ(floats, sampleRate, peak2FreqHz, peak2GainDb, peak2Q);
    }
    if(highShelfGainDb != 0.0f) {
      applyHighShelf(floats, sampleRate, highShelfFreqHz, highShelfGainDb);
    }

    // Apply compressor
    if(compressorRatio > 1.0f && compressorThresholdDb < 0.0f) {
      applyCompressor(floats, sampleRate);
    }

    // Convert back to short with peak normalization
    float peak = 0.0f;
    for(float s : floats) {
      float abs = Math.abs(s);
      if(abs > peak) peak = abs;
    }
    float gain = (peak > 0.001f) ? targetPeak / peak : 1.0f;

    for(int i = 0; i < samples.length; i++) {
      int val = Math.round(floats[i] * gain * 32767.0f);
      samples[i] = (short) Math.max(-32768, Math.min(32767, val));
    }

    return samples;
  }

  // --- Biquad filter implementations ---
  // Direct form II transposed: more numerically stable than direct form I

  /**
   * Apply a low shelf biquad filter in place.
   */
  private void applyLowShelf(float[] samples, int sampleRate, float freqHz, float gainDb) {
    double A = Math.pow(10.0, gainDb / 40.0);
    double w0 = 2.0 * Math.PI * freqHz / sampleRate;
    double cosW0 = Math.cos(w0);
    double sinW0 = Math.sin(w0);
    double alpha = sinW0 / 2.0 * Math.sqrt((A + 1.0 / A) * 1.0 + 2.0);
    double sqrtA2alpha = 2.0 * Math.sqrt(A) * alpha;

    double b0 = A * ((A + 1) - (A - 1) * cosW0 + sqrtA2alpha);
    double b1 = 2 * A * ((A - 1) - (A + 1) * cosW0);
    double b2 = A * ((A + 1) - (A - 1) * cosW0 - sqrtA2alpha);
    double a0 = (A + 1) + (A - 1) * cosW0 + sqrtA2alpha;
    double a1 = -2 * ((A - 1) + (A + 1) * cosW0);
    double a2 = (A + 1) + (A - 1) * cosW0 - sqrtA2alpha;

    applyBiquad(samples, b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0);
  }

  /**
   * Apply a high shelf biquad filter in place.
   */
  private void applyHighShelf(float[] samples, int sampleRate, float freqHz, float gainDb) {
    double A = Math.pow(10.0, gainDb / 40.0);
    double w0 = 2.0 * Math.PI * freqHz / sampleRate;
    double cosW0 = Math.cos(w0);
    double sinW0 = Math.sin(w0);
    double alpha = sinW0 / 2.0 * Math.sqrt((A + 1.0 / A) * 1.0 + 2.0);
    double sqrtA2alpha = 2.0 * Math.sqrt(A) * alpha;

    double b0 = A * ((A + 1) + (A - 1) * cosW0 + sqrtA2alpha);
    double b1 = -2 * A * ((A - 1) + (A + 1) * cosW0);
    double b2 = A * ((A + 1) + (A - 1) * cosW0 - sqrtA2alpha);
    double a0 = (A + 1) - (A - 1) * cosW0 + sqrtA2alpha;
    double a1 = 2 * ((A - 1) - (A + 1) * cosW0);
    double a2 = (A + 1) - (A - 1) * cosW0 - sqrtA2alpha;

    applyBiquad(samples, b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0);
  }

  /**
   * Apply a peaking (bell) EQ biquad filter in place.
   */
  private void applyPeakingEQ(float[] samples, int sampleRate, float freqHz, float gainDb, float Q) {
    double A = Math.pow(10.0, gainDb / 40.0);
    double w0 = 2.0 * Math.PI * freqHz / sampleRate;
    double cosW0 = Math.cos(w0);
    double sinW0 = Math.sin(w0);
    double alpha = sinW0 / (2.0 * Q);

    double b0 = 1 + alpha * A;
    double b1 = -2 * cosW0;
    double b2 = 1 - alpha * A;
    double a0 = 1 + alpha / A;
    double a1 = -2 * cosW0;
    double a2 = 1 - alpha / A;

    applyBiquad(samples, b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0);
  }

  /**
   * Apply a biquad filter (direct form II transposed) in place.
   */
  private void applyBiquad(float[] samples, double b0, double b1, double b2, double a1, double a2) {
    double z1 = 0.0, z2 = 0.0;
    for(int i = 0; i < samples.length; i++) {
      double x = samples[i];
      double y = b0 * x + z1;
      z1 = b1 * x - a1 * y + z2;
      z2 = b2 * x - a2 * y;
      samples[i] = (float) y;
    }
  }

  /**
   * Apply a simple feedforward compressor with envelope following.
   */
  private void applyCompressor(float[] samples, int sampleRate) {
    double thresholdLin = Math.pow(10.0, compressorThresholdDb / 20.0);
    double attackCoeff = Math.exp(-1.0 / (compressorAttackMs * 0.001 * sampleRate));
    double releaseCoeff = Math.exp(-1.0 / (compressorReleaseMs * 0.001 * sampleRate));

    double envelope = 0.0;
    for(int i = 0; i < samples.length; i++) {
      double abs = Math.abs(samples[i]);

      // Smooth envelope follower
      if(abs > envelope) {
        envelope = attackCoeff * envelope + (1.0 - attackCoeff) * abs;
      }
      else {
        envelope = releaseCoeff * envelope + (1.0 - releaseCoeff) * abs;
      }

      // Apply compression above threshold
      if(envelope > thresholdLin) {
        double overDb = 20.0 * Math.log10(envelope / thresholdLin);
        double gainReductionDb = overDb * (1.0 - 1.0 / compressorRatio);
        double gain = Math.pow(10.0, -gainReductionDb / 20.0);
        samples[i] *= (float) gain;
      }
    }
  }
}
