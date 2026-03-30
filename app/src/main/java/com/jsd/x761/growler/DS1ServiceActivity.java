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
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import com.jsd.x761.growler.Growler.R;
import com.nolimits.ds1library.DS1Service;
import com.nolimits.ds1library.ExtendedDS1Service;

/**
 * A base activity for activities that need to connect to a DS1 service.
 */
public class DS1ServiceActivity extends AppCompatActivity {
  private static final String TAG = "BASE_SERVICE_ACTIVITY";
  public static final String MESSAGE_TOKEN = "DS1_SERVICE_ACTIVITY_MESSAGES";

  private final Handler mHandler = new Handler(Looper.getMainLooper());
  private SharedPreferences mSharedPreferences;
  protected boolean mDS1ServiceEnabled;
  protected int mDS1ServiceActive;
  private BroadcastReceiver mDS1Receiver;
  private ServiceConnection mDS1ServiceConnection;
  protected DS1Service mDS1Service;
  private boolean mDS1DeviceDisconnected = false;
  protected ImageView mDS1ConnectedImage;
  private Runnable mRefreshDS1DeviceTask;

  // A receiver for DS1 notifications
  @Override
  protected void onCreate(Bundle b) {
    Log.i(TAG, "onCreate");
    super.onCreate(b);

    mSharedPreferences = getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE);
    mDS1ServiceEnabled = mSharedPreferences.getBoolean(getString(R.string.key_ds1_enabled), true);

    if(mDS1ServiceEnabled) {
      mDS1ServiceActive = 1;
      mRefreshDS1DeviceTask = () -> {
        refreshDS1Service();
      };
    }
  }

  private void updateDS1DeviceConnectedText(boolean connected) {
    runOnUiThread(() -> {
      if(connected) {
        mDS1ConnectedImage.setColorFilter(Color.LTGRAY);
      }
      else {
        mDS1ConnectedImage.setColorFilter(Color.DKGRAY);
      }
    });
  }

  protected void bindDS1Service(Runnable onDone) {
    Log.i(TAG, "bindDS1Service");
    // Report that the DS1 device is not connected if connection doesn't
    // complete after a few seconds
    Runnable notConnectedTask = () -> {
      Log.i(TAG, "notConnectedTask");
      Log.i(TAG, "sendBroadcast() DS1_DISCONNECTED");
      sendBroadcast(new Intent(DS1Service.DS1_DISCONNECTED));
    };
    mHandler.postDelayed(notConnectedTask, MESSAGE_TOKEN, Configuration.DS1_SERVICE_CONNECT_WAIT_TIMER);

    // Register a receiver for DS1 device notifications
    mDS1Receiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        Log.i(TAG, String.format("mDS1Service onReceive action %s", action));
        if(action.equals(DS1Service.DS1_CONNECTED)) {
          // Connected to the DS1 device
          mHandler.removeCallbacks(notConnectedTask);
          updateDS1DeviceConnectedText(true);
          onDS1DeviceConnected();
        }
        else if(action.equals(DS1Service.DS1_GOT_RESULT)) {
          // Received some data from the DS1 device
          onDS1DeviceData();
        }
        else if(action.equals(DS1Service.DS1_DISCONNECTED)) {
          // Lost connection with DS1 device
          if(!mDS1DeviceDisconnected) {
            // Make sure this is only invoked once
            mDS1DeviceDisconnected = true;
            updateDS1DeviceConnectedText(false);
            onDS1DeviceDisconnected();
          }
        }
      }
    };
    IntentFilter filter = new IntentFilter();
    filter.addAction(DS1Service.DS1_GOT_RESULT);
    filter.addAction(DS1Service.DS1_CONNECTED);
    filter.addAction(DS1Service.DS1_DISCONNECTED);
    registerReceiver(mDS1Receiver, filter, Context.RECEIVER_EXPORTED);

    // Bind a service connection to the DS1 service
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

        // Auto connect to the configured DS1 device
        if(mDS1Service != null) {
          if(!mDS1Service.isConnected()) {
            if(mSharedPreferences.getBoolean(getString(R.string.key_ds1_autoconnect), false)) {
              String autoConnectAddress = mSharedPreferences.getString(getString(R.string.key_ds1_autoconnect_address), "");
              // Auto-connect to the last selected device
              if(autoConnectAddress.length() != 0) {
                try {
                  mDS1ConnectedImage.setColorFilter(Color.GRAY);

                  Log.i(TAG, String.format("mDS1Service.connectTo() %s", autoConnectAddress));
                  mDS1Service.connectTo(autoConnectAddress);

                  mHandler.postDelayed(onDone, DS1ServiceActivity.MESSAGE_TOKEN, 1);
                  return;
                }
                catch(Exception e) {
                  Log.e(TAG, String.format("Exception connecting to %s", autoConnectAddress), e);
                }
              }
            }
          }
        }

        mHandler.postDelayed(onDone, DS1ServiceActivity.MESSAGE_TOKEN, 1);
      }

      @Override
      public void onServiceDisconnected(ComponentName n) {
        Log.i(TAG, "mDS1ServiceConnection.onServiceDisconnected");
      }
    };

    Log.i(TAG, "bindService() mDS1ServiceConnection");
    Intent ds1ServiceIntent = new Intent(this, ExtendedDS1Service.class);
    bindService(ds1ServiceIntent, mDS1ServiceConnection, BIND_AUTO_CREATE);
  }

  protected void onDS1DeviceConnected() {
    Log.i(TAG, "onDS1DeviceConnected");
    if(mDS1ServiceActive == 0) {
      mDS1ServiceActive = 2;
    }
  }

  protected void onDS1DeviceData() {
    Log.i(TAG, "onDS1DeviceData");
  }

  protected void onDS1DeviceDisconnected() {
    Log.i(TAG, "onDS1DeviceDisconnected");
    if(mDS1ServiceActive != 0) {
      mDS1ServiceActive = 0;
    }
  }

  private void refreshDS1Service() {
    Log.i(TAG, "refreshDS1Service");
    mHandler.removeCallbacksAndMessages(MESSAGE_TOKEN);
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
    mDS1DeviceDisconnected = false;
    Log.i(TAG, "refreshDS1Service bindDS1Service()");
    bindDS1Service(() -> {
      Log.i(TAG, "refreshDS1Service bindDS1Service.onDone");
    });
  }

  protected void scheduleRefreshDS1Service() {
    mHandler.removeCallbacks(mRefreshDS1DeviceTask);
    mHandler.postDelayed(mRefreshDS1DeviceTask, MESSAGE_TOKEN, Configuration.DS1_SERVICE_RECONNECT_TIMER);
  }

  @Override
  protected void onDestroy() {
    Log.i(TAG, "onDestroy");
    super.onDestroy();

    mHandler.removeCallbacksAndMessages(MESSAGE_TOKEN);

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

}
