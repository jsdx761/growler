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
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.widget.EditText;
import com.google.android.material.appbar.MaterialToolbar;

import androidx.appcompat.app.AppCompatActivity;

import com.jsd.x761.ds1pace.R;

/**
 * An activity that configures location tracking preferences.
 */
public class LocationSettingsActivity extends AppCompatActivity {
  private static final String TAG = "LOCATION_PREF_ACTIVITY";

  private SharedPreferences mSharedPreferences;

  @Override
  protected void onCreate(Bundle b) {
    Log.i(TAG, "onCreate");
    super.onCreate(b);

    setContentView(R.layout.location_settings_activity);
    MaterialToolbar toolbar = findViewById(R.id.settingsToolbar);
    toolbar.setTitle("Location");
    toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

    mSharedPreferences = getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE);

    // Location interval
    EditText intervalEdit = findViewById(R.id.locationIntervalEdit);
    String interval = mSharedPreferences.getString(getString(R.string.key_location_interval), getString(R.string.default_location_interval));
    intervalEdit.setText(interval);

    intervalEdit.addTextChangedListener(new TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {
      }

      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {
      }

      @Override
      public void afterTextChanged(Editable s) {
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        editor.putString(getString(R.string.key_location_interval), s.toString());
        editor.apply();
      }
    });
  }
}
