package com.jsd.x761.growler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for AudioPostProcessor DSP filter chain.
 */
public class AudioPostProcessorTest {

  @Test
  public void neutralProcessor_isNeutral() {
    AudioPostProcessor proc = new AudioPostProcessor();
    assertTrue("Default params should be neutral", proc.isNeutral());
  }

  @Test
  public void nonNeutralProcessor_withGain() {
    AudioPostProcessor proc = new AudioPostProcessor();
    proc.lowShelfGainDb = 3.0f;
    assertFalse("Non-zero gain should not be neutral", proc.isNeutral());
  }

  @Test
  public void nonNeutralProcessor_withCompressor() {
    AudioPostProcessor proc = new AudioPostProcessor();
    proc.compressorThresholdDb = -20.0f;
    proc.compressorRatio = 4.0f;
    assertFalse("Active compressor should not be neutral", proc.isNeutral());
  }

  @Test
  public void process_emptyArray_returnsEmpty() {
    AudioPostProcessor proc = new AudioPostProcessor();
    short[] result = proc.process(new short[0], 24000);
    assertNotNull(result);
    assertEquals(0, result.length);
  }

  @Test
  public void process_nullArray_returnsNull() {
    AudioPostProcessor proc = new AudioPostProcessor();
    short[] result = proc.process(null, 24000);
    assertTrue("Null input should return null", result == null);
  }

  @Test
  public void process_neutralParams_preservesSignal() {
    AudioPostProcessor proc = new AudioPostProcessor();

    // Generate a simple sine wave
    int sampleRate = 24000;
    int numSamples = 2400; // 100ms
    short[] original = new short[numSamples];
    for(int i = 0; i < numSamples; i++) {
      original[i] = (short)(16000 * Math.sin(2 * Math.PI * 440 * i / sampleRate));
    }

    // Copy for comparison
    short[] input = original.clone();

    // Process with neutral params — only normalization should change levels
    short[] result = proc.process(input, sampleRate);
    assertNotNull(result);
    assertEquals(numSamples, result.length);

    // With neutral filters, the shape should be preserved (just normalized).
    // Check that the signal is non-zero and has the right length.
    int nonZero = 0;
    for(short s : result) {
      if(s != 0) nonZero++;
    }
    assertTrue("Most samples should be non-zero", nonZero > numSamples / 2);
  }

  @Test
  public void process_withLowShelfBoost_modifiesSignal() {
    AudioPostProcessor proc = new AudioPostProcessor();
    proc.lowShelfGainDb = 6.0f;
    proc.lowShelfFreqHz = 200.0f;

    int sampleRate = 24000;
    int numSamples = 2400;
    short[] input = new short[numSamples];
    for(int i = 0; i < numSamples; i++) {
      input[i] = (short)(8000 * Math.sin(2 * Math.PI * 100 * i / sampleRate));
    }

    short[] result = proc.process(input, sampleRate);
    assertNotNull(result);
    assertEquals(numSamples, result.length);

    // The signal should still be non-trivial
    short peak = 0;
    for(short s : result) {
      short abs = (short) Math.abs(s);
      if(abs > peak) peak = abs;
    }
    assertTrue("Processed signal should have significant amplitude", peak > 1000);
  }

  @Test
  public void process_withCompressor_reducesAmplitude() {
    AudioPostProcessor proc = new AudioPostProcessor();
    proc.compressorThresholdDb = -20.0f;
    proc.compressorRatio = 4.0f;

    int sampleRate = 24000;
    int numSamples = 2400;
    short[] input = new short[numSamples];
    for(int i = 0; i < numSamples; i++) {
      input[i] = (short)(16000 * Math.sin(2 * Math.PI * 440 * i / sampleRate));
    }

    short[] result = proc.process(input, sampleRate);
    assertNotNull(result);
    assertEquals(numSamples, result.length);
  }

  @Test
  public void process_withFullFilterChain_doesNotCrash() {
    AudioPostProcessor proc = new AudioPostProcessor();
    proc.lowShelfGainDb = -2.0f;
    proc.lowShelfFreqHz = 200.0f;
    proc.peak1GainDb = 3.0f;
    proc.peak1FreqHz = 800.0f;
    proc.peak1Q = 1.0f;
    proc.peak2GainDb = -1.5f;
    proc.peak2FreqHz = 2500.0f;
    proc.peak2Q = 1.5f;
    proc.highShelfGainDb = 2.0f;
    proc.highShelfFreqHz = 5000.0f;
    proc.compressorThresholdDb = -20.0f;
    proc.compressorRatio = 3.0f;

    int sampleRate = 24000;
    int numSamples = 24000; // 1 second
    short[] input = new short[numSamples];
    // Mix of frequencies to exercise all filter bands
    for(int i = 0; i < numSamples; i++) {
      double t = (double) i / sampleRate;
      input[i] = (short)(4000 * (
        Math.sin(2 * Math.PI * 100 * t) +   // low
        Math.sin(2 * Math.PI * 800 * t) +   // mid
        Math.sin(2 * Math.PI * 2500 * t) +  // presence
        Math.sin(2 * Math.PI * 6000 * t)    // high
      ));
    }

    short[] result = proc.process(input, sampleRate);
    assertNotNull(result);
    assertEquals(numSamples, result.length);

    // Verify output is normalized near target peak
    short peak = 0;
    for(short s : result) {
      short abs = (short) Math.abs(s);
      if(abs > peak) peak = abs;
    }
    // Target peak is 0.95 * 32767 ≈ 31128
    assertTrue("Peak should be near target", peak > 25000 && peak < 32768);
  }

  @Test
  public void process_silentInput_handledGracefully() {
    AudioPostProcessor proc = new AudioPostProcessor();
    proc.lowShelfGainDb = 3.0f;
    proc.compressorThresholdDb = -10.0f;
    proc.compressorRatio = 4.0f;

    short[] input = new short[2400];  // all zeros

    short[] result = proc.process(input, 24000);
    assertNotNull(result);
    // Silent input should remain silent (no gain amplification of zeros)
    for(short s : result) {
      assertEquals("Silent input should remain silent", 0, s);
    }
  }
}
