/*
 * Copyright (c) 2021 NoLimits Enterprises brock@radenso.com
 *
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

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.jsd.x761.growler.Growler.R;

/**
 * An activity that displays the app splash screen and checks for the
 * required app permissions.
 */
public class SplashActivity extends AppCompatActivity {
  private static final String TAG = "SPLASH_ACTIVITY";
  public static final String MESSAGE_TOKEN = "SPLASH_ACTIVITY_MESSAGES";

  private final Handler mHandler = new Handler(Looper.getMainLooper());

  private final ActivityResultLauncher<String> mFineLocationPermissionLauncher =
    registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
      Log.i(TAG, "fineLocationPermissionLauncher result");
      if(granted) {
        Log.i(TAG, "Permission to access this device's precise location was granted");
        runOnUiThread(() -> Toast.makeText(this, "Permission to access this device's precise location was granted",
          Toast.LENGTH_SHORT).show());
        checkBackgroundLocationPermission();
      }
      else {
        Log.i(TAG, "Permission to access this device's precise location was denied");
        runOnUiThread(() -> Toast.makeText(this, "Permission to access this device's precise location was denied",
          Toast.LENGTH_SHORT).show());
        finish();
      }
    });

  private final ActivityResultLauncher<String> mBackgroundLocationPermissionLauncher =
    registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
      Log.i(TAG, "backgroundLocationPermissionLauncher result");
      if(granted) {
        Log.i(TAG, "Permission to access this device's location all the time was granted");
        runOnUiThread(() -> Toast.makeText(this, "Permission to access this device's location all the time was granted",
          Toast.LENGTH_SHORT).show());
        checkBluetoothScanPermission();
      }
      else {
        Log.i(TAG, "Permission to access this device's location all the time was denied");
        runOnUiThread(() -> Toast.makeText(this, "Permission to access this device's location all the time was denied",
          Toast.LENGTH_SHORT).show());
        finish();
      }
    });

  private final ActivityResultLauncher<String> mBluetoothScanPermissionLauncher =
    registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
      Log.i(TAG, "bluetoothScanPermissionLauncher result");
      if(granted) {
        Log.i(TAG, "Permission to find and connect to nearby devices was granted");
        runOnUiThread(() -> Toast.makeText(this, "Permission to find and connect to nearby devices was granted", Toast.LENGTH_SHORT).show());
        checkBluetoothConnectPermission();
      }
      else {
        Log.i(TAG, "Permission to find and connect to nearby devices was denied");
        runOnUiThread(() -> Toast.makeText(this, "Permission to find and connect to nearby devices was denied", Toast.LENGTH_SHORT).show());
        finish();
      }
    });

  private final ActivityResultLauncher<String> mBluetoothConnectPermissionLauncher =
    registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
      Log.i(TAG, "bluetoothConnectPermissionLauncher result");
      if(granted) {
        Log.i(TAG, "Permission to connect to Bluetooth devices was granted");
        runOnUiThread(() -> Toast.makeText(this, "Permission to connect to Bluetooth devices was granted", Toast.LENGTH_SHORT).show());
        enableBluetooth();
      }
      else {
        Log.i(TAG, "Permission to connect to Bluetooth devices was denied");
        runOnUiThread(() -> Toast.makeText(this, "Permission to connect to Bluetooth devices was denied", Toast.LENGTH_SHORT).show());
        finish();
      }
    });

  private final ActivityResultLauncher<Intent> mBluetoothEnableLauncher =
    registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
      Log.i(TAG, "bluetoothEnableLauncher result");
      if(result.getResultCode() == RESULT_OK) {
        BluetoothManager bluetoothManager = (BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
        if(bluetoothManager == null) {
          Log.i(TAG, "Failed to get Bluetooth manager");
          runOnUiThread(() -> Toast.makeText(this, "Failed to get Bluetooth manager", Toast.LENGTH_SHORT).show());
          finish();
          return;
        }

        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
        if(bluetoothAdapter.isEnabled()) {
          runOnUiThread(() -> Toast.makeText(this, "Permission to enable Bluetooth was granted", Toast.LENGTH_SHORT).show());
          startMainMenuActivity();
          return;
        }
      }
      runOnUiThread(() -> Toast.makeText(this, "Permission to enable Bluetooth was denied", Toast.LENGTH_SHORT).show());
      finish();
    });

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    Log.i(TAG, "onCreate");
    super.onCreate(savedInstanceState);

    setContentView(R.layout.splash_activity);

    this.getSupportActionBar().hide();

    // Check for Bluetooth permissions
    checkBluetoothPermission();
  }

  @Override
  protected void onDestroy() {
    Log.i(TAG, "onDestroy");
    super.onDestroy();

    mHandler.removeCallbacksAndMessages(MESSAGE_TOKEN);
  }

  protected void checkFineLocationPermission() {
    Log.i(TAG, "checkFineLocationPermission");
    if(this.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
      runOnUiThread(() -> {
        AlertDialog.Builder b = new AlertDialog.Builder(SplashActivity.this);
        b.setMessage("Please allow Growler to access this device's precise location.\n\n" +
          "This will be needed to be able to look for crowd-sourced alerts and aircrafts close to your location.");
        b.setTitle("Location Access");
        b.setPositiveButton(android.R.string.ok, (dialog, which) ->
          mFineLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION));
        b.setOnCancelListener(dialog -> {
          Log.i(TAG, "Permission to access this device's precise location was denied");
          runOnUiThread(() -> Toast.makeText(SplashActivity.this, "Permission to access this device's precise location was denied",
            Toast.LENGTH_SHORT).show());
          finish();
        });
        b.show();
      });
    }
    else {
      // Foreground location already granted, check background location
      checkBackgroundLocationPermission();
    }
  }

  protected void checkBackgroundLocationPermission() {
    Log.i(TAG, "checkBackgroundLocationPermission");
    if(this.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
      runOnUiThread(() -> {
        AlertDialog.Builder b = new AlertDialog.Builder(SplashActivity.this);
        b.setMessage("Please also allow Growler to access your location all the time.\n\n" +
          "This will be needed to look for alerts and aircrafts close to your location when the app is in the background.");
        b.setTitle("Background Location Access");
        b.setPositiveButton(android.R.string.ok, (dialog, which) ->
          mBackgroundLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION));
        b.setOnCancelListener(dialog -> {
          Log.i(TAG, "Permission to access this device's location all the time was denied");
          runOnUiThread(() -> Toast.makeText(SplashActivity.this, "Permission to access this device's location all the time was denied",
            Toast.LENGTH_SHORT).show());
          finish();
        });
        b.show();
      });
    }
    else {
      // Check for Bluetooth scanning permission
      checkBluetoothScanPermission();
    }
  }

  protected void checkBluetoothScanPermission() {
    Log.i(TAG, "checkBluetoothScanPermission");
    if(this.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
      runOnUiThread(() -> {
        AlertDialog.Builder b = new AlertDialog.Builder(SplashActivity.this);
        b.setMessage("Please allow Growler to find and connect to nearby devices.\n\nThis will be needed to find and connect to your DS1 radar detector.");
        b.setTitle("Nearby Device Access");
        b.setPositiveButton(android.R.string.ok, (dialog, which) ->
          mBluetoothScanPermissionLauncher.launch(Manifest.permission.BLUETOOTH_SCAN));
        b.setOnCancelListener(dialog -> {
          Log.i(TAG, "Permission to find and connect to nearby devices was denied");
          runOnUiThread(() -> Toast.makeText(SplashActivity.this, "Permission to find and connect to nearby devices was denied", Toast.LENGTH_SHORT).show());
          finish();
        });
        b.show();
      });
    }
    else {
      // Check for Bluetooth connect permission
      checkBluetoothConnectPermission();
    }
  }

  protected void checkBluetoothPermission() {
    Log.i(TAG, "checkBluetoothPermission");
    // Check for location access permission
    checkFineLocationPermission();
  }

  protected void checkBluetoothConnectPermission() {
    Log.i(TAG, "checkBluetoothConnectPermission");
    if(this.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
      runOnUiThread(() -> {
        AlertDialog.Builder b = new AlertDialog.Builder(SplashActivity.this);
        b.setMessage("Please allow Growler to connect to Bluetooth devices.\n\nThis will be needed to connect to your DS1 radar detector.");
        b.setTitle("Bluetooth Connect Access");
        b.setPositiveButton(android.R.string.ok, (dialog, which) ->
          mBluetoothConnectPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT));
        b.setOnCancelListener(dialog -> {
          Log.i(TAG, "Permission to connect to Bluetooth devices was denied");
          runOnUiThread(() -> Toast.makeText(SplashActivity.this, "Permission to connect to Bluetooth devices was denied", Toast.LENGTH_SHORT).show());
          finish();
        });
        b.show();
      });
    }
    else {
      // Turn on Bluetooth
      enableBluetooth();
    }
  }

  @SuppressLint("MissingPermission")
  protected void enableBluetooth() {
    Log.i(TAG, "enableBluetooth");
    // Get the Bluetooth manager and adapter
    BluetoothManager bluetoothManager = (BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
    if(bluetoothManager == null) {
      Log.i(TAG, "Failed to get Bluetooth manager");
      runOnUiThread(() -> Toast.makeText(SplashActivity.this, "Failed to get Bluetooth manager", Toast.LENGTH_SHORT).show());
      finish();
      return;
    }

    BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();

    // Enable Bluetooth
    if(!bluetoothAdapter.isEnabled()) {
      runOnUiThread(() -> {
        AlertDialog.Builder b = new AlertDialog.Builder(SplashActivity.this);
        b.setMessage("Please enable Bluetooth.\n\nThis will be needed to be able to connect to your DS1 radar detector.");
        b.setTitle("Bluetooth");
        b.setPositiveButton(android.R.string.ok, (dialog, which) -> {
          Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
          mBluetoothEnableLauncher.launch(intent);
        });
        b.setOnCancelListener(dialog -> {
          runOnUiThread(() -> Toast.makeText(SplashActivity.this, "Permission to enable Bluetooth was denied", Toast.LENGTH_SHORT).show());
          finish();
        });
        b.show();
      });
    }
    else {
      // Start the DS1 device scan activity
      startMainMenuActivity();
    }
  }

  private void startMainMenuActivity() {
    Log.i(TAG, "startMainMenuActivity");
    Runnable startTask = () -> {
      Intent intent = new Intent(SplashActivity.this, AlertsActivity.class);
      intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
      startActivity(intent);
      finish();
    };

    // Wait a few secs before starting the DS1 device scan activity
    mHandler.postDelayed(startTask, MESSAGE_TOKEN, Configuration.SPLASH_TIMER);
  }
}
