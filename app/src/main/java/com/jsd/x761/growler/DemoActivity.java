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

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.jsd.x761.growler.Growler.R;

/**
 * Sends simulated alerts via broadcast, then navigates to AlertsActivity
 * so the user can see and hear the announcements. The simulated alerts
 * are cleared automatically after a timeout.
 */
public class DemoActivity extends AppCompatActivity {
  private static final String TAG = "DEMO_ACTIVITY";
  private static final long CLEAR_DELAY_MS = 30000;

  private final Handler mHandler = new Handler(Looper.getMainLooper());

  @Override
  protected void onCreate(Bundle b) {
    Log.i(TAG, "onCreate");
    super.onCreate(b);

    setTitle("Demo");
    setContentView(R.layout.demo_activity);

    findViewById(R.id.demoRadarButton).setOnClickListener(v -> runRadarDemo());
    findViewById(R.id.demoCrowdSourcedButton).setOnClickListener(v -> runCrowdSourcedDemo());
    findViewById(R.id.demoAircraftButton).setOnClickListener(v -> runAircraftDemo());
  }

  private void runRadarDemo() {
    Log.i(TAG, "runRadarDemo");

    // Clear any existing simulated alerts
    sendBroadcast(new Intent("com.jsd.x761.growler.SIMULATE_RADAR_CLEAR"));

    // Send a variety of radar alerts with staggered timing
    mHandler.postDelayed(() -> {
      Intent i = new Intent("com.jsd.x761.growler.SIMULATE_RADAR");
      i.putExtra("band", "KA");
      i.putExtra("freq", 34.7f);
      i.putExtra("intensity", 60.0f);
      i.putExtra("distance", 0.3f);
      i.putExtra("bearing", 12);
      sendBroadcast(i);
    }, 500);

    mHandler.postDelayed(() -> {
      Intent i = new Intent("com.jsd.x761.growler.SIMULATE_RADAR");
      i.putExtra("band", "KA");
      i.putExtra("freq", 35.5f);
      i.putExtra("intensity", 40.0f);
      i.putExtra("distance", 0.5f);
      i.putExtra("bearing", 2);
      sendBroadcast(i);
    }, 3000);

    mHandler.postDelayed(() -> {
      Intent i = new Intent("com.jsd.x761.growler.SIMULATE_RADAR");
      i.putExtra("band", "K");
      i.putExtra("freq", 24.1f);
      i.putExtra("intensity", 30.0f);
      i.putExtra("distance", 0.8f);
      i.putExtra("bearing", 10);
      sendBroadcast(i);
    }, 6000);

    // Clear after timeout
    mHandler.postDelayed(() ->
      sendBroadcast(new Intent("com.jsd.x761.growler.SIMULATE_RADAR_CLEAR")),
      CLEAR_DELAY_MS);

    navigateToAlerts();
  }

  private void runCrowdSourcedDemo() {
    Log.i(TAG, "runCrowdSourcedDemo");

    sendBroadcast(new Intent("com.jsd.x761.growler.SIMULATE_REPORT_CLEAR"));

    mHandler.postDelayed(() -> {
      Intent i = new Intent("com.jsd.x761.growler.SIMULATE_REPORT");
      i.putExtra("type", "POLICE");
      i.putExtra("subtype", "POLICE_VISIBLE");
      i.putExtra("city", "San Jose");
      i.putExtra("street", "US-101 N");
      i.putExtra("lat", 37.334f);
      i.putExtra("lng", -121.844f);
      sendBroadcast(i);
    }, 500);

    mHandler.postDelayed(() -> {
      Intent i = new Intent("com.jsd.x761.growler.SIMULATE_REPORT");
      i.putExtra("type", "ACCIDENT");
      i.putExtra("subtype", "");
      i.putExtra("city", "Santa Clara");
      i.putExtra("street", "I-280 S");
      i.putExtra("lat", 37.335f);
      i.putExtra("lng", -121.850f);
      sendBroadcast(i);
    }, 3000);

    mHandler.postDelayed(() -> {
      Intent i = new Intent("com.jsd.x761.growler.SIMULATE_REPORT");
      i.putExtra("type", "POLICE");
      i.putExtra("subtype", "POLICE_HIDDEN");
      i.putExtra("city", "Sunnyvale");
      i.putExtra("street", "CA-237 E");
      i.putExtra("lat", 37.388f);
      i.putExtra("lng", -122.010f);
      sendBroadcast(i);
    }, 6000);

    mHandler.postDelayed(() ->
      sendBroadcast(new Intent("com.jsd.x761.growler.SIMULATE_REPORT_CLEAR")),
      CLEAR_DELAY_MS);

    navigateToAlerts();
  }

  private void runAircraftDemo() {
    Log.i(TAG, "runAircraftDemo");

    sendBroadcast(new Intent("com.jsd.x761.growler.SIMULATE_AIRCRAFT_CLEAR"));

    mHandler.postDelayed(() -> {
      Intent i = new Intent("com.jsd.x761.growler.SIMULATE_AIRCRAFT");
      i.putExtra("transponder", "a6165b");
      i.putExtra("type", "Helicopter");
      i.putExtra("owner", "CHP");
      i.putExtra("manufacturer", "Bell");
      i.putExtra("lat", 37.336f);
      i.putExtra("lng", -121.846f);
      i.putExtra("altitude", 1500.0f);
      sendBroadcast(i);
    }, 500);

    mHandler.postDelayed(() -> {
      Intent i = new Intent("com.jsd.x761.growler.SIMULATE_AIRCRAFT");
      i.putExtra("transponder", "a54f11");
      i.putExtra("type", "Airplane");
      i.putExtra("owner", "Police");
      i.putExtra("manufacturer", "Cessna");
      i.putExtra("lat", 37.340f);
      i.putExtra("lng", -121.860f);
      i.putExtra("altitude", 2500.0f);
      sendBroadcast(i);
    }, 3000);

    mHandler.postDelayed(() -> {
      Intent i = new Intent("com.jsd.x761.growler.SIMULATE_AIRCRAFT");
      i.putExtra("transponder", "a3566a");
      i.putExtra("type", "Helicopter");
      i.putExtra("owner", "Sheriff");
      i.putExtra("manufacturer", "Eurocopter");
      i.putExtra("lat", 37.330f);
      i.putExtra("lng", -121.840f);
      i.putExtra("altitude", 800.0f);
      sendBroadcast(i);
    }, 6000);

    mHandler.postDelayed(() ->
      sendBroadcast(new Intent("com.jsd.x761.growler.SIMULATE_AIRCRAFT_CLEAR")),
      CLEAR_DELAY_MS);

    navigateToAlerts();
  }

  private void navigateToAlerts() {
    // Small delay to let the first broadcast be received before
    // navigating, so the alerts are visible on the main screen
    mHandler.postDelayed(() -> {
      Intent intent = new Intent(DemoActivity.this, AlertsActivity.class);
      intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      intent.putExtra("demo", true);
      startActivity(intent);
      finish();
    }, 200);
  }
}
