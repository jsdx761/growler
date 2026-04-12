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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;

import com.jsd.x761.ds1pace.R;

import java.util.List;

/**
 * An activity that configures the voice / TTS backend preferences.
 */
public class VoiceSettingsActivity extends AppCompatActivity {
  private static final String TAG = "VOICE_SETTINGS_ACTIVITY";

  // ElevenLabs voice modes mapped to slider positions 0-3
  private static final int[] ELEVENLABS_MODES = {
    Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS_090,  // 0 = 0.90x
    Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS_100,  // 1 = 1.00x
    Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS_110,  // 2 = 1.10x
    Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS_120,  // 3 = 1.20x
  };
  private static final String[] ELEVENLABS_LABELS = {"0.90x", "1.00x", "1.10x", "1.20x"};
  private static final int ELEVENLABS_DEFAULT = 1; // 1.00x

  // Voxtral voice modes mapped to slider positions 0-2
  private static final int[] VOXTRAL_MODES = {
    Configuration.VOICE_MODE_PRERECORDED,                 // 0 = 1.00x
    Configuration.VOICE_MODE_PRERECORDED_VOXTRAL_110,     // 1 = 1.10x
    Configuration.VOICE_MODE_PRERECORDED_VOXTRAL_120,     // 2 = 1.20x
  };
  private static final String[] VOXTRAL_LABELS = {"1.00x", "1.10x", "1.20x"};
  private static final int VOXTRAL_DEFAULT = 0; // 1.00x

  private SharedPreferences mSharedPreferences;
  private SpeechService mSpeechService;
  private ServiceConnection mSpeechServiceConnection;
  private final Handler mHandler = new Handler(Looper.getMainLooper());
  private int mCurrentVoiceMode;

  @Override
  protected void onCreate(Bundle b) {
    Log.i(TAG, "onCreate");
    super.onCreate(b);

    setContentView(R.layout.voice_settings_activity);
    MaterialToolbar toolbar = findViewById(R.id.settingsToolbar);
    toolbar.setTitle("Voice");
    toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
    mSharedPreferences = getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE);

    RadioGroup backendGroup = findViewById(R.id.voiceBackendGroup);
    RadioButton elevenLabsRadio = findViewById(R.id.voiceBackendElevenLabs);
    RadioButton voxtralRadio = findViewById(R.id.voiceBackendVoxtral);
    RadioButton systemRadio = findViewById(R.id.voiceBackendSystem);
    RadioButton tunedLocalRadio = findViewById(R.id.voiceBackendTunedLocal);
    LinearLayout carVoiceRateSection = findViewById(R.id.carVoiceRateSection);
    SeekBar carVoiceRateSeekBar = findViewById(R.id.carVoiceRateSeekBar);
    TextView carVoiceRateValue = findViewById(R.id.carVoiceRateValue);
    View elevenLabsHint = findViewById(R.id.voiceBackendElevenLabsHint);
    View voxtralHint = findViewById(R.id.voiceBackendVoxtralHint);
    View systemHint = findViewById(R.id.voiceBackendSystemHint);
    View tunedLocalHint = findViewById(R.id.voiceBackendTunedLocalHint);
    LinearLayout rateSection = findViewById(R.id.voiceRateSection);
    LinearLayout pitchSection = findViewById(R.id.voicePitchSection);
    SeekBar speedSeekBar = findViewById(R.id.voiceSpeedSeekBar);
    TextView speedValue = findViewById(R.id.voiceSpeedValue);
    SeekBar pitchSeekBar = findViewById(R.id.voicePitchSeekBar);
    TextView pitchValue = findViewById(R.id.voicePitchValue);
    Button testButton = findViewById(R.id.voiceTestButton);

    // Load current preferences
    int voiceMode = mSharedPreferences.getInt(getString(R.string.key_voice_mode), -1);
    if(voiceMode == -1) {
      boolean usePreRecorded = mSharedPreferences.getBoolean(getString(R.string.key_voice_use_prerecorded), false);
      voiceMode = usePreRecorded ? Configuration.VOICE_MODE_PRERECORDED : Configuration.VOICE_MODE_SYSTEM;
    }
    float speed = mSharedPreferences.getFloat(getString(R.string.key_voice_speed), Configuration.AUDIO_SPEECH_RATE);
    float pitch = mSharedPreferences.getFloat(getString(R.string.key_voice_pitch), Configuration.AUDIO_SPEECH_PITCH);

