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
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A speech engine that plays pre-recorded audio segments concatenated at
 * runtime with crossfading, similar to high-end car navigation systems.
 *
 * Segments are OGG Vorbis files stored in assets/voice_segments/, generated
 * offline by the Voxtral or ElevenLabs TTS pipeline with a cloned voice.
 * At runtime, the engine lazily decodes segments into PCM short arrays —
 * a background thread preloads all segments after init, and any segment
 * not yet preloaded is decoded on demand when speak() is called.
 */
public class PreRecordedTtsEngine {
  private static final String TAG = "PRERECORDED_TTS";
  private static final String DEFAULT_SEGMENTS_DIR = "voice_segments_voxtral_100";

  // Silence padding at the start and end of the announcement to avoid
  // AudioTrack startup clicks
  private static final int LEAD_SILENCE_MS = 150;
  private static final int TRAIL_SILENCE_MS = 100;

  // Gentle fade-out at the end to avoid a hard stop
  private static final int FADE_OUT_MS = 30;

  // Very short crossfade at segment junctions to eliminate clicks
  // caused by waveform discontinuities (segment ending at non-zero
  // sample followed by silence gap at zero)
  private static final int JUNCTION_FADE_MS = 5;

  // Peak normalization target
  private static final float TARGET_PEAK = 0.95f;

  public interface Callback {
    void onStart(String utteranceId);
    void onDone(String utteranceId);
    void onError(String utteranceId);
    void onStop(String utteranceId, boolean interrupted);
  }

  private final ConcurrentHashMap<String, short[]> mSegments = new ConcurrentHashMap<>();
  private final Set<String> mSegmentIds = ConcurrentHashMap.newKeySet();
  private String mSegmentsDir;
  private AssetManager mAssets;
  private int mSampleRate = 24000;
  private volatile boolean mReady = false;
  private Callback mCallback;
  private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
  private final AtomicBoolean mStopped = new AtomicBoolean(false);
  private final AtomicReference<AudioTrack> mCurrentTrack = new AtomicReference<>();
  private float mSpeed = 1.0f;

  public void setCallback(Callback callback) {
    mCallback = callback;
  }

  /**
   * Set the playback speed. Values below 1.0 slow down (and lower pitch),
   * values above 1.0 speed up (and raise pitch). Clamped to 0.5–2.0.
   */
  public void setSpeed(float speed) {
    mSpeed = Math.max(0.5f, Math.min(2.0f, speed));
  }

  /**
   * Index all voice segments and start background preloading.
   * Returns immediately — segments are decoded lazily on demand and
   * preloaded in the background so subsequent requests are instant.
   */
  public void init(Context context) {
    init(context, DEFAULT_SEGMENTS_DIR);
  }

  public void init(Context context, String segmentsDir) {
    mSegmentsDir = segmentsDir;
    String indexFile = mSegmentsDir + "/segments_index.txt";
    Log.i(TAG, "init: indexing voice segments from assets/" + mSegmentsDir);
    mAssets = context.getApplicationContext().getAssets();

    try(InputStream indexStream = mAssets.open(indexFile);
        BufferedReader reader = new BufferedReader(new InputStreamReader(indexStream, StandardCharsets.UTF_8))) {
      // Read the index file (fast — just a string list)
      String line;
      while((line = reader.readLine()) != null) {
        line = line.trim();
        if(!line.isEmpty()) mSegmentIds.add(line);
      }
    }
    catch(IOException e) {
      Log.e(TAG, "Failed to load segments index", e);
      return;
    }

    mReady = true;
    Log.i(TAG, String.format("init: %d segments indexed, preloading in background",
        mSegmentIds.size()));

    // Preload all segments in a background thread
    Thread preloader = new Thread(() -> {
      int loaded = 0;
      for(String id : mSegmentIds) {
        if(!mSegments.containsKey(id)) {
          loadSegmentById(id);
          loaded++;
        }
      }
      Log.i(TAG, String.format("preload complete: %d segments decoded, sampleRate=%d",
          loaded, mSampleRate));
    }, "segment-preload");
    preloader.setDaemon(true);
    preloader.start();
  }

  public boolean isReady() {
    return mReady;
  }

  /**
   * Check if a specific segment ID exists in the index.
   */
  public boolean hasSegment(String segmentId) {
    return mSegmentIds.contains(segmentId);
  }

  /**
   * Decode a single segment by ID and cache it. Thread-safe.
   */
  private short[] loadSegmentById(String segmentId) {
    String assetPath = mSegmentsDir + "/" + segmentId + ".ogg";
    try {
      short[] pcm = loadOggAsPcm(mAssets, assetPath);
      if(pcm != null) {
        mSegments.put(segmentId, pcm);
      }
      return pcm;
    }
    catch(IOException e) {
      Log.w(TAG, "Failed to load segment: " + assetPath, e);
      return null;
    }
  }

