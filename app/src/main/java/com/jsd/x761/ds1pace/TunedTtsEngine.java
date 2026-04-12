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

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A TTS engine that synthesizes speech to a file, applies DSP post-processing
 * filters via {@link AudioPostProcessor}, and plays the result through
 * AudioTrack.  This gives full control over the audio for voice shaping.
 *
 * Two instances are typically created: one restricted to local/offline voices,
 * and one that also considers network voices.  The voice, pitch, rate, and
 * filter parameters are determined by the Optuna optimization pipeline and
 * stored in SharedPreferences.
 */
public class TunedTtsEngine {
  private static final String TAG = "TUNED_TTS";

  // Timeout for synthesis to complete (seconds)
  private static final int SYNTHESIS_TIMEOUT_SEC = 10;

  public interface Callback {
    void onStart(String utteranceId);
    void onDone(String utteranceId);
    void onError(String utteranceId);
    void onStop(String utteranceId, boolean interrupted);
  }

  private final boolean mNetworkAllowed;
  private TextToSpeech mTts;
  private boolean mReady = false;
  private Callback mCallback;
  private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
  private final AtomicBoolean mStopped = new AtomicBoolean(false);
  private final AtomicReference<AudioTrack> mCurrentTrack = new AtomicReference<>();
  private Context mContext;
  private final AudioPostProcessor mProcessor = new AudioPostProcessor();
  private final List<Voice> mAvailableVoices = new ArrayList<>();
  private String mVoiceName;
  private float mPitch = Configuration.AUDIO_SPEECH_PITCH;
  private float mRate = Configuration.AUDIO_SPEECH_RATE;

  // Track synthesis completion across threads
  private volatile CountDownLatch mSynthLatch;
  private volatile boolean mSynthSuccess;

  public TunedTtsEngine(boolean networkAllowed) {
    mNetworkAllowed = networkAllowed;
  }

  public void setCallback(Callback callback) {
    mCallback = callback;
  }

  public AudioPostProcessor getProcessor() {
    return mProcessor;
  }

  /**
   * Set the preferred voice by name.  Takes effect on the next speak() call
   * if the voice is available.
   */
  public void setVoiceName(String voiceName) {
    mVoiceName = voiceName;
    if(mTts != null && mReady) {
      applyVoice();
    }
  }

  public String getVoiceName() {
    Voice v = mTts != null ? mTts.getVoice() : null;
    return v != null ? v.getName() : mVoiceName;
  }

  public void setPitch(float pitch) {
    mPitch = Math.max(0.5f, Math.min(2.0f, pitch));
    if(mTts != null) {
      mTts.setPitch(mPitch);
    }
  }

  public void setRate(float rate) {
    mRate = Math.max(0.5f, Math.min(2.0f, rate));
    if(mTts != null) {
      mTts.setSpeechRate(mRate);
    }
  }

  public List<Voice> getAvailableVoices() {
    return mAvailableVoices;
  }

  /**
   * Initialize the engine.  Creates a TextToSpeech instance, enumerates
   * voices, and selects the configured voice.  Call from a background thread.
   */
  public void init(Context context) {
    mContext = context;
    Log.i(TAG, String.format("init networkAllowed=%b", mNetworkAllowed));

    CountDownLatch initLatch = new CountDownLatch(1);

    mTts = new TextToSpeech(context, status -> {
      if(status == TextToSpeech.SUCCESS) {
        setupTts();
        initLatch.countDown();
      }
      else {
        Log.e(TAG, "TTS init failed with status " + status);
        initLatch.countDown();
      }
    });

    try {
      initLatch.await(10, TimeUnit.SECONDS);
    }
    catch(InterruptedException e) {
      Log.e(TAG, "TTS init interrupted", e);
    }
  }