    // Set initial radio button and car voice rate slider state (before listeners)
    if(isElevenLabsMode(voiceMode)) {
      elevenLabsRadio.setChecked(true);
      int pos = elevenLabsSliderPosition(voiceMode);
      carVoiceRateSeekBar.setMax(ELEVENLABS_MODES.length - 1);
      carVoiceRateSeekBar.setProgress(pos);
      carVoiceRateValue.setText(ELEVENLABS_LABELS[pos]);
    } else if(isVoxtralMode(voiceMode)) {
      voxtralRadio.setChecked(true);
      int pos = voxtralSliderPosition(voiceMode);
      carVoiceRateSeekBar.setMax(VOXTRAL_MODES.length - 1);
      carVoiceRateSeekBar.setProgress(pos);
      carVoiceRateValue.setText(VOXTRAL_LABELS[pos]);
    } else if(voiceMode == Configuration.VOICE_MODE_TUNED_LOCAL) {
      tunedLocalRadio.setChecked(true);
    } else {
      systemRadio.setChecked(true);
    }

    mCurrentVoiceMode = voiceMode;
    updateSliderVisibility(voiceMode, backendGroup,
      elevenLabsHint, voxtralHint, systemHint, tunedLocalHint,
      carVoiceRateSection, rateSection, pitchSection);

    // Radio button change listener
    backendGroup.setOnCheckedChangeListener((group, checkedId) -> {
      int mode;
      if(checkedId == R.id.voiceBackendElevenLabs) {
        carVoiceRateSeekBar.setMax(ELEVENLABS_MODES.length - 1);
        carVoiceRateSeekBar.setProgress(ELEVENLABS_DEFAULT);
        carVoiceRateValue.setText(ELEVENLABS_LABELS[ELEVENLABS_DEFAULT]);
        mode = ELEVENLABS_MODES[ELEVENLABS_DEFAULT];
      } else if(checkedId == R.id.voiceBackendVoxtral) {
        carVoiceRateSeekBar.setMax(VOXTRAL_MODES.length - 1);
        carVoiceRateSeekBar.setProgress(VOXTRAL_DEFAULT);
        carVoiceRateValue.setText(VOXTRAL_LABELS[VOXTRAL_DEFAULT]);
        mode = VOXTRAL_MODES[VOXTRAL_DEFAULT];
      } else if(checkedId == R.id.voiceBackendTunedLocal) {
        mode = Configuration.VOICE_MODE_TUNED_LOCAL;
      } else {
        mode = Configuration.VOICE_MODE_SYSTEM;
      }
      mCurrentVoiceMode = mode;
      Log.i(TAG, String.format("voice mode changed %d", mode));
      if(mSpeechService != null) {
        mSpeechService.stopSpeech();
        mSpeechService.setVoiceMode(mode);
      }
      updateSliderVisibility(mode, backendGroup,
        elevenLabsHint, voxtralHint, systemHint, tunedLocalHint,
        carVoiceRateSection, rateSection, pitchSection);
      updateSpeedSlider(mode, speedSeekBar, speedValue);
    });

