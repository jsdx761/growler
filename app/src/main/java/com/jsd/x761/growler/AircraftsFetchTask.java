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
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * An asynchronous task that fetches aircraft state vectors from a server.
 */
public class AircraftsFetchTask implements Runnable {
  private static final String TAG = "AIRCRAFTS_FETCH_TASK";

  private final String mSourceURL;
  private final String mUser;
  private final String mPassword;
  private final AircraftsDatabase mAircraftsDatabase;
  private final Location mLocation;

  public AircraftsFetchTask(String sourceURL, String user, String password, AircraftsDatabase aircraftsDatabase, Location location) {
    // The source URL is configured from the app preferences
    mSourceURL = sourceURL;
    mUser = user;
    mPassword = password;
    mAircraftsDatabase = aircraftsDatabase;
    mLocation = location;
  }

  @Override
  public void run() {
    Log.i(TAG, String.format("run lat %f lng %f", (float)mLocation.getLatitude(), (float)mLocation.getLongitude()));

    JSONObject json = null;
    if(Configuration.DEBUG_INJECT_TEST_AIRCRAFTS != 0) {
      // Support using test aircraft state vectors to help debugging without
      // having to connect to an actual server everytime
      try {
        Log.i(TAG, "using test aircraft state vectors");
        json = new JSONObject(Configuration.DEBUG_TEST_AIRCRAFTS);
      }
      catch(JSONException e) {
        Log.e(TAG, "JSONException reading JSON", e);
        onDone(null);
        return;
      }
    }
    else {
      // Connect to the configured server and fetch aircraft state vectors
      // within the configured max distance
      HttpURLConnection connection = null;
      try {
        float distance = Geospatial.toMeters(Configuration.AIRCRAFTS_MAX_DISTANCE);
        Location bottom = Geospatial.getDestination(mLocation, distance, 180f);
        Location left = Geospatial.getDestination(mLocation, distance, 270f);
        Location top = Geospatial.getDestination(mLocation, distance, 0f);
        Location right = Geospatial.getDestination(mLocation, distance, 90f);
        URL url = new URL(String.format(
          "%s/api/states/all?lamin=%f&lomin=%f&lamax=%f&lomax=%f",
          mSourceURL,
          (float)bottom.getLatitude(),
          (float)left.getLongitude(),
          (float)top.getLatitude(),
          (float)right.getLongitude()));

        if(Configuration.DEBUG) {
          // Double check that the computed area uses the correct distance
          float topDistance = Geospatial.toMiles(Geospatial.getDistance(mLocation, top));
          float leftDistance = Geospatial.toMiles(Geospatial.getDistance(mLocation, left));
          Log.i(TAG, String.format("aircraft area distance top %f left %f", topDistance, leftDistance));
        }

        // Connect to the server and fetch the aircraft state vectors in
        // JSON form
        Log.i(TAG, String.format("URL.openConnection %s", url.toExternalForm()));
        connection = (HttpURLConnection)url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Connection", "close");
        connection.setConnectTimeout(Configuration.AIRCRAFTS_CONNECT_TIMEOUT);

        if(mUser.length() != 0 && mPassword.length() != 0) {
          String userPass = mUser + ":" + mPassword;
          String basicAuth = "Basic " + new String(Base64.getEncoder().encode(userPass.getBytes()));
          connection.setRequestProperty("Authorization", basicAuth);
        }
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

        String rateLimit = connection.getHeaderField("X-Rate-Limit-Remaining");
        Log.i(TAG, String.format("URL.openConnection remaining rate limit %s", rateLimit));

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

    List<Alert> aircrafts = new ArrayList<>();
    try {
      JSONArray jsonAircrafts = json.optJSONArray("states");
      if(jsonAircrafts != null) {
        int n = 0;
        for(int i = 0; i < jsonAircrafts.length(); i++) {
          JSONArray jsonAircraft = jsonAircrafts.getJSONArray(i);
          try {
            // Only keep aicraft state vectors of relevant types, not on the
            // ground and within the configured distance
            String transponder = jsonAircraft.getString(0);
            Log.i(TAG, String.format("aircraft transponder icao24 address %s", transponder));

            String[] aircraftInfo = mAircraftsDatabase.getInterestingAircrafts().get(transponder);
            if(aircraftInfo != null) {
              Alert aircraft = Alert.fromAircraft(mLocation, jsonAircraft, aircraftInfo);
              if(Configuration.DEBUG_INJECT_TEST_AIRCRAFTS != 0) {
                if(n < Configuration.DEBUG_INJECT_TEST_AIRCRAFTS) {
                  aircrafts.add(aircraft);
                  n++;
                }
              }
              else {
                if(!aircraft.onGround && aircraft.distance <= Configuration.AIRCRAFTS_MAX_DISTANCE) {
                  aircrafts.add(aircraft);
                }
              }
            }
          }
          catch(Exception e) {
            Log.e(TAG, "Exception processing aircraft state vector", e);
          }
        }
      }
    }
    catch(Exception e) {
      Log.e(TAG, "Exception processing aircraft state vectors", e);
      onDone(null);
      return;
    }
    onDone(aircrafts);
  }

  protected void onDone(List<Alert> aircrafts) {
    if(aircrafts != null) {
      Log.i(TAG, String.format("onDone %d aircrafts", aircrafts.size()));
    }
    else {
      Log.i(TAG, "onDone null aircrafts");
    }
  }
}
