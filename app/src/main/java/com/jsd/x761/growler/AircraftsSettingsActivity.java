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

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.widget.EditText;
import android.widget.Switch;

import androidx.appcompat.app.AppCompatActivity;

import com.jsd.x761.growler.Growler.R;

/**
 * An activity that configures the aircraft report feature preferences.
 */
public class AircraftsSettingsActivity extends AppCompatActivity {
  private static final String TAG = "AIRCRAFTS_PREF_ACTIVITY";

  private SharedPreferences mSharedPreferences;

  @Override
  protected void onCreate(Bundle b) {
    Log.i(TAG, "onCreate");
    super.onCreate(b);

    setTitle("Aircraft Alerts");

    setContentView(R.layout.aircrafts_settings_activity);
    Switch useAircraftsSwitch = findViewById(R.id.useAircraftsSwitch);
    EditText aircraftsURLEdit = findViewById(R.id.aircraftsURLEdit);
    EditText aircraftsUserEdit = findViewById(R.id.aircraftsUserEdit);
    EditText aircraftsPasswordEdit = findViewById(R.id.aircraftsPasswordEdit);

    // Retrieve the aircraft report server URL preference
    mSharedPreferences = getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE);

    boolean useAircrafts = mSharedPreferences.getBoolean(getString(R.string.key_aircrafts_enabled), true);
    useAircraftsSwitch.setChecked(useAircrafts);
    useAircraftsSwitch.setOnCheckedChangeListener((v, isChecked) -> {
      Log.i(TAG, "onCheckedChangedListener");
      SharedPreferences.Editor editor = mSharedPreferences.edit();
      editor.putBoolean(getString(R.string.key_aircrafts_enabled), isChecked);
      editor.apply();
    });

    String aircraftsURL = mSharedPreferences.getString(getString(R.string.key_aircrafts_url), getString(R.string.default_aircrafts_url));
    aircraftsURLEdit.setText(aircraftsURL);
    aircraftsURLEdit.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(
        CharSequence s, int start, int count, int after) {
      }

      @Override
      public void onTextChanged(
        CharSequence s, int start, int before, int count) {
      }

      @Override
      public void afterTextChanged(Editable s) {
        Log.i(TAG, String.format("afterTextChanged %s", s.toString()));

        // Save the aircraft report server URL preference
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putString(getString(R.string.key_aircrafts_url), s.toString());
        editor.apply();
      }
    });

    String aircraftsUser = mSharedPreferences.getString(getString(R.string.key_aircrafts_user), getString(R.string.default_aircrafts_user));
    aircraftsUserEdit.setText(aircraftsUser);
    aircraftsUserEdit.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(
        CharSequence s, int start, int count, int after) {
      }

      @Override
      public void onTextChanged(
        CharSequence s, int start, int before, int count) {
      }

      @Override
      public void afterTextChanged(Editable s) {
        Log.i(TAG, String.format("afterTextChanged %s", s.toString()));

        // Save the aircraft report server user preference
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putString(getString(R.string.key_aircrafts_user), s.toString());
        editor.apply();
      }
    });

    String aircraftsPassword = mSharedPreferences.getString(getString(R.string.key_aircrafts_password), getString(R.string.default_aircrafts_password));
    aircraftsPasswordEdit.setText(aircraftsPassword);
    aircraftsPasswordEdit.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(
        CharSequence s, int start, int count, int after) {
      }

      @Override
      public void onTextChanged(
        CharSequence s, int start, int before, int count) {
      }

      @Override
      public void afterTextChanged(Editable s) {
        Log.i(TAG, String.format("afterTextChanged %s", s.toString()));

        // Save the aircraft report server password preference
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putString(getString(R.string.key_aircrafts_password), s.toString());
        editor.apply();
      }
    });

    setupTypeToggle(R.id.aircraftsReminderSwitch, R.string.key_aircrafts_reminder, true);
  }

  private void setupTypeToggle(int switchId, int keyId, boolean defaultValue) {
    Switch toggle = findViewById(switchId);
    boolean enabled = mSharedPreferences.getBoolean(getString(keyId), defaultValue);
    toggle.setChecked(enabled);
    toggle.setOnCheckedChangeListener((v, isChecked) -> {
      SharedPreferences.Editor editor = mSharedPreferences.edit();
      editor.putBoolean(getString(keyId), isChecked);
      editor.apply();
    });
  }
}