    // Car voice rate slider (discrete steps)
    carVoiceRateSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if(elevenLabsRadio.isChecked()) {
          carVoiceRateValue.setText(ELEVENLABS_LABELS[progress]);
        } else if(voxtralRadio.isChecked()) {
          carVoiceRateValue.setText(VOXTRAL_LABELS[progress]);
        }
      }

      @Override
      public void onStartTrackingTouch(SeekBar seekBar) {}

      @Override
      public void onStopTrackingTouch(SeekBar seekBar) {
        int progress = seekBar.getProgress();
        int mode;
        if(elevenLabsRadio.isChecked()) {
          mode = ELEVENLABS_MODES[progress];
        } else {
          mode = VOXTRAL_MODES[progress];
        }
        mCurrentVoiceMode = mode;
        Log.i(TAG, String.format("car voice rate changed, mode %d", mode));
        if(mSpeechService != null) {
          mSpeechService.stopSpeech();
          mSpeechService.setVoiceMode(mode);
        }
      }
    });

    // Speed slider (0.00 to 2.00, seek bar 0-200 maps to 0.00-2.00)
    float initialRate = (voiceMode == Configuration.VOICE_MODE_TUNED_LOCAL)
      ? mSharedPreferences.getFloat(getString(R.string.key_tuned_local_rate), Configuration.TUNED_LOCAL_RATE)
      : speed;
    int speedProgress = Math.round(initialRate * 100);
    speedSeekBar.setProgress(speedProgress);
    speedValue.setText(String.format("%.2fx", initialRate));

    speedSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        float rate = progress / 100.0f;
        speedValue.setText(String.format("%.2fx", rate));
      }

      @Override
      public void onStartTrackingTouch(SeekBar seekBar) {}

      @Override
      public void onStopTrackingTouch(SeekBar seekBar) {
        float rate = seekBar.getProgress() / 100.0f;
        Log.i(TAG, String.format("speed changed %.2f", rate));
        if(mCurrentVoiceMode == Configuration.VOICE_MODE_TUNED_LOCAL) {
          if(mSpeechService != null) {
            mSpeechService.setTunedRate(false, rate);
          }
        } else {
          SharedPreferences.Editor editor = mSharedPreferences.edit();
          editor.putFloat(getString(R.string.key_voice_speed), rate);
          editor.apply();
          if(mSpeechService != null) {
            mSpeechService.setRateSilent(rate);
          }
        }
      }
    });

    // Pitch slider (0.00 to 2.00, seek bar 0-200 maps to 0.00-2.00)
    int pitchProgress = Math.round(pitch * 100);
    pitchSeekBar.setProgress(pitchProgress);
    pitchValue.setText(String.format("%.2f", pitch));

    pitchSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override
      public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        float p = progress / 100.0f;
        pitchValue.setText(String.format("%.2f", p));
      }

      @Override
      public void onStartTrackingTouch(SeekBar seekBar) {}

      @Override
      public void onStopTrackingTouch(SeekBar seekBar) {
        float p = seekBar.getProgress() / 100.0f;
        Log.i(TAG, String.format("pitch changed %.2f", p));
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putFloat(getString(R.string.key_voice_pitch), p);
        editor.apply();
        if(mSpeechService != null) {
          mSpeechService.setPitchSilent(p);
        }
      }
    });

    // Test button — plays a realistic combination of radar, Waze, and aircraft alerts
    testButton.setOnClickListener(v -> {
      if(mSpeechService != null) {
        mSpeechService.stopSpeech();
        if(mSpeechService.usePreRecorded()) {
          // Radar KA 34.7, then Waze speed trap on highway, then police helicopter
          mSpeechService.announceSegments(
            List.of(new Segment("radar_ka_34_7")), () ->
            mSpeechService.announceSegments(
              List.of(new Segment("report_speed_trap_road_highway"),
                new Segment("bearing_3", Segment.GAP_CLAUSE),
                new Segment("dist_0_5", Segment.GAP_CLAUSE)), () ->
              mSpeechService.announceSegments(
                List.of(new Segment("aircraft_police_helicopter"),
                  new Segment("bearing_9", Segment.GAP_CLAUSE),
                  new Segment("dist_1_0", Segment.GAP_CLAUSE)), () -> {})));
        }
        else {
          mSpeechService.announceEvent("K A band 34.7 Radar", () ->
            mSpeechService.announceEvent("Speed Trap on Highway 101 at 3 o'clock 0.5 mile away", () ->
              mSpeechService.announceEvent("Police Helicopter at 9 o'clock 1 mile away", () -> {})));
        }
      }
    });

    // Bind to SpeechService to get engine status
    bindSpeechService();
  }

  private boolean isElevenLabsMode(int mode) {
    for(int m : ELEVENLABS_MODES) {
      if(m == mode) return true;
    }
    return false;
  }

  private boolean isVoxtralMode(int mode) {
    for(int m : VOXTRAL_MODES) {
      if(m == mode) return true;
    }
    return false;
  }

  private int elevenLabsSliderPosition(int mode) {
    for(int i = 0; i < ELEVENLABS_MODES.length; i++) {
      if(ELEVENLABS_MODES[i] == mode) return i;
    }
    return ELEVENLABS_DEFAULT;
  }

  private int voxtralSliderPosition(int mode) {
    for(int i = 0; i < VOXTRAL_MODES.length; i++) {
      if(VOXTRAL_MODES[i] == mode) return i;
    }
    return VOXTRAL_DEFAULT;
  }

  /**
   * Position and show/hide all slider sections below their respective voice option.
   */
  private void updateSliderVisibility(int mode, RadioGroup backendGroup,
      View elevenLabsHint, View voxtralHint, View systemHint, View tunedLocalHint,
      LinearLayout carVoiceRateSection, LinearLayout rateSection, LinearLayout pitchSection) {

    boolean showCarRate = isElevenLabsMode(mode) || isVoxtralMode(mode);
    boolean showRate = mode == Configuration.VOICE_MODE_SYSTEM || mode == Configuration.VOICE_MODE_TUNED_LOCAL;
    boolean showPitch = mode == Configuration.VOICE_MODE_SYSTEM;

    // Remove all slider sections from current positions
    backendGroup.removeView(carVoiceRateSection);
    backendGroup.removeView(rateSection);
    backendGroup.removeView(pitchSection);

    // Position car voice rate slider
    if(showCarRate) {
      View anchor = isElevenLabsMode(mode) ? elevenLabsHint : voxtralHint;
      int insertAt = backendGroup.indexOfChild(anchor) + 1;
      backendGroup.addView(carVoiceRateSection, insertAt);
      carVoiceRateSection.setVisibility(View.VISIBLE);
    } else {
      backendGroup.addView(carVoiceRateSection);
      carVoiceRateSection.setVisibility(View.GONE);
    }

    // Position system rate/pitch sliders
    if(showRate) {
      View anchor = (mode == Configuration.VOICE_MODE_SYSTEM) ? systemHint : tunedLocalHint;
      int insertAt = backendGroup.indexOfChild(anchor) + 1;
      backendGroup.addView(rateSection, insertAt);
      rateSection.setVisibility(View.VISIBLE);
      if(showPitch) {
        backendGroup.addView(pitchSection, insertAt + 1);
        pitchSection.setVisibility(View.VISIBLE);
      } else {
        backendGroup.addView(pitchSection);
        pitchSection.setVisibility(View.GONE);
      }
    } else {
      backendGroup.addView(rateSection);
      backendGroup.addView(pitchSection);
      rateSection.setVisibility(View.GONE);
      pitchSection.setVisibility(View.GONE);
    }
  }

  /**
   * Update the speed slider value when switching voice modes.
   */
  private void updateSpeedSlider(int mode, SeekBar speedSeekBar, TextView speedValue) {
    float rate = (mode == Configuration.VOICE_MODE_TUNED_LOCAL)
      ? mSharedPreferences.getFloat(getString(R.string.key_tuned_local_rate), Configuration.TUNED_LOCAL_RATE)
      : mSharedPreferences.getFloat(getString(R.string.key_voice_speed), Configuration.AUDIO_SPEECH_RATE);
    int progress = Math.round(rate * 100);
    speedSeekBar.setProgress(progress);
    speedValue.setText(String.format("%.2fx", rate));
  }

  private void bindSpeechService() {
    mSpeechServiceConnection = new ServiceConnection() {
      @Override
      public void onServiceConnected(ComponentName name, IBinder s) {
        Log.i(TAG, "onServiceConnected");
        mSpeechService = ((SpeechService.ThisBinder) s).getService();
        updateEngineStatus();
      }

      @Override
      public void onServiceDisconnected(ComponentName name) {
        Log.i(TAG, "onServiceDisconnected");
        mSpeechService = null;
      }
    };
    Intent intent = new Intent(this, SpeechService.class);
    bindService(intent, mSpeechServiceConnection, BIND_AUTO_CREATE);
  }

  private void updateEngineStatus() {
    if(mSpeechService == null) return;

    // Poll until all engines ready
    boolean allReady = mSpeechService.isPreRecordedReady()
      && mSpeechService.isTunedLocalReady();
    if(!allReady) {
      mHandler.postDelayed(this::updateEngineStatus, 1000);
    }
  }

  @Override
  protected void onDestroy() {
    Log.i(TAG, "onDestroy");
    super.onDestroy();
    if(mSpeechServiceConnection != null) {
      unbindService(mSpeechServiceConnection);
    }
  }
}
