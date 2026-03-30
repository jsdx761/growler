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

package com.jsd.x761.growler;

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

import com.jsd.x761.growler.Growler.R;

import java.util.List;

/**
 * An activity that configures the voice / TTS backend preferences.
 */
public class VoiceSettingsActivity extends AppCompatActivity {
  private static final String TAG = "VOICE_SETTINGS_ACTIVITY";

  private SharedPreferences mSharedPreferences;
  private SpeechService mSpeechService;
  private ServiceConnection mSpeechServiceConnection;
  private final Handler mHandler = new Handler(Looper.getMainLooper());

  @Override
  protected void onCreate(Bundle b) {
    Log.i(TAG, "onCreate");
    super.onCreate(b);

    setTitle("Voice");

    setContentView(R.layout.voice_settings_activity);
    mSharedPreferences = getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE);

    RadioGroup backendGroup = findViewById(R.id.voiceBackendGroup);
    RadioButton systemRadio = findViewById(R.id.voiceBackendSystem);
    RadioButton preRecordedRadio = findViewById(R.id.voiceBackendPreRecorded);
    RadioButton preRecordedElevenLabsRadio = findViewById(R.id.voiceBackendPreRecordedElevenLabs);
    RadioButton preRecordedElevenLabs075Radio = findViewById(R.id.voiceBackendPreRecordedElevenLabs075);
    RadioButton tunedLocalRadio = findViewById(R.id.voiceBackendTunedLocal);
    LinearLayout slidersSection = findViewById(R.id.voiceSlidersSection);
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

    // Backend selection
    switch(voiceMode) {
      case Configuration.VOICE_MODE_PRERECORDED:
        preRecordedRadio.setChecked(true);
        break;
      case Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS:
        preRecordedElevenLabsRadio.setChecked(true);
        break;
      case Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS_075:
        preRecordedElevenLabs075Radio.setChecked(true);
        break;
      case Configuration.VOICE_MODE_TUNED_LOCAL:
        tunedLocalRadio.setChecked(true);
        break;
      default:
        systemRadio.setChecked(true);
        break;
    }

    backendGroup.setOnCheckedChangeListener((group, checkedId) -> {
      int mode;
      if(checkedId == R.id.voiceBackendPreRecorded) {
        mode = Configuration.VOICE_MODE_PRERECORDED;
      } else if(checkedId == R.id.voiceBackendPreRecordedElevenLabs) {
        mode = Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS;
      } else if(checkedId == R.id.voiceBackendPreRecordedElevenLabs075) {
        mode = Configuration.VOICE_MODE_PRERECORDED_ELEVENLABS_075;
      } else if(checkedId == R.id.voiceBackendTunedLocal) {
        mode = Configuration.VOICE_MODE_TUNED_LOCAL;
      } else {
        mode = Configuration.VOICE_MODE_SYSTEM;
      }
      Log.i(TAG, String.format("voice mode changed %d", mode));
      if(mSpeechService != null) {
        mSpeechService.setVoiceMode(mode);
      }
      updateSliderVisibility(mode, slidersSection);
    });

    updateSliderVisibility(voiceMode, slidersSection);

    // Speed slider (0.00 to 2.00, seek bar 0-200 maps to 0.00-2.00)
    int speedProgress = Math.round(speed * 100);
    speedSeekBar.setProgress(speedProgress);
    speedValue.setText(String.format("%.2fx", speed));

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
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putFloat(getString(R.string.key_voice_speed), rate);
        editor.apply();
        if(mSpeechService != null) {
          mSpeechService.setRateSilent(rate);
          if(mSpeechService.getPreRecordedEngine() != null) {
            mSpeechService.getPreRecordedEngine().setSpeed(rate);
          }
          mSpeechService.setTunedRate(false, rate);
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
          mSpeechService.announceEvent("K A band thirty four point seven Radar", () ->
            mSpeechService.announceEvent("Speed Trap on Highway, three o'clock, half a mile", () ->
              mSpeechService.announceEvent("Police Helicopter, nine o'clock, one mile", () -> {})));
        }
      }
    });

    // Bind to SpeechService to get engine status
    bindSpeechService();
  }

  /**
   * Show the rate/pitch sliders only for the built-in phone voice.
   */
  private void updateSliderVisibility(int mode, LinearLayout slidersSection) {
    boolean show = mode == Configuration.VOICE_MODE_SYSTEM;
    slidersSection.setVisibility(show ? View.VISIBLE : View.GONE);
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