  private void setupTts() {
    mTts.setLanguage(Locale.UK);

    // Set up the synthesis completion listener
    mTts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
      @Override
      public void onStart(String utteranceId) {}

      @Override
      public void onDone(String utteranceId) {
        mSynthSuccess = true;
        if(mSynthLatch != null) mSynthLatch.countDown();
      }

      @Override
      public void onError(String utteranceId) {
        mSynthSuccess = false;
        if(mSynthLatch != null) mSynthLatch.countDown();
      }
    });

    // Enumerate available voices
    mAvailableVoices.clear();
    Set<Voice> voices = mTts.getVoices();
    if(voices != null) {
      for(Voice voice : voices) {
        // Include English voices (UK, US, AU, etc.)
        if(!"en".equals(voice.getLocale().getLanguage())) continue;
        if(!mNetworkAllowed && voice.isNetworkConnectionRequired()) continue;
        mAvailableVoices.add(voice);
        Log.i(TAG, String.format("voice: %s locale=%s network=%b quality=%d",
          voice.getName(), voice.getLocale(), voice.isNetworkConnectionRequired(),
          voice.getQuality()));
      }
    }

    applyVoice();
    mTts.setPitch(mPitch);
    mTts.setSpeechRate(mRate);

    Log.i(TAG, String.format("init complete: %d voices, selected=%s",
      mAvailableVoices.size(), mTts.getVoice() != null ? mTts.getVoice().getName() : "none"));
    mReady = true;
  }

  private void applyVoice() {
    // Try to use the configured voice name
    if(mVoiceName != null) {
      for(Voice v : mAvailableVoices) {
        if(v.getName().equals(mVoiceName)) {
          mTts.setVoice(v);
          return;
        }
      }
      Log.w(TAG, "Configured voice not found: " + mVoiceName);
    }

    // Default: prefer en-gb-x-gbc-local (local) or en-gb-x-gbc-network (network)
    String preferred = mNetworkAllowed ? "en-gb-x-gbc-network" : "en-gb-x-gbc-local";
    for(Voice v : mAvailableVoices) {
      if(v.getName().equals(preferred)) {
        mTts.setVoice(v);
        return;
      }
    }

    // Fallback: any UK voice
    for(Voice v : mAvailableVoices) {
      if(Locale.UK.equals(v.getLocale())) {
        mTts.setVoice(v);
        return;
      }
    }

    // Last resort: first available
    if(!mAvailableVoices.isEmpty()) {
      mTts.setVoice(mAvailableVoices.get(0));
    }
  }

  public boolean isReady() {
    return mReady;
  }

  /**
   * Speak text by synthesizing to a temp file, applying DSP filters, and
   * playing via AudioTrack.
   */
  public void speak(String text, String utteranceId) {
    mStopped.set(false);
    mExecutor.submit(() -> {
      if(mStopped.get()) return;
      if(mCallback != null) mCallback.onStart(utteranceId);

      try {
        // 1. Synthesize to temp file
        File tempFile = File.createTempFile("tuned_tts_", ".wav", mContext.getCacheDir());
        tempFile.deleteOnExit();

        mSynthLatch = new CountDownLatch(1);
        mSynthSuccess = false;
        String synthId = UUID.randomUUID().toString();

        int result = mTts.synthesizeToFile(text, null, tempFile, synthId);
        if(result != TextToSpeech.SUCCESS) {
          Log.e(TAG, "synthesizeToFile failed: " + result);
          if(mCallback != null) mCallback.onError(utteranceId);
          tempFile.delete();
          return;
        }

        // 2. Wait for synthesis to complete
        boolean completed = mSynthLatch.await(SYNTHESIS_TIMEOUT_SEC, TimeUnit.SECONDS);
        if(!completed || !mSynthSuccess) {
          Log.e(TAG, "Synthesis timeout or error");
          if(mCallback != null) mCallback.onError(utteranceId);
          tempFile.delete();
          return;
        }

        if(mStopped.get()) {
          if(mCallback != null) mCallback.onStop(utteranceId, true);
          tempFile.delete();
          return;
        }

        // 3. Read WAV file as PCM
        WavData wav = readWav(tempFile);
        tempFile.delete();

        if(wav == null || wav.pcm == null || wav.pcm.length == 0) {
          Log.e(TAG, "Failed to read synthesized WAV");
          if(mCallback != null) mCallback.onError(utteranceId);
          return;
        }

        // 4. Apply DSP post-processing
        mProcessor.process(wav.pcm, wav.sampleRate);

        if(mStopped.get()) {
          if(mCallback != null) mCallback.onStop(utteranceId, true);
          return;
        }

        // 5. Play via AudioTrack
        playPcm(wav.pcm, wav.sampleRate);

        if(mStopped.get()) {
          if(mCallback != null) mCallback.onStop(utteranceId, true);
        }
        else {
          if(mCallback != null) mCallback.onDone(utteranceId);
        }
      }
      catch(Exception e) {
        Log.e(TAG, "speak error", e);
        if(mCallback != null) mCallback.onError(utteranceId);
      }
    });
  }

  /**
   * Synthesize text to a WAV file with DSP filters applied.
   * Used by the optimization pipeline to produce filtered output for scoring.
   */
  public void synthesizeToFile(String text, File outputFile, Runnable onDone) {
    mExecutor.submit(() -> {
      try {
        // Synthesize raw TTS
        File tempFile = File.createTempFile("tuned_synth_", ".wav", mContext.getCacheDir());
        tempFile.deleteOnExit();

        mSynthLatch = new CountDownLatch(1);
        mSynthSuccess = false;
        String synthId = UUID.randomUUID().toString();

        int result = mTts.synthesizeToFile(text, null, tempFile, synthId);
        if(result != TextToSpeech.SUCCESS) {
          Log.e(TAG, "synthesizeToFile failed: " + result);
          tempFile.delete();
          if(onDone != null) onDone.run();
          return;
        }

        boolean completed = mSynthLatch.await(SYNTHESIS_TIMEOUT_SEC, TimeUnit.SECONDS);
        if(!completed || !mSynthSuccess) {
          Log.e(TAG, "Synthesis timeout or error");
          tempFile.delete();
          if(onDone != null) onDone.run();
          return;
        }

        // Read, process, and write back
        WavData wav = readWav(tempFile);
        tempFile.delete();

        if(wav != null && wav.pcm != null && wav.pcm.length > 0) {
          mProcessor.process(wav.pcm, wav.sampleRate);
          writeWavPcm(outputFile, wav.pcm, wav.sampleRate);
          Log.i(TAG, String.format("SYNTHESIZE_FILTERED_DONE %s", outputFile.getAbsolutePath()));
        }

        if(onDone != null) onDone.run();
      }
      catch(Exception e) {
        Log.e(TAG, "synthesizeToFile error", e);
        if(onDone != null) onDone.run();
      }
    });
  }

  public void stop() {
    mStopped.set(true);
    AudioTrack track = mCurrentTrack.getAndSet(null);
    if(track != null) {
      try {
        track.pause();
        track.flush();
        track.release();
      }
      catch(Exception ignored) {}
    }
  }

  public void shutdown() {
    stop();
    mExecutor.shutdownNow();
    if(mTts != null) {
      mTts.shutdown();
      mTts = null;
    }
    mReady = false;
  }

  private void playPcm(short[] samples, int sampleRate) {
    int bufferSize = samples.length * 2;
    AudioAttributes attrs = new AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
      .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
      .build();
    AudioFormat format = new AudioFormat.Builder()
      .setSampleRate(sampleRate)
      .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
      .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
      .build();
    AudioTrack track = new AudioTrack.Builder()
      .setAudioAttributes(attrs)
      .setAudioFormat(format)
      .setBufferSizeInBytes(bufferSize)
      .setTransferMode(AudioTrack.MODE_STATIC)
      .build();

    mCurrentTrack.set(track);
    track.write(samples, 0, samples.length);
    track.play();

    long durationMs = (long) samples.length * 1000 / sampleRate;
    long elapsed = 0;
    while(elapsed < durationMs + 100 && !mStopped.get()) {
      try { Thread.sleep(50); }
      catch(InterruptedException e) { break; }
      elapsed += 50;
    }

    AudioTrack finished = mCurrentTrack.getAndSet(null);
    if(finished != null) {
      try {
        finished.stop();
        finished.release();
      }
      catch(Exception ignored) {}
    }
  }

  // --- WAV file I/O ---

  private static class WavData {
    final short[] pcm;
    final int sampleRate;
    WavData(short[] pcm, int sampleRate) {
      this.pcm = pcm;
      this.sampleRate = sampleRate;
    }
  }

  /**
   * Read a WAV file as 16-bit PCM mono samples and extract the sample rate.
   * Android TTS synthesizeToFile always produces 16-bit PCM.
   */
  private WavData readWav(File file) {
    try(FileInputStream fis = new FileInputStream(file)) {
      byte[] allBytes = fis.readAllBytes();
      if(allBytes.length < 44) return null;

      ByteBuffer buf = ByteBuffer.wrap(allBytes).order(ByteOrder.LITTLE_ENDIAN);

      // Verify RIFF/WAVE header
      buf.position(0);
      if(buf.getInt() != 0x46464952) return null; // "RIFF"
      buf.getInt(); // chunk size
      if(buf.getInt() != 0x45564157) return null; // "WAVE"

      // Find fmt and data chunks
      int numChannels = 1;
      int sampleRate = 22050;
      int dataOffset = 0;
      int dataSize = 0;

      while(buf.remaining() >= 8) {
        int chunkId = buf.getInt();
        int chunkSize = buf.getInt();

        if(chunkId == 0x20746d66) { // "fmt "
          buf.getShort(); // audio format
          numChannels = buf.getShort() & 0xFFFF;
          sampleRate = buf.getInt();
          buf.getInt(); // byte rate
          buf.getShort(); // block align
          buf.getShort(); // bits per sample
          int remaining = chunkSize - 16;
          if(remaining > 0) buf.position(buf.position() + remaining);
        }
        else if(chunkId == 0x61746164) { // "data"
          dataOffset = buf.position();
          dataSize = chunkSize;
          break;
        }
        else {
          buf.position(buf.position() + chunkSize);
        }
      }

      if(dataOffset == 0) return null;

      buf.position(dataOffset);
      int numSamples = dataSize / (2 * numChannels);
      short[] pcm = new short[numSamples];
      for(int i = 0; i < numSamples; i++) {
        pcm[i] = buf.getShort();
        // Skip extra channels
        for(int ch = 1; ch < numChannels; ch++) buf.getShort();
      }
      return new WavData(pcm, sampleRate);
    }
    catch(IOException e) {
      Log.e(TAG, "readWav error", e);
      return null;
    }
  }

  /**
   * Write 16-bit PCM mono samples to a WAV file.
   */
  private void writeWavPcm(File file, short[] pcm, int sampleRate) throws IOException {
    int dataSize = pcm.length * 2;
    int fileSize = 36 + dataSize;
    byte[] header = new byte[44];
    ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);

    // RIFF header
    buf.putInt(0x46464952);    // "RIFF"
    buf.putInt(fileSize);
    buf.putInt(0x45564157);    // "WAVE"

    // fmt chunk
    buf.putInt(0x20746d66);    // "fmt "
    buf.putInt(16);             // chunk size
    buf.putShort((short) 1);   // PCM
    buf.putShort((short) 1);   // mono
    buf.putInt(sampleRate);
    buf.putInt(sampleRate * 2); // byte rate
    buf.putShort((short) 2);   // block align
    buf.putShort((short) 16);  // bits per sample

    // data chunk
    buf.putInt(0x61746164);    // "data"
    buf.putInt(dataSize);

    try(java.io.FileOutputStream fos = new java.io.FileOutputStream(file)) {
      fos.write(header);
      ByteBuffer dataBuf = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN);
      for(short s : pcm) dataBuf.putShort(s);
      fos.write(dataBuf.array());
    }
  }
}
