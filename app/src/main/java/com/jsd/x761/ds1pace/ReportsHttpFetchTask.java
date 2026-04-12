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

import android.location.Location;
import android.os.Handler;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Fetches Waze reports from a GeoRSS-compatible API server.
 *
 * The server URL can be the Waze live map (https://www.waze.com) for
 * direct access, or a proxy server that handles the Waze reCAPTCHA
 * Enterprise session on behalf of the client.
 *
 * The proxy server API is compatible with the Waze GeoRSS endpoint:
 *   GET {url}/georss?bottom=LAT&left=LNG&top=LAT&right=LNG&env=ENV&types=alerts
 *
 * An optional API key can be configured for proxy server authentication,
 * sent as the X-API-Key header.
 */
public class ReportsHttpFetchTask {
  private static final String TAG = "REPORTS_HTTP_FETCH";

  private final Handler mHandler;
  private final Executor mExecutor;

  public interface FetchCallback {
    void onDone(List<Alert> reports);
  }

  public ReportsHttpFetchTask(Handler handler) {
    mHandler = handler;
    mExecutor = Executors.newSingleThreadExecutor();
  }

  /**
   * Fetch Waze reports from the configured server.
   */
  public void fetch(String sourceURL, String sourceEnv, String apiKey,
                    Location location,
                    boolean policeEnabled, boolean accidentEnabled,
                    boolean hazardEnabled, boolean jamEnabled,
                    FetchCallback callback) {
    Log.i(TAG, String.format("fetch lat %f lng %f", (float)location.getLatitude(), (float)location.getLongitude()));

    // Support debug test reports
    if(Configuration.DEBUG_INJECT_TEST_REPORTS != 0) {
      try {
        Log.i(TAG, "using test reports");
        JSONObject json = new JSONObject(Configuration.DEBUG_TEST_REPORTS);
        List<Alert> reports = parseReports(json, location, policeEnabled, accidentEnabled, hazardEnabled, jamEnabled);
        callback.onDone(reports);
      }
      catch(Exception e) {
        Log.e(TAG, "Exception reading test reports", e);
        callback.onDone(null);
      }
      return;
    }

    // Compute bounding box around the current location
    float distance = Geospatial.toMeters(Configuration.REPORTS_MAX_DISTANCE);
    Location bottom = Geospatial.getDestination(location, distance, 180f);
    Location left = Geospatial.getDestination(location, distance, 270f);
    Location top = Geospatial.getDestination(location, distance, 0f);
    Location right = Geospatial.getDestination(location, distance, 90f);

    String apiURL = String.format(
      "%s/georss?bottom=%f&left=%f&top=%f&right=%f&env=%s&types=alerts",
      sourceURL,
      (float)bottom.getLatitude(),
      (float)left.getLongitude(),
      (float)top.getLatitude(),
      (float)right.getLongitude(),
      sourceEnv);

    Log.i(TAG, String.format("fetch %s", apiURL));

    mExecutor.execute(() -> {
      HttpURLConnection conn = null;
      try {
        conn = (HttpURLConnection) new URL(apiURL).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(Configuration.REPORTS_CONNECT_TIMEOUT);
        conn.setReadTimeout(Configuration.REPORTS_CONNECT_TIMEOUT);
        conn.setRequestProperty("Accept", "application/json");
        if(apiKey != null && !apiKey.isEmpty()) {
          conn.setRequestProperty("X-API-Key", apiKey);
        }

        int status = conn.getResponseCode();
        Log.i(TAG, String.format("status %d", status));

        if(status == 200) {
          BufferedReader reader = new BufferedReader(
            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
          StringBuilder sb = new StringBuilder();
          String line;
          while((line = reader.readLine()) != null) {
            if(sb.length() > 1024 * 1024) break;
            sb.append(line);
          }
          reader.close();

          JSONObject json = new JSONObject(sb.toString());
          List<Alert> reports = parseReports(json, location,
            policeEnabled, accidentEnabled, hazardEnabled, jamEnabled);
          if(reports != null) {
            Log.i(TAG, String.format("parsed %d reports", reports.size()));
          }
          mHandler.post(() -> callback.onDone(reports));
        }
        else {
          Log.e(TAG, String.format("error %d", status));
          mHandler.post(() -> callback.onDone(null));
        }
      }
      catch(Exception e) {
        Log.e(TAG, "Exception fetching reports", e);
        mHandler.post(() -> callback.onDone(null));
      }
      finally {
        if(conn != null) {
          conn.disconnect();
        }
      }
    });
  }

  /**
   * No-op for API compatibility. The HTTP client doesn't need cleanup.
   */
  public void destroy() {
  }

  /**
   * Parse the georss JSON response into a list of Alert objects, filtering
   * by enabled report types.
   */
  static List<Alert> parseReports(JSONObject json, Location location,
                                   boolean policeEnabled, boolean accidentEnabled,
                                   boolean hazardEnabled, boolean jamEnabled) {
    List<Alert> reports = new ArrayList<>();
    try {
      JSONArray jsonReports = json.optJSONArray("alerts");
      if(jsonReports != null) {
        int n = 0;
        for(int i = 0; i < jsonReports.length(); i++) {
          JSONObject jsonReport = jsonReports.getJSONObject(i);
          try {
            String type = jsonReport.getString("type");
            Log.i(TAG, String.format("report type %s", type));
            if(("POLICE".equals(type) && policeEnabled) ||
               ("ACCIDENT".equals(type) && accidentEnabled) ||
               ("HAZARD".equals(type) && hazardEnabled) ||
               ("JAM".equals(type) && jamEnabled)) {
              Alert report = Alert.fromReport(location, jsonReport);
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
      return null;
    }
    return reports;
  }
}
