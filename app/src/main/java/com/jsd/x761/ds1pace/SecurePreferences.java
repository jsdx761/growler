/*
 * Copyright (c) 2023 jsdx761
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.jsd.x761.ds1pace;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKeys;

/**
 * Provides an EncryptedSharedPreferences instance for storing credentials.
 * Falls back to regular SharedPreferences if encryption is unavailable.
 */
public class SecurePreferences {
  private static final String TAG = "SECURE_PREFS";
  private static final String FILE_NAME = "ds1pace_secure_prefs";

  public static SharedPreferences get(Context context) {
    try {
      String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
      return EncryptedSharedPreferences.create(
        FILE_NAME,
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
      );
    }
    catch(Exception e) {
      Log.e(TAG, "Failed to create encrypted preferences, falling back to regular", e);
      return context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }
  }
}
