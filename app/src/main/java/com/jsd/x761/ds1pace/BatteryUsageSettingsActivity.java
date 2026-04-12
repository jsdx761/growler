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
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.CompoundButton;

import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.appbar.MaterialToolbar;

import com.jsd.x761.ds1pace.R;

/**
 * An activity that configures battery usage preferences.
 */
public class BatteryUsageSettingsActivity extends AppCompatActivity {
  private static final String TAG = "BATTERY_USAGE_ACTIVITY";

  private SharedPreferences mSharedPreferences;

  @Override
  protected void onCreate(Bundle b) {
    Log.i(TAG, "onCreate");
    super.onCreate(b);

    setContentView(R.layout.battery_usage_settings_activity);
    MaterialToolbar toolbar = findViewById(R.id.settingsToolbar);
    toolbar.setTitle("Battery Usage");
    toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

    mSharedPreferences = getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE);

    // Foreground service toggle
    CompoundButton foregroundServiceSwitch = findViewById(R.id.foregroundServiceSwitch);
    boolean foregroundService = mSharedPreferences.getBoolean(getString(R.string.key_location_foreground_service), true);
    foregroundServiceSwitch.setChecked(foregroundService);
    foregroundServiceSwitch.setOnCheckedChangeListener((v, isChecked) -> {
      SharedPreferences.Editor editor = mSharedPreferences.edit();
      editor.putBoolean(getString(R.string.key_location_foreground_service), isChecked);
      editor.apply();
    });

    // Wake lock toggle
    CompoundButton wakeLockSwitch = findViewById(R.id.wakeLockSwitch);
    boolean wakeLock = mSharedPreferences.getBoolean(getString(R.string.key_location_wake_lock), true);
    wakeLockSwitch.setChecked(wakeLock);
    wakeLockSwitch.setOnCheckedChangeListener((v, isChecked) -> {
      SharedPreferences.Editor editor = mSharedPreferences.edit();
      editor.putBoolean(getString(R.string.key_location_wake_lock), isChecked);
      editor.apply();
    });
  }
}
