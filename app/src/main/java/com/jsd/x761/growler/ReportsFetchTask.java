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

import android.location.Location;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * An asynchronous task that fetches crowdsourced reports from a server.
 */
public class ReportsFetchTask implements Runnable {
  private static final String TAG = "REPORTS_FETCH_TASK";

  private final String mSourceURL;
  private final String mSourceEnv;
  private final Location mLocation;
  private final boolean mPoliceEnabled;
  private final boolean mAccidentEnabled;
  private final boolean mHazardEnabled;
  private final boolean mJamEnabled;

  public ReportsFetchTask(String sourceURL, String sourceEnv, Location location,
                          boolean policeEnabled, boolean accidentEnabled,
                          boolean hazardEnabled, boolean jamEnabled) {
    mSourceURL = sourceURL;
    mSourceEnv = sourceEnv;
    mLocation = location;
    mPoliceEnabled = policeEnabled;
    mAccidentEnabled = accidentEnabled;
    mHazardEnabled = hazardEnabled;
    mJamEnabled = jamEnabled;
  }

  @Override
  public void run() {
    Log.i(TAG, String.format("run lat %f lng %f", (float)mLocation.getLatitude(), (float)mLocation.getLongitude()));

    JSONObject json = null;
    if(Configuration.DEBUG_INJECT_TEST_REPORTS != 0) {
      // Support using test reports to help debugging without having to
      // connect to an actual server everytime
      try {
        Log.i(TAG, "using test reports");
        json = new JSONObject(Configuration.DEBUG_TEST_REPORTS);
      }
      catch(Exception e) {
        Log.e(TAG, "Exception reading reports", e);
        onDone(null);
        return;
      }
    }
    else {
      // Connect to the configured server and fetch crowdsourced reports
      // within the configured max distance
      HttpURLConnection connection = null;
      try {
        float distance = Geospatial.toMeters(Configuration.REPORTS_MAX_DISTANCE);
        Location bottom = Geospatial.getDestination(mLocation, distance, 180f);
        Location left = Geospatial.getDestination(mLocation, distance, 270f);
        Location top = Geospatial.getDestination(mLocation, distance, 0f);
        Location right = Geospatial.getDestination(mLocation, distance, 90f);
        URL url = new URL(String.format(
          "%s/live-map/api/georss?bottom=%f&left=%f&top=%f&right=%f&env=%s&types=alerts",
          mSourceURL,
          (float)bottom.getLatitude(),
          (float)left.getLongitude(),
          (float)top.getLatitude(),
          (float)right.getLongitude(),
          mSourceEnv));

        if(Configuration.DEBUG) {
          // Double check that the computed area uses the correct distance
          float topDistance = Geospatial.toMiles(Geospatial.getDistance(mLocation, top));
          float leftDistance = Geospatial.toMiles(Geospatial.getDistance(mLocation, left));
          Log.i(TAG, String.format("report area distance top %f left %f", topDistance, leftDistance));
        }

        // Connect to the server and fetch the reports in JSON form
        Log.i(TAG, String.format("URL.openConnection %s", url.toExternalForm()));
        connection = (HttpURLConnection)url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Connection", "close");
        connection.setRequestProperty("Referer", String.format("%s/live-map", mSourceURL));
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        connection.setConnectTimeout(Configuration.REPORTS_CONNECT_TIMEOUT);
        connection.connect();

        InputStream inputStream = connection.getInputStream();
        if(inputStream == null) {
          onDone(null);
          return;
        }

        String jsonString;
        try(BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
          StringBuilder buffer = new StringBuilder();
          String line;
          while((line = reader.readLine()) != null) {
            buffer.append(line).append("\n");
          }

          if(buffer.isEmpty()) {
            onDone(null);
            return;
          }
          jsonString = buffer.toString();
        }
        json = new JSONObject(jsonString);
      }
      catch(Exception e) {
        Log.e(TAG, "Exception reading JSON from URL", e);
        onDone(null);
        return;
      }
      finally {
        if(connection != null) {
          connection.disconnect();
        }
      }
    }

    List<Alert> reports = new ArrayList<>();
    try {
      JSONArray jsonReports = json.optJSONArray("alerts");
      if(jsonReports != null) {
        int n = 0;
        for(int i = 0; i < jsonReports.length(); i++) {
          JSONObject jsonReport = jsonReports.getJSONObject(i);
          try {
            // Only keep reports of relevant types
            String type = jsonReport.getString("type");
            Log.i(TAG, String.format("report type %s", type));
            if(("POLICE".equals(type) && mPoliceEnabled) ||
               ("ACCIDENT".equals(type) && mAccidentEnabled) ||
               ("HAZARD".equals(type) && mHazardEnabled) ||
               ("JAM".equals(type) && mJamEnabled)) {
              Alert report = Alert.fromReport(mLocation, jsonReport);
              if(Configuration.DEBUG_INJECT_TEST_REPORTS != 0) {
                if(n < Configuration.DEBUG_INJECT_TEST_REPORTS) {
                  reports.add(report);
                  n++;
                }
              }
              else {
                reports.add(report);
              }
            }
          }
          catch(Exception e) {
            Log.e(TAG, "Exception processing report", e);
          }
        }
      }
    }
    catch(Exception e) {
      Log.e(TAG, "Exception processing reports", e);
      onDone(null);
      return;
    }
    onDone(reports);
  }

  protected void onDone(List<Alert> reports) {
    if(reports != null) {
      Log.i(TAG, String.format("onDone %d reports", reports.size()));
    }
    else {
      Log.i(TAG, "onDone null reports");
    }
  }
}
