/*
 * Copyright (c) 2021 NoLimits Enterprises brock@radenso.com
 *
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

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import com.jsd.x761.growler.Growler.R;

/**
 * An activity that configures the DS1 device volume.
 */
public class DS1VolumeActivity extends DS1ServiceActivity {
  private static final String TAG = "DS1_VOLUME_ACTIVITY";
  public static final String MESSAGE_TOKEN = "DS1_VOLUME_ACTIVITY_MESSAGES";

  private final Handler mHandler = new Handler(Looper.getMainLooper());
  private SeekBar mDS1VolumeSeekBar;
  private Button mDS1VolumePlus;
  private Button mDS1VolumeMinus;
  private TextView mDS1VolumeText;

  @Override
  protected void onCreate(Bundle b) {
    Log.i(TAG, "onCreate");
    super.onCreate(b);

    setTitle("DS1 Volume");

    setContentView(R.layout.ds1_volume_activity);
    mDS1ConnectedImage = findViewById(R.id.ds1ConnectedImage);
    mDS1VolumeSeekBar = findViewById(R.id.ds1VolumeSeekBar);
    mDS1VolumeMinus = findViewById(R.id.ds1VolumeMinus);
    mDS1VolumePlus = findViewById(R.id.ds1VolumePlus);
    mDS1VolumeText = findViewById(R.id.ds1VolumeText);
    mDS1VolumeSeekBar.setEnabled(false);
    mDS1VolumeMinus.setEnabled(false);
    mDS1VolumePlus.setEnabled(false);

    // Bind to the DS1 service
    bindDS1Service(() -> {
      Log.i(TAG, "bindDS1Service.onDone");
    });

    mDS1VolumeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {

      @Override
      public void onProgressChanged(
        SeekBar seekBar, int progress, boolean fromUser) {
        if(fromUser) {
          // Set the volume on the DS1 device
          if(mDS1Service != null && mDS1Service.isConnected()) {
            mDS1Service.setVolume(progress);
          }
          mDS1VolumeText.setText(String.valueOf(progress));
        }
      }

      @Override
      public void onStartTrackingTouch(SeekBar seekBar) {
      }

      @Override
      public void onStopTrackingTouch(SeekBar seekBar) {
      }
    });
  }

  @Override
  protected void onResume() {
    Log.i(TAG, "onResume");
    super.onResume();
    if(mDS1Service != null && mDS1Service.isConnected()) {
      mDS1VolumeSeekBar.setProgress(mDS1Service.getmSetting().volume);
      mDS1VolumeText.setText(String.valueOf(mDS1Service.getmSetting().volume));
    }
  }

  @Override
  protected void onDS1DeviceData() {
    Log.i(TAG, "onDS1DeviceData");
    if(mDS1Service != null && mDS1Service.isConnected()) {
      // Display the current DS1 device volume
      runOnUiThread(() -> {
        mDS1VolumeSeekBar.setEnabled(true);
        mDS1VolumeMinus.setEnabled(true);
        mDS1VolumePlus.setEnabled(true);

        mDS1VolumeSeekBar.setProgress(mDS1Service.getmSetting().volume);
        mDS1VolumeText.setText(String.valueOf(mDS1Service.getmSetting().volume));
      });
    }
  }

  @Override
  protected void onDS1DeviceConnected() {
    Log.i(TAG, "onDS1DeviceConnected");
    mHandler.postDelayed(() -> {
      // Request the DS1 device settings
      mDS1Service.requestSettings();
    }, MESSAGE_TOKEN, 1000);
  }

  @Override
  protected void onDS1DeviceDisconnected() {
    Log.i(TAG, "onDS1DeviceDisconnected");
    mDS1VolumeSeekBar.setEnabled(false);
    mDS1VolumeMinus.setEnabled(false);
    mDS1VolumePlus.setEnabled(false);

    // Try to reconnect to the DS1 device
    scheduleRefreshDS1Service();
  }

  public void onPlus(View v) {
    Log.i(TAG, "onPlus");
    mDS1VolumeSeekBar.setProgress(mDS1VolumeSeekBar.getProgress() + 1);
    int progress = mDS1VolumeSeekBar.getProgress();
    // Set the volume on the DS1 device
    if(mDS1Service != null && mDS1Service.isConnected()) {
      mDS1Service.setVolume(progress);
    }
    mDS1VolumeText.setText(String.valueOf(progress));
  }

  public void onMinus(View v) {
    Log.i(TAG, "onMinus");
    mDS1VolumeSeekBar.setProgress(mDS1VolumeSeekBar.getProgress() - 1);
    int progress = mDS1VolumeSeekBar.getProgress();
    // Set the volume on the DS1 device
    if(mDS1Service != null && mDS1Service.isConnected()) {
      mDS1Service.setVolume(progress);
    }
    mDS1VolumeText.setText(String.valueOf(progress));
  }

  @Override
  protected void onDestroy() {
    Log.i(TAG, "onDestroy");
    super.onDestroy();
    mHandler.removeCallbacksAndMessages(MESSAGE_TOKEN);
  }

  public void onRefreshClick(View v) {
    Log.i(TAG, "onRefreshClick");
    recreate();
  }
}
