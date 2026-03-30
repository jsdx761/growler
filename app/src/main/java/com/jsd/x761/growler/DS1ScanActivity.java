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

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.jsd.x761.growler.Growler.R;
import com.nolimits.ds1library.DS1Service;
import com.nolimits.ds1library.ExtendedDS1Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An activity that scans for DS1 devices.
 */
public class DS1ScanActivity extends AppCompatActivity {
  private static final String TAG = "DS1_SCAN_ACTIVITY";
  public static final String MESSAGE_TOKEN = "DS1_SCAN_ACTIVITY_MESSAGES";

  private final List<BluetoothDevice> mDeviceList = new ArrayList<>();
  private final List<String> mDeviceNameList = new ArrayList<>();
  private ArrayAdapter<String> mDeviceArrayAdapter;
  private SharedPreferences mSharedPreferences;
  private String mConnectAddress;
  private final Handler mHandler = new Handler(Looper.getMainLooper());
  private boolean mScanning = false;
  private BluetoothLeScanner mBluetoothScanner;
  private int mDS1ConnectedPosition = -1;
  private int mDS1ConnectedColor = Color.LTGRAY;

  private final ActivityResultLauncher<Intent> mBluetoothEnableLauncher =
    registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
      Log.i(TAG, "bluetoothEnableLauncher result");
    });

  private ProgressBar mDS1ScanProgressBar;
  private BluetoothAdapter mBluetoothAdapter;
  private DS1Service mDS1Service;
  private BroadcastReceiver mDS1Receiver;
  private ServiceConnection mDS1ServiceConnection;
  private ScanCallback mScanCallback;

  @SuppressLint("MissingPermission")
  @Override
  protected void onCreate(Bundle b) {
    Log.i(TAG, "onCreate");
    super.onCreate(b);

    setTitle("Radar Detector");

    setContentView(R.layout.ds1_scan_activity);
    Switch useDS1Switch = findViewById(R.id.useDS1Switch);

    // Retrieve the DS1 scan and connection preferences
    mSharedPreferences = getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE);
    boolean useDS1 = mSharedPreferences.getBoolean(getString(R.string.key_ds1_enabled), true);

    useDS1Switch.setChecked(useDS1);
    useDS1Switch.setOnCheckedChangeListener((v, isChecked) -> {
      Log.i(TAG, "onCheckedChangedListener");
      SharedPreferences.Editor editor = mSharedPreferences.edit();
      editor.putBoolean(getString(R.string.key_ds1_enabled), isChecked);
      editor.apply();

      if(isChecked) {
        refresh();
      }
      else {
        stopScan();
      }
    });

    ListView ds1DeviceListView = findViewById(R.id.ds1DeviceList);
    mDeviceArrayAdapter = new ArrayAdapter<>(this, R.layout.ds1_device_item, R.id.ds1DeviceItemText, mDeviceNameList) {
      @NonNull
      @Override
      public View getView(int pos, @Nullable View convertView, @NonNull ViewGroup parent) {
        TextView v = (TextView)super.getView(pos, convertView, parent);
        if(pos == mDS1ConnectedPosition) {
          v.setTextColor(mDS1ConnectedColor);
        }
        return v;
      }
    };
    ds1DeviceListView.setAdapter(mDeviceArrayAdapter);
    mDS1ScanProgressBar = findViewById(R.id.ds1ScanProgressBar);

    // Get the Bluetooth manager and adapter
    BluetoothManager bluetoothManager = (BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
    if(bluetoothManager == null) {
      Log.i(TAG, "Failed to get Bluetooth manager");
      Toast.makeText(this, "Failed to get Bluetooth manager", Toast.LENGTH_SHORT).show();
      finish();
      return;
    }
    mBluetoothAdapter = bluetoothManager.getAdapter();
    if(mBluetoothAdapter == null) {
      Log.i(TAG, "Failed to get Bluetooth adapter");
      Toast.makeText(this, "Failed to get Bluetooth adapter", Toast.LENGTH_SHORT).show();
      finish();
      return;
    }
    if(!mBluetoothAdapter.isEnabled()) {
      mBluetoothEnableLauncher.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
    }

    // Create a Bluetooth scan callback
    mScanCallback = new ScanCallback() {
      @SuppressLint("MissingPermission")
      @Override
      public void onScanResult(int t, ScanResult r) {
        Log.i(TAG, "onScanResult");

        if(mDeviceList.contains(r.getDevice())) {
          return;
        }
        if(r.getDevice().getName() == null) {
          return;
        }

        // Specifically check for DS1 devices
        if(r.getDevice().getAddress().startsWith("E0:00:01") && r.getDevice().getName().startsWith("DS1")) {
          String deviceName = String.format("%s %s", r.getDevice().getName(), r.getDevice().getAddress());
          Log.i(TAG, String.format("mDeviceNameList.add() %s", deviceName));
          mDeviceNameList.add(deviceName);
          mDeviceList.add(r.getDevice());
          runOnUiThread(() -> {
            mDeviceArrayAdapter.notifyDataSetChanged();
          });

          if(mSharedPreferences.getBoolean(getString(R.string.key_ds1_autoconnect), false)) {
            String autoConnectAddress = mSharedPreferences.getString(getString(R.string.key_ds1_autoconnect_address), "");
            // Auto-connect to the last selected device
            if(autoConnectAddress.length() != 0 && autoConnectAddress.equals(r.getDevice().getAddress())) {
              runOnUiThread(() -> {
                mDS1ConnectedPosition = mDeviceList.size() - 1;
                mDS1ConnectedColor = Color.GRAY;
                mDeviceArrayAdapter.notifyDataSetChanged();
              });

              mConnectAddress = autoConnectAddress;
              try {
                mDS1Service.connectTo(autoConnectAddress);
              }
              catch(Exception e) {
                Log.e(TAG, String.format("Exception connecting to %s", mConnectAddress), e);
              }
            }
          }
        }
      }
    };

    // Register a receiver for DS1 device notifications
    mDS1Receiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, String.format("mDS1Service onReceive action %s", action));
        if(action.equals(DS1Service.DS1_CONNECTED)) {
          updateDS1DeviceConnectedText(true);

          if(!Configuration.DEBUG_USE_NULL_DS1_SERVICE) {
            // Save the last successfully connected device
            SharedPreferences.Editor editor = mSharedPreferences.edit();
            editor.putBoolean(getString(R.string.key_ds1_autoconnect), true);
            editor.putString(getString(R.string.key_ds1_autoconnect_address), mConnectAddress);
            editor.apply();
          }
        }
        else if(action.equals(DS1Service.DS1_DISCONNECTED)) {
          updateDS1DeviceConnectedText(false);
        }
      }
    };
    IntentFilter filter = new IntentFilter();
    filter.addAction(DS1Service.DS1_CONNECTED);
    filter.addAction(DS1Service.DS1_DISCONNECTED);
    registerReceiver(mDS1Receiver, filter, Context.RECEIVER_EXPORTED);

    // Start the DS1 service and bind a service connection to it
    mDS1ServiceConnection = new ServiceConnection() {
      @Override
      public void onServiceConnected(ComponentName name, IBinder s) {
        Log.i(TAG, "mDS1ServiceConnection.onServiceConnected");
        if(!Configuration.DEBUG_USE_NULL_DS1_SERVICE) {
          mDS1Service = ((DS1Service.ThisBinder)s).getService();
          Log.i(TAG, "mDS1Service.disconnect()");
          mDS1Service.disconnect();
        }
        updateDS1DeviceConnectedText(mDS1Service != null && mDS1Service.isConnected());
      }

      @Override
      public void onServiceDisconnected(ComponentName n) {
        Log.i(TAG, "mDS1ServiceConnection.onServiceDisconnected");
      }
    };

    Log.i(TAG, "bindService() mDS1ServiceConnection");
    Intent ds1ServiceIntent = new Intent(this, ExtendedDS1Service.class);
    bindService(ds1ServiceIntent, mDS1ServiceConnection, BIND_AUTO_CREATE);

    ds1DeviceListView.setOnItemClickListener((p, v, pos, id) -> {
      // Disconnect from current DS1 device
      if(mDS1Service.isConnected()) {
        Log.i(TAG, "mDS1Service.disconnect()");
        mDS1Service.disconnect();
      }

      runOnUiThread(() -> {
        mDS1ConnectedPosition = pos;
        mDS1ConnectedColor = Color.GRAY;
        mDeviceArrayAdapter.notifyDataSetChanged();
      });

      // Check that the DS1 device is in pairing mode
      if(mDeviceList.get(pos).getBondState() != BluetoothDevice.BOND_BONDED) {
        AlertDialog.Builder builder = new AlertDialog.Builder(DS1ScanActivity.this);
        builder.setMessage("Please make sure DS1 is in pairing mode")
          .setCancelable(false).setPositiveButton(
            "OK",
            (dialog, id1) -> {
              try {
                // Pair and connect to the DS1 device
                mDeviceList.get(pos).createBond();
                mConnectAddress = mDeviceList.get(pos).getAddress();
                mDS1Service.connectTo(mDeviceList.get(pos).getAddress());
              }
              catch(Exception e) {
                Log.e(TAG, String.format("Exception connecting to %s", mConnectAddress), e);
              }
            });
        AlertDialog alert = builder.create();
        alert.show();
      }
      else {
        // Connect to an already paired DS1 device
        mConnectAddress = mDeviceList.get(pos).getAddress();
        try {
          mDS1Service.connectTo(mConnectAddress);
        }
        catch(Exception e) {
          Log.e(TAG, String.format("Exception connecting to %s", mConnectAddress), e);
        }
      }
    });

    // Start scanning for DS1 devices
    if(useDS1) {
      Runnable startScanTask = () -> startScan();
      mHandler.postDelayed(startScanTask, MESSAGE_TOKEN, 1);
    }
  }

  private void updateDS1DeviceConnectedText(boolean connected) {
    runOnUiThread(() -> {
      if(connected) {
        mDS1ConnectedColor = Color.LTGRAY;
      }
      else {
        mDS1ConnectedColor = Color.DKGRAY;
      }
      mDeviceArrayAdapter.notifyDataSetChanged();
    });
  }

  @Override
  protected void onDestroy() {
    Log.i(TAG, "onDestroy");
    super.onDestroy();
    mHandler.removeCallbacksAndMessages(MESSAGE_TOKEN);

    stopScan();

    if(mDS1Receiver != null) {
      unregisterReceiver(mDS1Receiver);
    }
    if(mDS1Service != null) {
      Log.i(TAG, "mDS1Service.disconnect()");
      mDS1Service.disconnect();
      mDS1Service.close();
    }
    if(mDS1ServiceConnection != null) {
      Log.i(TAG, "unbindService() mDS1ServiceConnection");
      unbindService(mDS1ServiceConnection);
    }
  }

  @Override
  protected void onPause() {
    Log.i(TAG, "onPause");
    super.onPause();
  }

  @Override
  protected void onResume() {
    Log.i(TAG, "onResume");
    super.onResume();

  }

  @SuppressLint("MissingPermission")
  private void stopScan() {
    Log.i(TAG, "stopScan");
    mDS1ScanProgressBar.setVisibility(View.INVISIBLE);

    if(mScanning) {
      mScanning = false;
      if(mBluetoothScanner != null) {
        mBluetoothScanner.stopScan(mScanCallback);
      }
    }
  }

  @SuppressLint("MissingPermission")
  private void startScan() {
    Log.i(TAG, "startScan");

    if(Configuration.DEBUG_USE_NULL_DS1_SERVICE) {
      // Support testing the app without having to connect to an actual
      // DS1 device every time
      Runnable nullDS1ServiceConnectedTask = () -> mDS1Receiver.onReceive(DS1ScanActivity.this, new Intent(DS1Service.DS1_CONNECTED));
      mHandler.postDelayed(nullDS1ServiceConnectedTask, MESSAGE_TOKEN, Configuration.DEBUG_NULL_DS1_SERVICE_SCAN_TIMER);
      return;
    }

    mScanning = true;
    mBluetoothScanner = mBluetoothAdapter.getBluetoothLeScanner();
    ScanFilter scanFilter = new ScanFilter.Builder().setDeviceName(Configuration.DS1_SERVICE_SCAN_NAME).build();
    ScanSettings scanSettings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
    mBluetoothScanner.startScan(Collections.singletonList(scanFilter), scanSettings, mScanCallback);
    mDS1ScanProgressBar.setVisibility(View.VISIBLE);
  }

  private void refresh() {
    Log.i(TAG, "refresh");

    // Disconnect from DS1 device and stop scanning for DS1 devices
    if(mDS1Service.isConnected()) {
      Log.i(TAG, "mDS1Service.disconnect()");
      mDS1Service.disconnect();
    }
    Log.i(TAG, "stopScan()");
    stopScan();

    // Refresh and restart scanning
    mDeviceList.clear();
    mDeviceNameList.clear();
    mDeviceArrayAdapter.notifyDataSetChanged();
    Runnable startScanTask = () -> startScan();
    mHandler.postDelayed(startScanTask, MESSAGE_TOKEN, 1);
  }
}
