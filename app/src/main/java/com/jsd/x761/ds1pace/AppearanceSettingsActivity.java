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
import android.widget.RadioGroup;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import com.google.android.material.appbar.MaterialToolbar;

import com.jsd.x761.ds1pace.R;

/**
 * An activity that configures appearance preferences.
 */
public class AppearanceSettingsActivity extends AppCompatActivity {
  private static final String TAG = "APPEARANCE_ACTIVITY";

  private SharedPreferences mSharedPreferences;

  @Override
  protected void onCreate(Bundle b) {
    Log.i(TAG, "onCreate");
    super.onCreate(b);

    setContentView(R.layout.appearance_settings_activity);
    MaterialToolbar toolbar = findViewById(R.id.settingsToolbar);
    toolbar.setTitle("Appearance");
    toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

    mSharedPreferences = getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE);

    int mode = mSharedPreferences.getInt(getString(R.string.key_appearance_mode), Configuration.APPEARANCE_MODE_SYSTEM);

    RadioGroup group = findViewById(R.id.appearanceModeGroup);
    switch(mode) {
      case Configuration.APPEARANCE_MODE_DARK:
        group.check(R.id.appearanceModeDark);
        break;
      case Configuration.APPEARANCE_MODE_LIGHT:
        group.check(R.id.appearanceModeLight);
        break;
      default:
        group.check(R.id.appearanceModeSystem);
        break;
    }

    group.setOnCheckedChangeListener((g, checkedId) -> {
      int selected;
      int nightMode;
      if(checkedId == R.id.appearanceModeDark) {
        selected = Configuration.APPEARANCE_MODE_DARK;
        nightMode = AppCompatDelegate.MODE_NIGHT_YES;
      }
      else if(checkedId == R.id.appearanceModeLight) {
        selected = Configuration.APPEARANCE_MODE_LIGHT;
        nightMode = AppCompatDelegate.MODE_NIGHT_NO;
      }
      else {
        selected = Configuration.APPEARANCE_MODE_SYSTEM;
        nightMode = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
      }

      SharedPreferences.Editor editor = mSharedPreferences.edit();
      editor.putInt(getString(R.string.key_appearance_mode), selected);
      editor.apply();

      AppCompatDelegate.setDefaultNightMode(nightMode);
    });
  }

  /**
   * Apply the saved appearance mode. Call early in the app entry point.
   */
  public static void applySavedMode(Context context) {
    SharedPreferences prefs = context.getSharedPreferences(
      context.getString(R.string.preference_file_key), Context.MODE_PRIVATE);
    int mode = prefs.getInt(context.getString(R.string.key_appearance_mode), Configuration.APPEARANCE_MODE_SYSTEM);
    switch(mode) {
      case Configuration.APPEARANCE_MODE_DARK:
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        break;
      case Configuration.APPEARANCE_MODE_LIGHT:
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        break;
      default:
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        break;
    }
  }
}