  /**
   * Get a segment's PCM data, decoding on demand if not yet preloaded.
   */
  private short[] getSegment(String segmentId) {
    short[] pcm = mSegments.get(segmentId);
    if(pcm != null) return pcm;
    if(!mSegmentIds.contains(segmentId)) return null;
    return loadSegmentById(segmentId);
  }

  /**
   * Speak a sequence of segments concatenated with dynamic gap timing
   * and energy contour matching at join points.
   */
  public void speak(List<Segment> segments, String utteranceId) {
    mStopped.set(false);
    mExecutor.submit(() -> {
      if(mStopped.get()) return;
      if(mCallback != null) mCallback.onStart(utteranceId);

      try {
        short[] audio = concatenateSegments(segments);
        if(audio == null || audio.length == 0) {
          // No audio produced — all segments were missing.  Fire onDone
          // (not onError) so the announcement callback chain continues
          // normally and doesn't get stuck.
          Log.w(TAG, "No audio produced for segments: " + segments);
          if(mCallback != null) mCallback.onDone(utteranceId);
          return;
        }
        if(mStopped.get()) {
          if(mCallback != null) mCallback.onStop(utteranceId, true);
          return;
        }

        playPcm(audio);

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

  public void stop() {
    Log.i(TAG, "stop");
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
    Log.i(TAG, "shutdown");
    stop();
    mExecutor.shutdownNow();
    mSegments.clear();
    mReady = false;
  }

  /**
   * Concatenate segments with silence gaps between them. No trimming,
   * no per-segment fading, no energy matching — just straight joins
   * with the gap durations specified by each segment.
   */
  private short[] concatenateSegments(List<Segment> segments) {
    short[][] parts = new short[segments.size()][];
    int[] gapSamples = new int[segments.size()];
    int validCount = 0;

    for(int i = 0; i < segments.size(); i++) {
      Segment seg = segments.get(i);
      short[] pcm = getSegment(seg.id);
      if(pcm == null) {
        Log.w(TAG, "Missing segment: " + seg.id);
        continue;
      }
      parts[validCount] = pcm;
      gapSamples[validCount] = validCount == 0 ? 0 : mSampleRate * seg.gapMs / 1000;
      validCount++;
    }

    if(validCount == 0) return null;

    // Calculate total length
    int outputLength = 0;
    for(int i = 0; i < validCount; i++) {
      outputLength += parts[i].length;
      if(i > 0) outputLength += gapSamples[i];
    }

    short[] output = new short[outputLength];
    int writePos = 0;
    int junctionFade = mSampleRate * JUNCTION_FADE_MS / 1000;

    for(int i = 0; i < validCount; i++) {
      if(i > 0) writePos += gapSamples[i];
      System.arraycopy(parts[i], 0, output, writePos, parts[i].length);

      // Fade out the last few ms of each segment (before the gap) and
      // fade in the first few ms (after the gap) to eliminate clicks
      // from waveform discontinuities at junctions
      if(i < validCount - 1) {
        int fadeLen = Math.min(junctionFade, parts[i].length);
        int fadeStart = writePos + parts[i].length - fadeLen;
        for(int j = 0; j < fadeLen; j++) {
          float gain = 1.0f - (float) j / fadeLen;
          output[fadeStart + j] = (short) Math.round(output[fadeStart + j] * gain);
        }
      }
      if(i > 0) {
        int fadeLen = Math.min(junctionFade, parts[i].length);
        for(int j = 0; j < fadeLen; j++) {
          float gain = (float) j / fadeLen;
          output[writePos + j] = (short) Math.round(output[writePos + j] * gain);
        }
      }

      writePos += parts[i].length;
    }

    return applyEnvelope(normalize(output));
  }

  /**
   * Add lead/trail silence and a gentle fade-out. The lead silence
   * gives the AudioTrack time to start cleanly before speech begins.
   */
  private short[] applyEnvelope(short[] samples) {
    int leadSamples = mSampleRate * LEAD_SILENCE_MS / 1000;
    int trailSamples = mSampleRate * TRAIL_SILENCE_MS / 1000;
    int fadeOutSamples = mSampleRate * FADE_OUT_MS / 1000;

    short[] out = new short[leadSamples + samples.length + trailSamples];
    System.arraycopy(samples, 0, out, leadSamples, samples.length);

    // Gentle fade-out only (no fade-in — let the segments play as-is)
    int fadeOutStart = Math.max(0, samples.length - fadeOutSamples);
    for(int i = fadeOutStart; i < samples.length; i++) {
      float gain = (float) (samples.length - i) / fadeOutSamples;
      out[leadSamples + i] = (short) Math.round(out[leadSamples + i] * gain);
    }

    return out;
  }

  private short[] normalize(short[] samples) {
    short peak = 0;
    for(short s : samples) {
      short abs = (short) Math.abs(s);
      if(abs > peak) peak = abs;
    }
    if(peak < 100) return samples;

    float gain = (TARGET_PEAK * 32767) / peak;
    short[] result = new short[samples.length];
    for(int i = 0; i < samples.length; i++) {
      int val = Math.round(samples[i] * gain);
      result[i] = (short) Math.max(-32768, Math.min(32767, val));
    }
    return result;
  }

  private void playPcm(short[] samples) {
    // Adjust playback sample rate for speed/pitch control.
    // Higher rate = faster + higher pitch, lower = slower + lower pitch.
    int playbackRate = Math.round(mSampleRate * mSpeed);

    int bufferSize = samples.length * 2; // 16-bit = 2 bytes per sample
    AudioAttributes attrs = new AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
      .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
      .build();
    AudioFormat format = new AudioFormat.Builder()
      .setSampleRate(playbackRate)
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

    // Wait for the AudioTrack to actually start playing before
    // counting duration, to avoid premature completion
    for(int i = 0; i < 20 && track.getPlaybackHeadPosition() == 0; i++) {
      try { Thread.sleep(5); }
      catch(InterruptedException e) { break; }
    }

    // Block until playback completes
    long durationMs = (long) samples.length * 1000 / playbackRate;
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

  /**
   * Decode an OGG Vorbis file from assets into a PCM short array using
   * Android's MediaExtractor + MediaCodec pipeline.
   */
  private short[] loadOggAsPcm(AssetManager assets, String path) throws IOException {
    AssetFileDescriptor afd = assets.openFd(path);
    MediaExtractor extractor = new MediaExtractor();
    try {
      extractor.setDataSource(
          afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
    } finally {
      afd.close();
    }

    if(extractor.getTrackCount() == 0) {
      Log.w(TAG, "No tracks in: " + path);
      extractor.release();
      return null;
    }

    extractor.selectTrack(0);
    MediaFormat format = extractor.getTrackFormat(0);
    String mime = format.getString(MediaFormat.KEY_MIME);
    if(mime == null) {
      Log.w(TAG, "No MIME type in: " + path);
      extractor.release();
      return null;
    }

    // Store sample rate from first file loaded
    int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
    if(mSegments.isEmpty()) {
      mSampleRate = sampleRate;
    }

    MediaCodec codec;
    try {
      codec = MediaCodec.createDecoderByType(mime);
      codec.configure(format, null, null, 0);
      codec.start();
    } catch(Exception e) {
      Log.w(TAG, "Failed to create decoder for: " + path, e);
      extractor.release();
      return null;
    }

    ByteArrayOutputStream pcmOut = new ByteArrayOutputStream();
    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    boolean inputDone = false;
    boolean outputDone = false;

    while(!outputDone) {
      // Feed input buffers
      if(!inputDone) {
        int inIdx = codec.dequeueInputBuffer(5000);
        if(inIdx >= 0) {
          ByteBuffer inBuf = codec.getInputBuffer(inIdx);
          int size = extractor.readSampleData(inBuf, 0);
          if(size < 0) {
            codec.queueInputBuffer(inIdx, 0, 0, 0,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            inputDone = true;
          } else {
            codec.queueInputBuffer(inIdx, 0, size,
                extractor.getSampleTime(), 0);
            extractor.advance();
          }
        }
      }

      // Drain output buffers
      int outIdx = codec.dequeueOutputBuffer(info, 5000);
      if(outIdx >= 0) {
        if(info.size > 0) {
          ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
          byte[] chunk = new byte[info.size];
          outBuf.get(chunk);
          pcmOut.write(chunk);
        }
        outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
        codec.releaseOutputBuffer(outIdx, false);
      } else if(outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
        // Update sample rate in case the codec reports a different one
        MediaFormat outFmt = codec.getOutputFormat();
        if(outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
          sampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
          if(mSegments.isEmpty()) {
            mSampleRate = sampleRate;
          }
        }
      }
    }

    codec.stop();
    codec.release();
    extractor.release();

    byte[] pcmBytes = pcmOut.toByteArray();
    if(pcmBytes.length < 2) {
      Log.w(TAG, "Empty decode result: " + path);
      return null;
    }

    short[] samples = new short[pcmBytes.length / 2];
    ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        .asShortBuffer().get(samples);
    return samples;
  }
}
