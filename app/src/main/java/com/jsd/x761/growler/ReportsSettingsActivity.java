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
 * An activity that configures the crowdsourced reports feature preferences.
 */
public class ReportsSettingsActivity extends AppCompatActivity {
  private static final String TAG = "REPORTS_PREF_ACTIVITY";

  private SharedPreferences mSharedPreferences;

  @Override
  protected void onCreate(Bundle b) {
    Log.i(TAG, "onCreate");
    super.onCreate(b);

    setTitle("Crowd-sourced Alerts");

    setContentView(R.layout.reports_settings_activity);
    Switch useReportsSwitch = findViewById(R.id.useReportsSwitch);
    EditText reportsURLEdit = findViewById(R.id.reportsURLEdit);

    // Retrieve the crowd-sourced reports preferences
    mSharedPreferences = getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE);
    boolean useReports = mSharedPreferences.getBoolean(getString(R.string.key_reports_enabled), true);

    useReportsSwitch.setChecked(useReports);
    useReportsSwitch.setOnCheckedChangeListener((v, isChecked) -> {
      Log.i(TAG, "onCheckedChangedListener");
      SharedPreferences.Editor editor = mSharedPreferences.edit();
      editor.putBoolean(getString(R.string.key_reports_enabled), isChecked);
      editor.apply();
    });

    String reportsURL = mSharedPreferences.getString(getString(R.string.key_reports_url), getString(R.string.default_reports_url));
    reportsURLEdit.setText(reportsURL);

    reportsURLEdit.addTextChangedListener(new TextWatcher() {
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
        // Save the crowdsourced reports server URL preference
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putString(getString(R.string.key_reports_url), s.toString());
        editor.apply();
      }
    });

    EditText reportsAPIKeyEdit = findViewById(R.id.reportsAPIKeyEdit);
    String reportsAPIKey = mSharedPreferences.getString(getString(R.string.key_reports_api_key), getString(R.string.default_reports_api_key));
    reportsAPIKeyEdit.setText(reportsAPIKey);

    reportsAPIKeyEdit.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {}

      @Override
      public void afterTextChanged(Editable s) {
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putString(getString(R.string.key_reports_api_key), s.toString());
        editor.apply();
      }
    });

    EditText reportsEnvEdit = findViewById(R.id.reportsEnvEdit);
    String reportsEnv = mSharedPreferences.getString(getString(R.string.key_reports_env), getString(R.string.default_reports_env));
    reportsEnvEdit.setText(reportsEnv);

    reportsEnvEdit.addTextChangedListener(new TextWatcher() {
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
        // Save the crowdsourced reports region preference
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putString(getString(R.string.key_reports_env), s.toString());
        editor.apply();
      }
    });

    // Alert type toggles
    setupTypeToggle(R.id.reportsPoliceSwitch, R.string.key_reports_police, true);
    setupTypeToggle(R.id.reportsAccidentSwitch, R.string.key_reports_accident, true);
    setupTypeToggle(R.id.reportsHazardSwitch, R.string.key_reports_hazard, false);
    setupTypeToggle(R.id.reportsJamSwitch, R.string.key_reports_jam, false);
    setupTypeToggle(R.id.reportsReminderSwitch, R.string.key_reports_reminder, true);
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
