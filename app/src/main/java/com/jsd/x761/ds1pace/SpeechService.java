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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import com.jsd.x761.ds1pace.R;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A service that handles speech announcements and audio focus.
 * Supports two voice backends:
 *   - System TTS (Android built-in British English voice)
 *   - Pre-recorded segments (Voxtral-generated, concatenated at runtime)
 */
public class SpeechService extends Service {
  private static final String TAG = "SPEECH_SERVICE";
  public static final String MESSAGE_TOKEN = "SPEECH_SERVICE_MESSAGES";

  private final Handler mHandler = new Handler(Looper.getMainLooper());
  private boolean mReady = false;
  private final List<Runnable> mReadyCallbacks = new ArrayList<>();
  private final IBinder mBinder;
  private AudioManager mAudioManager;
  private AudioFocusRequest mAudioFocusRequest;
  private int mDuckedAudioMedia = 0;
  private List<Voice> mUKVoices = new ArrayList<>();
  private int mVoiceIndex = -1;
  protected TextToSpeech mTextToSpeech;
  protected Map<String, Runnable> mTextToSpeechCallback = new ConcurrentHashMap<>();
  private SoundPool mSoundPool;
  private final Map<String, Integer> mEarconSoundIds = new HashMap<>();
  private PreRecordedTtsEngine mPreRecordedEngine;
  private TunedTtsEngine mTunedLocalEngine;
  private int mVoiceMode = Configuration.VOICE_MODE_SYSTEM;
  private boolean mUsePreRecorded = false;
  private volatile boolean mSystemTtsReady = false;
  private volatile boolean mPreRecordedInitDone = false;
  private volatile boolean mTunedLocalInitDone = false;
  final Queue<Runnable> mSpeechQueue = new LinkedList<>();
  boolean mSpeaking = false;

  public class ThisBinder extends Binder {
    public ThisBinder() {
    }

    public SpeechService getService() {
      return SpeechService.this;
    }
  }

  public SpeechService() {
    mBinder = new ThisBinder();
  }

  @Override
  public void onCreate() {
    Log.i(TAG, "onCreate");
    super.onCreate();

    mAudioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);

