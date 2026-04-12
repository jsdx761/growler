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

import android.util.Log;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * A simple in memory database of interesting aircrafts.
 */
public class AircraftsDatabase {
  private static final String TAG = "AIRCRAFTS_DATABASE";

  private final Map<String, String[]> mInterestingAircrafts = new HashMap<>();

  public AircraftsDatabase(DS1ServiceActivity serviceActivity) {
    BufferedReader reader = null;
    try {
      // Load database from res/raw/interesting_aircrafts.csv
      Log.i(TAG, "loading aircrafts database");
      InputStream inputStream = serviceActivity.getAssets().open("interesting_aircrafts.csv");
      reader = new BufferedReader(new InputStreamReader(inputStream));
      String line;
      while((line = reader.readLine()) != null) {
        String[] aircraftInfo = line.split(",");
        if(aircraftInfo.length >= 4) {
          mInterestingAircrafts.put(aircraftInfo[0], aircraftInfo);
        }
      }
    }
    catch(FileNotFoundException e) {
      Log.i(TAG, "aircrafts database not included");
    }
    catch(IOException e) {
      Log.e(TAG, "IOException loading aircrafts database", e);
    }
    finally {
      if(reader != null) {
        try {
          reader.close();
        }
        catch(IOException e) {
          Log.e(TAG, "IOException closing reader", e);
        }
      }
    }
  }

  public Map<String, String[]> getInterestingAircrafts() {
    return mInterestingAircrafts;
  }
}