    // Initialize the text to speech engine
    mTextToSpeech = new TextToSpeech(getApplicationContext(), status -> {
      if(status != TextToSpeech.ERROR) {
        mTextToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {

          @Override
          public void onStart(String s) {
            Log.i(TAG, "UtteranceProgressListener.onStart");
            for(int i = 0; i < Configuration.AUDIO_ADJUST_RAISE_COUNT; i++) {
              mAudioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0);
            }
          }

          @Override
          public void onDone(String s) {
            Log.i(TAG, "UtteranceProgressListener.onDone");
            for(int i = 0; i < Configuration.AUDIO_ADJUST_RAISE_COUNT; i++) {
              // Another option is AudioManager.STREAM_VOICE_CALL
              mAudioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0);
            }
            Runnable callback = mTextToSpeechCallback.get(s);
            if(callback != null) {
              Log.i(TAG, "onPlaySpeech.onDone.run()");
              callback.run();
            }
          }

          @Override
          public void onError(String s) {
            Log.i(TAG, "UtteranceProgressListener.onError");
            for(int i = 0; i < Configuration.AUDIO_ADJUST_RAISE_COUNT; i++) {
              mAudioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0);
            }
            Runnable callback = mTextToSpeechCallback.get(s);
            if(callback != null) {
              Log.i(TAG, "onPlaySpeech.onError.run()");
              callback.run();
            }
          }

          @Override
          public void onStop(String s, boolean interrupted) {
            Log.i(TAG, String.format("UtteranceProgressListener.onStop interrupted=%b", interrupted));
            Runnable callback = mTextToSpeechCallback.get(s);
            if(callback != null) {
              Log.i(TAG, "onPlaySpeech.onStop.run()");
              callback.run();
            }
          }
        });

        // Pick a local offline voice similar to the voice used in the
        // builtin navigation system in a Jaguar F-Pace
        mUKVoices = new ArrayList<>();
        mTextToSpeech.setLanguage(Locale.UK);
        Set<Voice> voices = mTextToSpeech.getVoices();
        for(Voice voice : voices) {
          if(Locale.UK.equals(voice.getLocale()) && !voice.isNetworkConnectionRequired()) {
            mUKVoices.add(voice);
            Log.i(TAG, String.format("UK voice %d: %s", mUKVoices.size() - 1, voice.getName()));
            if(voice.getName().equals("en-gb-x-gbc-local")) {
              mVoiceIndex = mUKVoices.size() - 1;
            }
          }
        }
        if(mVoiceIndex == -1 && !mUKVoices.isEmpty()) {
          mVoiceIndex = 0;
        }
        if(mVoiceIndex >= 0 && mVoiceIndex < mUKVoices.size()) {
          mTextToSpeech.setVoice(mUKVoices.get(mVoiceIndex));
          Log.i(TAG, String.format("Using local voice: %s", mUKVoices.get(mVoiceIndex).getName()));
        }
        else {
          Log.w(TAG, "No UK voices available, using default voice");
        }
        mTextToSpeech.setPitch(Configuration.AUDIO_SPEECH_PITCH);
        mTextToSpeech.setSpeechRate(Configuration.AUDIO_SPEECH_RATE);

        // Set audio attributes on the TTS engine to use navigation guidance
        // so that the actual audio output triggers ducking of media playback
        AudioAttributes ttsAttributes = new AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
          .build();
        mTextToSpeech.setAudioAttributes(ttsAttributes);

        // Load the notification sounds used to announce the various
        // types of alerts into a SoundPool for reliable playback
        // independent of the TTS engine
        AudioAttributes earconAttributes = new AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .build();
        mSoundPool = new SoundPool.Builder()
          .setMaxStreams(2)
          .setAudioAttributes(earconAttributes)
          .build();
        mEarconSoundIds.put("[s1]", mSoundPool.load(getApplicationContext(), R.raw.s1, 1));
        mEarconSoundIds.put("[s2]", mSoundPool.load(getApplicationContext(), R.raw.s2, 1));
        mEarconSoundIds.put("[s3]", mSoundPool.load(getApplicationContext(), R.raw.s3, 1));
        mEarconSoundIds.put("[s4]", mSoundPool.load(getApplicationContext(), R.raw.s4, 1));
        mEarconSoundIds.put("[s5]", mSoundPool.load(getApplicationContext(), R.raw.s5, 1));
        mEarconSoundIds.put("[s6]", mSoundPool.load(getApplicationContext(), R.raw.s6, 1));
        mEarconSoundIds.put("[s7]", mSoundPool.load(getApplicationContext(), R.raw.s7, 1));
        mEarconSoundIds.put("[s8]", mSoundPool.load(getApplicationContext(), R.raw.s8, 1));
        mEarconSoundIds.put("[s9]", mSoundPool.load(getApplicationContext(), R.raw.s9, 1));
        mEarconSoundIds.put("[s10]", mSoundPool.load(getApplicationContext(), R.raw.s10, 1));

        // Read voice backend preference
        SharedPreferences prefs = getSharedPreferences(
          getString(R.string.preference_file_key), Context.MODE_PRIVATE);
        mUsePreRecorded = prefs.getBoolean(getString(R.string.key_voice_use_prerecorded), false);

        // Load voice mode (new int-based selection, falls back to boolean for compat)
        mVoiceMode = prefs.getInt(getString(R.string.key_voice_mode), -1);
        if(mVoiceMode == -1) {
          mVoiceMode = mUsePreRecorded ? Configuration.VOICE_MODE_PRERECORDED : Configuration.VOICE_MODE_SYSTEM;
        }
        Log.i(TAG, String.format("voiceMode=%d", mVoiceMode));

        // Initialize the pre-recorded TTS engine in the background
        new Thread(() -> {
          initPreRecordedEngine(segmentsDirForMode(mVoiceMode));
          mPreRecordedInitDone = true;
          checkAllEnginesReady();
        }).start();

        // Initialize the tuned TTS engines (local and network) in background
        TunedTtsEngine.Callback tunedCallback = new TunedTtsEngine.Callback() {
          @Override
          public void onStart(String utteranceId) {
            Log.i(TAG, "TunedTtsEngine.onStart");
            for(int i = 0; i < Configuration.AUDIO_ADJUST_RAISE_COUNT; i++) {
              mAudioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0);
            }
          }
          @Override
          public void onDone(String utteranceId) {
            Log.i(TAG, "TunedTtsEngine.onDone");
            for(int i = 0; i < Configuration.AUDIO_ADJUST_RAISE_COUNT; i++) {
              mAudioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0);
            }
            Runnable callback = mTextToSpeechCallback.get(utteranceId);
            if(callback != null) callback.run();
          }
          @Override
          public void onError(String utteranceId) {
            Log.i(TAG, "TunedTtsEngine.onError");
            for(int i = 0; i < Configuration.AUDIO_ADJUST_RAISE_COUNT; i++) {
              mAudioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0);
            }
            Runnable callback = mTextToSpeechCallback.get(utteranceId);
            if(callback != null) callback.run();
          }
          @Override
          public void onStop(String utteranceId, boolean interrupted) {
            Log.i(TAG, String.format("TunedTtsEngine.onStop interrupted=%b", interrupted));
            Runnable callback = mTextToSpeechCallback.get(utteranceId);
            if(callback != null) callback.run();
          }
        };

        new Thread(() -> {
          try {
            mTunedLocalEngine = new TunedTtsEngine(false);
            mTunedLocalEngine.setCallback(tunedCallback);
            loadTunedEngineParams(prefs, mTunedLocalEngine);
            mTunedLocalEngine.init(getApplicationContext());
            Log.i(TAG, "TunedTtsEngine (local) initialized");
          } catch(Throwable e) {
            Log.e(TAG, "TunedTtsEngine (local) init failed", e);
            mTunedLocalEngine = null;
          }
          mTunedLocalInitDone = true;
          checkAllEnginesReady();
        }).start();

        mSystemTtsReady = true;
        checkAllEnginesReady();
      }
    });
  }

  @Override
  public IBinder onBind(Intent intent) {
    return mBinder;
  }

  private static final String CHANNEL_ID = "DS1PaceLocationChannel";
  private static final int NOTIFICATION_ID = 1;

  public void startForegroundMode() {
    Log.i(TAG, "startForegroundMode");
    NotificationChannel channel = new NotificationChannel(
      CHANNEL_ID, "DS1Pace Location", NotificationManager.IMPORTANCE_LOW);
    channel.setDescription("Keeps location tracking active");
    NotificationManager nm = getSystemService(NotificationManager.class);
    nm.createNotificationChannel(channel);

    Intent notificationIntent = new Intent(this, AlertsActivity.class);
    notificationIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
      PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

    Notification notification = new Notification.Builder(this, CHANNEL_ID)
      .setContentTitle("DS1-Pace")
      .setContentText("Tracking location")
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentIntent(pendingIntent)
      .setOngoing(true)
      .build();

    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
  }

  public void stopForegroundMode() {
    Log.i(TAG, "stopForegroundMode");
    stopForeground(STOP_FOREGROUND_REMOVE);
  }

  public void isReady(Runnable onDone) {
    if(mReady) {
      Log.i(TAG, "isReady onDone()");
      onDone.run();
    }
    else {
      Log.i(TAG, "mReadyCallbacks.add() readyCallback");
      mReadyCallbacks.add(onDone);
    }
  }

  /**
   * Check if the system TTS and the configured voice engine are both ready.
   * Called from each engine init thread and from the system TTS callback.
   * Posts to the main handler to ensure thread safety.
   */
  private void checkAllEnginesReady() {
    mHandler.postDelayed(() -> {
      if(mReady) return;
      if(!mSystemTtsReady) return;

      // Wait for the configured voice engine to finish initializing
      // (whether it succeeds or fails) before declaring ready
      if(isPreRecordedMode() && !mPreRecordedInitDone) return;
      if(mVoiceMode == Configuration.VOICE_MODE_TUNED_LOCAL && !mTunedLocalInitDone) return;

      Log.i(TAG, "checkAllEnginesReady: all engines ready");
      mReady = true;
      for(Runnable readyCallback : mReadyCallbacks) {
        Log.i(TAG, "postDelayed() readyCallback");
        mHandler.postDelayed(readyCallback, MESSAGE_TOKEN, 1);
      }
    }, MESSAGE_TOKEN, 1);
  }

  @Override
  public void onDestroy() {
    Log.i(TAG, "onDestroy");
    super.onDestroy();

    mHandler.removeCallbacksAndMessages(MESSAGE_TOKEN);
    mSpeechQueue.clear();
    mSpeaking = false;

    // Cleanup SoundPool resources
    if(mSoundPool != null) {
      Log.i(TAG, "mSoundPool.release()");
      mSoundPool.release();
    }

    // Cleanup text to speech resources and abandon audio focus
    if(mTextToSpeech != null) {
      Log.i(TAG, "mTextToSpeech.shutdown()");
      mTextToSpeech.shutdown();
    }
    if(mPreRecordedEngine != null) {
      Log.i(TAG, "mPreRecordedEngine.shutdown()");
      mPreRecordedEngine.shutdown();
    }
    if(mTunedLocalEngine != null) {
      Log.i(TAG, "mTunedLocalEngine.shutdown()");
      mTunedLocalEngine.shutdown();
    }
    mDuckedAudioMedia = 0;
    abandonAudioFocus(() -> {
    });
  }

  // Delay after acquiring audio focus to let the audio hardware settle
  // before starting playback, preventing initial click/truncation artifacts
  private static final long AUDIO_FOCUS_SETTLE_MS = 200;

  public void requestAudioFocus(Runnable onDone) {
    Log.i(TAG, String.format("requestAudioFocus mDuckedAudioMedia %d", mDuckedAudioMedia));

    // Keep track of concurrent in-flight audio focus requests
    // Request audio focus with ducking for speech over a voice communication stream
    mDuckedAudioMedia++;
    if(mDuckedAudioMedia == 1) {
      AudioAttributes playbackAttributes =
        new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
      mAudioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(playbackAttributes)
        .setAcceptsDelayedFocusGain(false)
        .build();
      Log.i(TAG, "requestAudioFocus()");
      mAudioManager.requestAudioFocus(mAudioFocusRequest);
      // Delay playback to let the audio hardware settle after focus
      // acquisition, preventing initial click/truncation artifacts
      mHandler.postDelayed(onDone, MESSAGE_TOKEN, AUDIO_FOCUS_SETTLE_MS);
    }
    else {
      onDone.run();
    }
  }

  public void abandonAudioFocus(Runnable onDone) {
    Log.i(TAG, String.format("abandonAudioFocus mDuckedAudioMedia %d", mDuckedAudioMedia));
    // Keep track of concurrent in-flight audio focus requests
    // Abandon audio focus once all audio focus requests are cleared
    if(mDuckedAudioMedia > 0) {
      mDuckedAudioMedia--;
    }
    if(mDuckedAudioMedia == 0 && mAudioFocusRequest != null) {
      Log.i(TAG, "abandonAudioFocus()");
      mAudioManager.abandonAudioFocusRequest(mAudioFocusRequest);
      onDone.run();
    }
    else {
      onDone.run();
    }
  }

  public void addOnUtteranceProgressCallback(String key, Runnable runnable) {
    mTextToSpeechCallback.put(key, runnable);
  }

  public void removeOnUtteranceProgressCallback(String key) {
    mTextToSpeechCallback.remove(key);
  }

  public void playEarcon(String earcon) {
    playEarcon(earcon, null);
  }

  public void playEarcon(String earcon, Runnable onDone) {
    // Play earcon via SoundPool for reliable playback independent of the
    // TTS engine's audio session lifecycle
    Integer soundId = mEarconSoundIds.get(earcon);
    Log.i(TAG, String.format("mSoundPool.play() earcon %s soundId %s", earcon, soundId));
    if(soundId != null) {
      mSoundPool.play(soundId, 1.0f, 1.0f, 1, 0, 1.0f);
    }
    if(onDone != null) {
      onDone.run();
    }
  }

  /**
   * Play a text string via the appropriate TTS engine based on voice mode.
   */
  public void playSpeech(String speech, String uuid) {
    TunedTtsEngine tunedEngine = getActiveTunedEngine();
    if(tunedEngine != null) {
      Log.i(TAG, String.format("tunedEngine.speak() uuid %s speech %s", uuid, speech));
      tunedEngine.speak(speech, uuid);
    }
    else {
      Log.i(TAG, String.format("mTextToSpeech.speak() uuid %s speech %s", uuid, speech));
      mTextToSpeech.speak(speech, TextToSpeech.QUEUE_ADD, null, uuid);
    }
  }

  /**
   * Play a list of pre-recorded segments via the concatenative engine.
   */
  public void playSegments(List<Segment> segments, String uuid) {
    if(mPreRecordedEngine != null && mPreRecordedEngine.isReady()) {
      Log.i(TAG, String.format("mPreRecordedEngine.speak() uuid %s segments %s", uuid, segments));
      mPreRecordedEngine.speak(segments, uuid);
    }
    else {
      Log.w(TAG, "PreRecordedTtsEngine not ready, falling back to system TTS");
      // Fallback: join segment IDs as rough text (won't sound great but works)
      StringBuilder fallback = new StringBuilder();
      for(Segment seg : segments) {
        if(fallback.length() > 0) fallback.append(" ");
        fallback.append(seg.id);
      }
      mTextToSpeech.speak(fallback.toString(), TextToSpeech.QUEUE_ADD, null, uuid);
    }
  }

  private boolean isPreRecordedMode() {
    return mVoiceMode == Configuration.VOICE_MODE_PRERECORDED
        || mVoiceMode == Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS
        || mVoiceMode == Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS_075
        || mVoiceMode == Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS_090
        || mVoiceMode == Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS_100
        || mVoiceMode == Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS_110
        || mVoiceMode == Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS_120
        || mVoiceMode == Configuration.VOICE_MODE_PRERECORDED_VOXTRAL_110
        || mVoiceMode == Configuration.VOICE_MODE_PRERECORDED_VOXTRAL_120;
  }

  public boolean usePreRecorded() {
    return isPreRecordedMode()
      && mPreRecordedEngine != null && mPreRecordedEngine.isReady();
  }

  public void setPitch(float pitch) {
    Log.i(TAG, String.format("setPitch %.2f", pitch));
    mTextToSpeech.setPitch(pitch);
    announceEvent(String.format("Pitch %.2f. KA band alert ahead, speed trap on highway 101", pitch), () -> {});
  }

  public void setRate(float rate) {
    Log.i(TAG, String.format("setRate %.2f", rate));
    mTextToSpeech.setSpeechRate(rate);
    if(usePreRecorded()) {
      announceSegments(java.util.List.of(new Segment("radar_ka_34_7")), () -> {});
    }
    else {
      announceEvent(String.format("Rate %.2f. KA band alert ahead, speed trap on highway 101", rate), () -> {});
    }
  }

  public void setVoice(int index) {
    if(mUKVoices.isEmpty()) return;
    mVoiceIndex = Math.max(0, Math.min(index, mUKVoices.size() - 1));
    Voice voice = mUKVoices.get(mVoiceIndex);
    Log.i(TAG, String.format("setVoice %d %s", mVoiceIndex, voice.getName()));
    mTextToSpeech.setVoice(voice);
    mTextToSpeech.setPitch(Configuration.AUDIO_SPEECH_PITCH);
    mTextToSpeech.setSpeechRate(Configuration.AUDIO_SPEECH_RATE);
    announceEvent(String.format("Voice %d. KA band alert ahead, speed trap on highway 101", mVoiceIndex), () -> {});
  }

  public void listVoices() {
    Log.i(TAG, String.format("listVoices count %d", mUKVoices.size()));
    for(int i = 0; i < mUKVoices.size(); i++) {
      Voice voice = mUKVoices.get(i);
      Log.i(TAG, String.format("VOICE_LIST %d %s %s quality=%d latency=%d features=%s",
        i, voice.getName(), voice.getLocale(), voice.getQuality(), voice.getLatency(),
        voice.getFeatures()));
    }
  }

  public void synthesizeToFile(String text, String filename) {
    Log.i(TAG, String.format("synthesizeToFile text=%s filename=%s", text, filename));
    isReady(() -> {
      File outputFile = new File(getExternalFilesDir(null), filename);
      String uuid = UUID.randomUUID().toString();
      addOnUtteranceProgressCallback(uuid, () -> {
        removeOnUtteranceProgressCallback(uuid);
        Log.i(TAG, String.format("SYNTHESIZE_DONE %s %s", filename, outputFile.getAbsolutePath()));
      });
      int result = mTextToSpeech.synthesizeToFile(text, null, outputFile, uuid);
      Log.i(TAG, String.format("synthesizeToFile result=%d path=%s", result, outputFile.getAbsolutePath()));
    });
  }

  public void stopSpeech() {
    Log.i(TAG, "stopSpeech");
    mTextToSpeech.stop();
    if(mPreRecordedEngine != null) {
      mPreRecordedEngine.stop();
    }
    if(mTunedLocalEngine != null) {
      mTunedLocalEngine.stop();
    }
    mTextToSpeechCallback.clear();
    mSpeechQueue.clear();
    mSpeaking = false;
    // Force-abandon audio focus since the callbacks that would have
    // done so were cleared and will never fire
    if(mDuckedAudioMedia > 0 && mAudioFocusRequest != null) {
      Log.i(TAG, String.format("stopSpeech abandonAudioFocus() mDuckedAudioMedia %d", mDuckedAudioMedia));
      mDuckedAudioMedia = 0;
      mAudioManager.abandonAudioFocusRequest(mAudioFocusRequest);
    }
  }

  /**
   * Enqueue a speech action. Only one announcement plays at a time;
   * subsequent calls are queued and played in order.
   */
  void enqueueSpeech(Runnable speechAction) {
    mSpeechQueue.add(speechAction);
    if(!mSpeaking) {
      // Acquire audio focus once for the entire queue
      mSpeaking = true;
      requestAudioFocus(this::processNextSpeech);
    }
  }

  /**
   * Dequeue and start the next speech action, or release focus and
   * mark as idle.
   */
  void processNextSpeech() {
    Runnable next = mSpeechQueue.poll();
    if(next != null) {
      next.run();
    }
    else {
      mSpeaking = false;
      abandonAudioFocus(() -> {});
    }
  }

  // Pause between queued announcements to prevent audio clicks from
  // back-to-back playback. Audio focus is held across the entire queue.
  private static final long INTER_ANNOUNCEMENT_PAUSE_MS = 350;

  public void announceEvent(String event, Runnable onDone) {
    Log.i(TAG, String.format("announceEvent %s", event));

    // Wait for TTS to be ready before speaking to avoid silently dropped
    // announcements during startup, then queue to prevent overlapping
    // announcements from interrupting each other
    isReady(() -> enqueueSpeech(() -> {
      String uuid = UUID.randomUUID().toString();
      addOnUtteranceProgressCallback(uuid, () -> {
        Log.i(TAG, String.format("UtteranceProgressListener.onDone %s", uuid));
        removeOnUtteranceProgressCallback(uuid);
        onDone.run();
        // Pause then advance to next item or release focus
        mHandler.postDelayed(this::processNextSpeech, MESSAGE_TOKEN, INTER_ANNOUNCEMENT_PAUSE_MS);
      });
      playSpeech(event, uuid);
    }));
  }

  /**
   * Announce an event using pre-recorded segments.
   */
  public void announceSegments(List<Segment> segments, Runnable onDone) {
    Log.i(TAG, String.format("announceSegments %s", segments));

    isReady(() -> enqueueSpeech(() -> {
      String uuid = UUID.randomUUID().toString();
      addOnUtteranceProgressCallback(uuid, () -> {
        Log.i(TAG, String.format("PreRecordedTtsEngine.onDone %s", uuid));
        removeOnUtteranceProgressCallback(uuid);
        onDone.run();
        mHandler.postDelayed(this::processNextSpeech, MESSAGE_TOKEN, INTER_ANNOUNCEMENT_PAUSE_MS);
      });
      playSegments(segments, uuid);
    }));
  }

  public void setUsePreRecorded(boolean usePreRecorded) {
    Log.i(TAG, String.format("setUsePreRecorded %b", usePreRecorded));
    mUsePreRecorded = usePreRecorded;
    SharedPreferences.Editor editor = getSharedPreferences(
      getString(R.string.preference_file_key), Context.MODE_PRIVATE).edit();
    editor.putBoolean(getString(R.string.key_voice_use_prerecorded), usePreRecorded);
    editor.apply();
  }

  public boolean getUsePreRecorded() {
    return mUsePreRecorded;
  }

  public boolean isPreRecordedReady() {
    return mPreRecordedEngine != null && mPreRecordedEngine.isReady();
  }

  /**
   * Check if a specific pre-recorded segment is available.
   */
  public boolean hasSegment(String segmentId) {
    return mPreRecordedEngine != null && mPreRecordedEngine.hasSegment(segmentId);
  }

  public PreRecordedTtsEngine getPreRecordedEngine() {
    return mPreRecordedEngine;
  }

  private PreRecordedTtsEngine.Callback mPreRecordedCallback = new PreRecordedTtsEngine.Callback() {
    @Override
    public void onStart(String utteranceId) {
      Log.i(TAG, "PreRecordedTtsEngine.onStart");
      for(int i = 0; i < Configuration.AUDIO_ADJUST_RAISE_COUNT; i++) {
        mAudioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0);
      }
    }
    @Override
    public void onDone(String utteranceId) {
      Log.i(TAG, "PreRecordedTtsEngine.onDone");
      for(int i = 0; i < Configuration.AUDIO_ADJUST_RAISE_COUNT; i++) {
        mAudioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0);
      }
      Runnable callback = mTextToSpeechCallback.get(utteranceId);
      if(callback != null) callback.run();
    }
    @Override
    public void onError(String utteranceId) {
      Log.i(TAG, "PreRecordedTtsEngine.onError");
      for(int i = 0; i < Configuration.AUDIO_ADJUST_RAISE_COUNT; i++) {
        mAudioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0);
      }
      Runnable callback = mTextToSpeechCallback.get(utteranceId);
      if(callback != null) callback.run();
    }
    @Override
    public void onStop(String utteranceId, boolean interrupted) {
      Log.i(TAG, String.format("PreRecordedTtsEngine.onStop interrupted=%b", interrupted));
      Runnable callback = mTextToSpeechCallback.get(utteranceId);
      if(callback != null) callback.run();
    }
  };

  private void initPreRecordedEngine(String segmentsDir) {
    try {
      mPreRecordedEngine = new PreRecordedTtsEngine();
      mPreRecordedEngine.setCallback(mPreRecordedCallback);
      mPreRecordedEngine.init(getApplicationContext(), segmentsDir);
      Log.i(TAG, "PreRecordedTtsEngine initialized: " + segmentsDir);
    } catch(Throwable e) {
      Log.e(TAG, "PreRecordedTtsEngine init failed", e);
      mPreRecordedEngine = null;
    }
  }

  private void reinitPreRecordedEngine(String segmentsDir) {
    new Thread(() -> {
      if(mPreRecordedEngine != null) {
        mPreRecordedEngine.shutdown();
      }
      initPreRecordedEngine(segmentsDir);
    }).start();
  }

  // --- Voice mode (4 backends) ---

  /**
   * Returns the active tuned TTS engine for the current voice mode, or null
   * if the current mode does not use a tuned engine.
   */
  private TunedTtsEngine getActiveTunedEngine() {
    if(mVoiceMode == Configuration.VOICE_MODE_TUNED_LOCAL
        && mTunedLocalEngine != null && mTunedLocalEngine.isReady()) {
      return mTunedLocalEngine;
    }
    return null;
  }

  private static String segmentsDirForMode(int mode) {
    switch(mode) {
      case Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS_120:
        return "voice_segments_elevenlabs_120";
      case Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS_110:
        return "voice_segments_elevenlabs_110";
      case Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS_100:
        return "voice_segments_elevenlabs_100";
      case Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS_090:
        return "voice_segments_elevenlabs_090";
      case Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS:
        return "voice_segments_elevenlabs_082";
      case Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS_075:
        return "voice_segments_elevenlabs_075";
      case Configuration.VOICE_MODE_PRERECORDED_VOXTRAL_110:
        return "voice_segments_voxtral_110";
      case Configuration.VOICE_MODE_PRERECORDED_VOXTRAL_120:
        return "voice_segments_voxtral_120";
      default:
        return "voice_segments_voxtral_100";
    }
  }

  public void setVoiceMode(int mode) {
    Log.i(TAG, String.format("setVoiceMode %d", mode));
    int oldMode = mVoiceMode;
    mVoiceMode = mode;
    // Keep legacy boolean in sync
    mUsePreRecorded = (mode == Configuration.VOICE_MODE_PRERECORDED
        || mode == Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS
        || mode == Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS_075
        || mode == Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS_090
        || mode == Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS_100
        || mode == Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS_110
        || mode == Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS_120
        || mode == Configuration.VOICE_MODE_PRERECORDED_VOXTRAL_110
        || mode == Configuration.VOICE_MODE_PRERECORDED_VOXTRAL_120);
    SharedPreferences.Editor editor = getSharedPreferences(
      getString(R.string.preference_file_key), Context.MODE_PRIVATE).edit();
    editor.putInt(getString(R.string.key_voice_mode), mode);
    editor.putBoolean(getString(R.string.key_voice_use_prerecorded), mUsePreRecorded);
    editor.apply();

    // Reinit the pre-recorded engine if switching between voice sets
    boolean oldIsPreRecorded = oldMode == Configuration.VOICE_MODE_PRERECORDED
        || oldMode == Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS
        || oldMode == Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS_075
        || oldMode == Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS_090
        || oldMode == Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS_100
        || oldMode == Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS_110
        || oldMode == Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS_120
        || oldMode == Configuration.VOICE_MODE_PRERECORDED_VOXTRAL_110
        || oldMode == Configuration.VOICE_MODE_PRERECORDED_VOXTRAL_120;
    boolean newIsPreRecorded = mUsePreRecorded;
    if(newIsPreRecorded && (!oldIsPreRecorded
        || !segmentsDirForMode(oldMode).equals(segmentsDirForMode(mode)))) {
      reinitPreRecordedEngine(segmentsDirForMode(mode));
    }
  }

  public int getVoiceMode() {
    return mVoiceMode;
  }

  public boolean isTunedLocalReady() {
    return mTunedLocalEngine != null && mTunedLocalEngine.isReady();
  }

  /**
   * Load tuned engine voice, pitch, rate, and filter parameters from prefs,
   * falling back to the optimized defaults from Configuration.
   */
  private void loadTunedEngineParams(SharedPreferences prefs, TunedTtsEngine engine) {
    String voiceName = prefs.getString(getString(R.string.key_tuned_local_voice), Configuration.TUNED_LOCAL_VOICE);
    float pitch = prefs.getFloat(getString(R.string.key_tuned_local_pitch), Configuration.TUNED_LOCAL_PITCH);
    float rate = prefs.getFloat(getString(R.string.key_tuned_local_rate), Configuration.TUNED_LOCAL_RATE);
    String filterStr = prefs.getString(getString(R.string.key_tuned_local_filter), Configuration.TUNED_LOCAL_FILTER);

    engine.setVoiceName(voiceName);
    engine.setPitch(pitch);
    engine.setRate(rate);
    parseFilterParams(engine.getProcessor(), filterStr);
  }

  /**
   * Set tuned engine filter parameters from a comma-separated string and
   * persist to SharedPreferences.
   *
   * Format: lowShelfGain,lowShelfFreq,peak1Gain,peak1Freq,peak1Q,
   *         peak2Gain,peak2Freq,peak2Q,highShelfGain,highShelfFreq,
   *         compThreshold,compRatio
   */
  public void setTunedFilterParams(boolean network, String filterStr) {
    Log.i(TAG, String.format("setTunedFilterParams params=%s", filterStr));
    if(mTunedLocalEngine != null) {
      parseFilterParams(mTunedLocalEngine.getProcessor(), filterStr);
    }

    SharedPreferences.Editor editor = getSharedPreferences(
      getString(R.string.preference_file_key), Context.MODE_PRIVATE).edit();
    editor.putString(getString(R.string.key_tuned_local_filter), filterStr);
    editor.apply();
  }

  /**
   * Set the voice name for a tuned engine and persist to SharedPreferences.
   */
  public void setTunedVoice(boolean network, String voiceName) {
    Log.i(TAG, String.format("setTunedVoice voice=%s", voiceName));
    if(mTunedLocalEngine != null) {
      mTunedLocalEngine.setVoiceName(voiceName);
    }

    SharedPreferences.Editor editor = getSharedPreferences(
      getString(R.string.preference_file_key), Context.MODE_PRIVATE).edit();
    editor.putString(getString(R.string.key_tuned_local_voice), voiceName);
    editor.apply();
  }

  /**
   * Set pitch for a tuned engine and persist.
   */
  public void setTunedPitch(boolean network, float pitch) {
    Log.i(TAG, String.format("setTunedPitch pitch=%.2f", pitch));
    if(mTunedLocalEngine != null) mTunedLocalEngine.setPitch(pitch);

    SharedPreferences.Editor editor = getSharedPreferences(
      getString(R.string.preference_file_key), Context.MODE_PRIVATE).edit();
    editor.putFloat(getString(R.string.key_tuned_local_pitch), pitch);
    editor.apply();
  }

  /**
   * Set rate for a tuned engine and persist.
   */
  public void setTunedRate(boolean network, float rate) {
    Log.i(TAG, String.format("setTunedRate rate=%.2f", rate));
    if(mTunedLocalEngine != null) mTunedLocalEngine.setRate(rate);

    SharedPreferences.Editor editor = getSharedPreferences(
      getString(R.string.preference_file_key), Context.MODE_PRIVATE).edit();
    editor.putFloat(getString(R.string.key_tuned_local_rate), rate);
    editor.apply();
  }

  private void parseFilterParams(AudioPostProcessor proc, String filterStr) {
    try {
      String[] parts = filterStr.split(",");
      if(parts.length >= 12) {
        proc.lowShelfGainDb = Float.parseFloat(parts[0].trim());
        proc.lowShelfFreqHz = Float.parseFloat(parts[1].trim());
        proc.peak1GainDb = Float.parseFloat(parts[2].trim());
        proc.peak1FreqHz = Float.parseFloat(parts[3].trim());
        proc.peak1Q = Float.parseFloat(parts[4].trim());
        proc.peak2GainDb = Float.parseFloat(parts[5].trim());
        proc.peak2FreqHz = Float.parseFloat(parts[6].trim());
        proc.peak2Q = Float.parseFloat(parts[7].trim());
        proc.highShelfGainDb = Float.parseFloat(parts[8].trim());
        proc.highShelfFreqHz = Float.parseFloat(parts[9].trim());
        proc.compressorThresholdDb = Float.parseFloat(parts[10].trim());
        proc.compressorRatio = Float.parseFloat(parts[11].trim());
      }
    }
    catch(NumberFormatException e) {
      Log.e(TAG, "Failed to parse filter params: " + filterStr, e);
    }
  }

  /**
   * List all English voices (local + network) for the optimizer.
   */
  /**
   * Silently set pitch on the main TTS (no test announcement).
   * Used by the optimizer to avoid queuing speech before synthesis.
   */
  public void setPitchSilent(float pitch) {
    Log.i(TAG, String.format("setPitchSilent %.2f", pitch));
    mTextToSpeech.setPitch(pitch);
  }

  /**
   * Silently set rate on the main TTS (no test announcement).
   */
  public void setRateSilent(float rate) {
    Log.i(TAG, String.format("setRateSilent %.2f", rate));
    mTextToSpeech.setSpeechRate(rate);
  }

  /**
   * Set voice on the main TTS by name (for the optimizer to test any voice).
   */
  public void setVoiceByName(String name) {
    Log.i(TAG, String.format("setVoiceByName %s", name));
    Set<Voice> voices = mTextToSpeech.getVoices();
    for(Voice v : voices) {
      if(v.getName().equals(name)) {
        mTextToSpeech.setVoice(v);
        Log.i(TAG, String.format("setVoiceByName: set %s", name));
        return;
      }
    }
    Log.w(TAG, "setVoiceByName: voice not found: " + name);
  }

  public void listAllVoices() {
    Log.i(TAG, "listAllVoices");
    Set<Voice> voices = mTextToSpeech.getVoices();
    int idx = 0;
    for(Voice voice : voices) {
      if("en".equals(voice.getLocale().getLanguage())) {
        Log.i(TAG, String.format("VOICE_LIST_ALL %d %s %s network=%b quality=%d latency=%d features=%s",
          idx, voice.getName(), voice.getLocale(), voice.isNetworkConnectionRequired(),
          voice.getQuality(), voice.getLatency(), voice.getFeatures()));
        idx++;
      }
    }
  }

  /**
   * Synthesize text to file using a tuned engine (with DSP filters applied).
   */
  public void synthesizeFilteredToFile(String text, String filename, boolean network) {
    Log.i(TAG, String.format("synthesizeFilteredToFile text=%s filename=%s", text, filename));
    if(mTunedLocalEngine != null && mTunedLocalEngine.isReady()) {
      File outputFile = new File(getExternalFilesDir(null), filename);
      mTunedLocalEngine.synthesizeToFile(text, outputFile, () -> {
        Log.i(TAG, String.format("SYNTHESIZE_FILTERED_DONE %s %s", filename, outputFile.getAbsolutePath()));
      });
    }
    else {
      Log.w(TAG, "Tuned engine not ready for synthesizeFilteredToFile");
    }
  }
}
