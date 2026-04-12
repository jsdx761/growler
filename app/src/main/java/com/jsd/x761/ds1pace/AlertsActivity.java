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

package com.jsd.x761.ds1pace;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationAvailability;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.jsd.x761.ds1pace.R;
import com.nolimits.ds1library.DS1Service;
import com.nolimits.ds1library.ExtendedDS1Service;

import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * An activity that collects and presents a view of the relevant alerts.
 */
public class AlertsActivity extends DS1ServiceActivity {
  private static final String TAG = "ALERTS_ACTIVITY";
  public static final String MESSAGE_TOKEN = "ALERTS_ACTIVITY_MESSAGES";

  private final Handler mHandler = new Handler(Looper.getMainLooper());
  private boolean mDemoMode;
  private AlertsAdapter mAlertsAdapter;
  private ServiceConnection mSpeechServiceConnection;
  private SpeechService mSpeechService;
  private boolean mBoundSpeechService;
  private Runnable mClearDS1AlertsTask;
  private SharedPreferences mSharedPreferences;
  private Runnable mDebugBackgroundAlertsTask;
  private ImageView mLocationActiveImage;
  RecyclerView mAlertsRecyclerView;
  private FusedLocationProviderClient mLocationClient;
  private LocationRequest mLocationRequest;
  private LocationCallback mLocationCallback;
  private Runnable mOnGetInitialLocationTask;
  private Location mLastLocation;
  private Location mLocation;
  private Location mBearingLocation;
  private float mBearing = 0.0f;
  private boolean mLocationActive;
  private Runnable mLocationNotAvailableTask;
  private ImageView mNetworkConnectedImage;
  private boolean mNetworkConnected = true;
  private Runnable mNetworkCheckTask;
  private ExecutorService mNetworkCheckTaskExecutor;
  private boolean mDS1AlertsActive;
  private BroadcastReceiver mSimulateRadarReceiver;
  private final List<Alert> mSimulatedRadarAlerts = new ArrayList<>();
  private final List<Alert> mSimulatedReportAlerts = new ArrayList<>();
  private final List<Alert> mSimulatedAircraftAlerts = new ArrayList<>();
  private boolean mReportsEnabled;
  private ImageView mReportsActiveImage;
  private String mReportsSourceURL;
  private String mReportsSourceEnv;
  private String mReportsSourceName;
  private boolean mReportsPoliceEnabled;
  private boolean mReportsAccidentEnabled;
  private boolean mReportsHazardEnabled;
  private boolean mReportsJamEnabled;
  private String mReportsAPIKey;
  private boolean mReportsReminderEnabled;
  private int mReportsActive;
  private int mReportsConsecutiveFailures;
  private ReportsHttpFetchTask mReportsHttpFetchTask;
  private Runnable mCheckForReportsTask;
  private boolean mAircraftsEnabled;
  private ImageView mAircraftsActiveImage;
  private AircraftsDatabase mAircraftsDatabase;
  private Runnable mCheckForAircraftsTask;
  private String mAircraftsSourceURL;
  private int mAircraftsActive;
  private ExecutorService mAircraftsFetchTaskExecutor;
  private String mAircraftsUser;
  private String mAircraftsPassword;
  private boolean mAircraftsReminderEnabled;
  private boolean mUseForegroundService;
  private boolean mUseWakeLock;
  private long mLocationInterval;
  private PowerManager.WakeLock mWakeLock;
  private WifiManager.WifiLock mWifiLock;
  private ConnectivityManager.NetworkCallback mNetworkCallback;
  private int mNetworkChangeVersion;
  private Runnable mNetworkChangeRunnable;
  private View mEmptyStateText;
  private ImageButton mMuteButton;
  private boolean mMuted = false;

  @SuppressLint("MissingPermission")
  @Override
  protected void onCreate(Bundle b) {
    Log.i(TAG, "onCreate");
    super.onCreate(b);

    setTitle("Alerts");

    // Demo mode suppresses status announcements so they don't
    // interfere with the simulated alert announcements
    mDemoMode = getIntent().getBooleanExtra("demo", false);
    if(mDemoMode) {
      Log.i(TAG, "Demo mode active");
    }

    setContentView(R.layout.alerts_activity);
    mDS1ConnectedImage = findViewById(R.id.ds1ConnectedImage);
    mReportsActiveImage = findViewById(R.id.reportsActiveImage);
    mAircraftsActiveImage = findViewById(R.id.aircraftsActiveImage);
    mNetworkConnectedImage = findViewById(R.id.networkConnectedImage);
    mLocationActiveImage = findViewById(R.id.locationActiveImage);
    mAlertsRecyclerView = findViewById(R.id.alertsRecyclerView);
    mAlertsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
    mEmptyStateText = findViewById(R.id.emptyStateText);

    mMuteButton = findViewById(R.id.alertsMuteButton);
    mMuteButton.setImageResource(R.drawable.volume_up_24);
    mMuteButton.setOnClickListener(v -> {
      mMuted = !mMuted;
      mMuteButton.setImageResource(mMuted
        ? R.drawable.volume_off_24
        : R.drawable.volume_up_24);
      if(mMuted && mAlertsAdapter != null) {
        mAlertsAdapter.cancelAnnouncements();
      }
    });

    mSharedPreferences = getSharedPreferences(getString(R.string.preference_file_key), Context.MODE_PRIVATE);
    mReportsEnabled = mSharedPreferences.getBoolean(getString(R.string.key_reports_enabled), true);
    mReportsSourceURL = mSharedPreferences.getString(getString(R.string.key_reports_url), getString(R.string.default_reports_url));
    mReportsSourceEnv = mSharedPreferences.getString(getString(R.string.key_reports_env), getString(R.string.default_reports_env));
    mReportsPoliceEnabled = mSharedPreferences.getBoolean(getString(R.string.key_reports_police), true);
    mReportsAccidentEnabled = mSharedPreferences.getBoolean(getString(R.string.key_reports_accident), true);
    mReportsHazardEnabled = mSharedPreferences.getBoolean(getString(R.string.key_reports_hazard), false);
    mReportsJamEnabled = mSharedPreferences.getBoolean(getString(R.string.key_reports_jam), false);
    mReportsReminderEnabled = mSharedPreferences.getBoolean(getString(R.string.key_reports_reminder), true);
    SharedPreferences securePrefs = SecurePreferences.get(this);
    mReportsAPIKey = securePrefs.getString(getString(R.string.key_reports_api_key),
      mSharedPreferences.getString(getString(R.string.key_reports_api_key), getString(R.string.default_reports_api_key)));
    try {
      String[] parts = new URL(mReportsSourceURL).getHost().split("\\.");
      if(parts.length >= 2) {
        mReportsSourceName = parts[parts.length - 2];
        mReportsSourceName = mReportsSourceName.substring(0, 1).toUpperCase() + mReportsSourceName.substring(1).toLowerCase();
      }
      else {
        mReportsSourceName = "Waze";
      }
    }
    catch(Exception e) {
      mReportsSourceName = "Waze";
    }
    mAircraftsEnabled = mSharedPreferences.getBoolean(getString(R.string.key_aircrafts_enabled), true);
    mAircraftsSourceURL = mSharedPreferences.getString(getString(R.string.key_aircrafts_url), getString(R.string.default_aircrafts_url));
    mAircraftsUser = securePrefs.getString(getString(R.string.key_aircrafts_user),
      mSharedPreferences.getString(getString(R.string.key_aircrafts_user), getString(R.string.default_aircrafts_user)));
    if(mAircraftsUser.equals(getString(R.string.default_aircrafts_user))) {
      mAircraftsUser = "";
    }
    mAircraftsPassword = securePrefs.getString(getString(R.string.key_aircrafts_password),
      mSharedPreferences.getString(getString(R.string.key_aircrafts_password), getString(R.string.default_aircrafts_password)));
    if(mAircraftsPassword.equals(getString(R.string.default_aircrafts_password))) {
      mAircraftsPassword = "";
    }
    mAircraftsReminderEnabled = mSharedPreferences.getBoolean(getString(R.string.key_aircrafts_reminder), true);

    mUseForegroundService = mSharedPreferences.getBoolean(getString(R.string.key_location_foreground_service), true);
    mUseWakeLock = mSharedPreferences.getBoolean(getString(R.string.key_location_wake_lock), true);
    try {
      mLocationInterval = Long.parseLong(mSharedPreferences.getString(getString(R.string.key_location_interval), getString(R.string.default_location_interval)));
    }
    catch(NumberFormatException e) {
      mLocationInterval = 2000;
    }

    mLocationClient = LocationServices.getFusedLocationProviderClient(this);

    mNetworkCheckTaskExecutor = Executors.newSingleThreadExecutor();
    mReportsHttpFetchTask = new ReportsHttpFetchTask(mHandler);
    mAircraftsFetchTaskExecutor = Executors.newSingleThreadExecutor();

    // Bind to the speech service
    Log.i(TAG, "bindSpeechService()");
    bindSpeechService(() -> {
      Log.i(TAG, "bindSpeechService.onDone");

      // Register broadcast receiver for simulating alerts via adb
      IntentFilter simulateFilter = new IntentFilter();
      simulateFilter.addAction("com.jsd.x761.ds1pace.SIMULATE_RADAR");
      simulateFilter.addAction("com.jsd.x761.ds1pace.SIMULATE_RADAR_CLEAR");
      simulateFilter.addAction("com.jsd.x761.ds1pace.SIMULATE_REPORT");
      simulateFilter.addAction("com.jsd.x761.ds1pace.SIMULATE_REPORT_CLEAR");
      simulateFilter.addAction("com.jsd.x761.ds1pace.SIMULATE_AIRCRAFT");
      simulateFilter.addAction("com.jsd.x761.ds1pace.SIMULATE_AIRCRAFT_CLEAR");
      simulateFilter.addAction("com.jsd.x761.ds1pace.SET_VOICE");
      simulateFilter.addAction("com.jsd.x761.ds1pace.SET_PITCH");
      simulateFilter.addAction("com.jsd.x761.ds1pace.SET_RATE");
      simulateFilter.addAction("com.jsd.x761.ds1pace.LIST_VOICES");
      simulateFilter.addAction("com.jsd.x761.ds1pace.LIST_ALL_VOICES");
      simulateFilter.addAction("com.jsd.x761.ds1pace.SYNTHESIZE_TO_FILE");
      simulateFilter.addAction("com.jsd.x761.ds1pace.SYNTHESIZE_FILTERED");
      simulateFilter.addAction("com.jsd.x761.ds1pace.SET_VOICE_BACKEND");
      simulateFilter.addAction("com.jsd.x761.ds1pace.SET_VOICE_MODE");
      simulateFilter.addAction("com.jsd.x761.ds1pace.SET_TUNED_VOICE");
      simulateFilter.addAction("com.jsd.x761.ds1pace.SET_TUNED_PITCH");
      simulateFilter.addAction("com.jsd.x761.ds1pace.SET_TUNED_RATE");
      simulateFilter.addAction("com.jsd.x761.ds1pace.SET_TUNED_FILTER");
      simulateFilter.addAction("com.jsd.x761.ds1pace.SET_VOICE_BY_NAME");
      simulateFilter.addAction("com.jsd.x761.ds1pace.SET_PITCH_SILENT");
      simulateFilter.addAction("com.jsd.x761.ds1pace.SET_RATE_SILENT");
      simulateFilter.addAction("com.jsd.x761.ds1pace.SET_LOCATION");
      mSimulateRadarReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          Log.i(TAG, String.format("SimulateReceiver.onReceive %s", intent.getAction()));
          if(mAlertsAdapter == null) return;
          String action = intent.getAction();
          if("com.jsd.x761.ds1pace.SIMULATE_RADAR".equals(action)) {
            String band = intent.getStringExtra("band");
            float freq = intent.getFloatExtra("freq", 0.0f);
            float intensity = intent.getFloatExtra("intensity", 50.0f);
            float distance = intent.getFloatExtra("distance", 0.0f);
            int bearing = intent.getIntExtra("bearing", 0);
            if(band != null) {
              onSimulatedRadarData(band, freq, intensity, distance, bearing);
            }
          }
          else if("com.jsd.x761.ds1pace.SIMULATE_RADAR_CLEAR".equals(action)) {
            onSimulatedRadarClear();
          }
          else if("com.jsd.x761.ds1pace.SIMULATE_REPORT".equals(action)) {
            String type = intent.getStringExtra("type");
            String subType = intent.getStringExtra("subtype");
            String city = intent.getStringExtra("city");
            String street = intent.getStringExtra("street");
            double lat = intent.getFloatExtra("lat", Float.NaN);
            double lng = intent.getFloatExtra("lng", Float.NaN);
            if(type != null && !Float.isNaN((float)lat) && !Float.isNaN((float)lng)) {
              onSimulatedReportData(type, subType, city, street, lat, lng);
            }
          }
          else if("com.jsd.x761.ds1pace.SIMULATE_REPORT_CLEAR".equals(action)) {
            onSimulatedReportClear();
          }
          else if("com.jsd.x761.ds1pace.SIMULATE_AIRCRAFT".equals(action)) {
            String transponder = intent.getStringExtra("transponder");
            String type = intent.getStringExtra("type");
            String owner = intent.getStringExtra("owner");
            String manufacturer = intent.getStringExtra("manufacturer");
            double lat = intent.getFloatExtra("lat", 0.0f);
            double lng = intent.getFloatExtra("lng", 0.0f);
            float altitude = intent.getFloatExtra("altitude", 0.0f);
            onSimulatedAircraftData(transponder, type, owner, manufacturer, lat, lng, altitude);
          }
          else if("com.jsd.x761.ds1pace.SIMULATE_AIRCRAFT_CLEAR".equals(action)) {
            onSimulatedAircraftClear();
          }
          else if("com.jsd.x761.ds1pace.SET_VOICE".equals(action)) {
            int index = intent.getIntExtra("index", 0);
            mSpeechService.setVoice(index);
          }
          else if("com.jsd.x761.ds1pace.SET_PITCH".equals(action)) {
            float pitch = intent.getFloatExtra("pitch", Configuration.AUDIO_SPEECH_PITCH);
            mSpeechService.setPitch(pitch);
          }
          else if("com.jsd.x761.ds1pace.SET_RATE".equals(action)) {
            float rate = intent.getFloatExtra("rate", Configuration.AUDIO_SPEECH_RATE);
            mSpeechService.setRate(rate);
          }
          else if("com.jsd.x761.ds1pace.LIST_VOICES".equals(action)) {
            mSpeechService.listVoices();
          }
          else if("com.jsd.x761.ds1pace.LIST_ALL_VOICES".equals(action)) {
            mSpeechService.listAllVoices();
          }
          else if("com.jsd.x761.ds1pace.SYNTHESIZE_TO_FILE".equals(action)) {
            String text = intent.getStringExtra("text");
            String filename = intent.getStringExtra("filename");
            if(text != null && filename != null) {
              mSpeechService.synthesizeToFile(text, filename);
            }
          }
          else if("com.jsd.x761.ds1pace.SYNTHESIZE_FILTERED".equals(action)) {
            String text = intent.getStringExtra("text");
            String filename = intent.getStringExtra("filename");
            boolean network = "network".equals(intent.getStringExtra("mode"));
            if(text != null && filename != null) {
              mSpeechService.synthesizeFilteredToFile(text, filename, network);
            }
          }
          else if("com.jsd.x761.ds1pace.SET_VOICE_BACKEND".equals(action)) {
            String backend = intent.getStringExtra("backend");
            if("prerecorded".equals(backend)) {
              mSpeechService.setUsePreRecorded(true);
            } else {
              mSpeechService.setUsePreRecorded(false);
            }
          }
          else if("com.jsd.x761.ds1pace.SET_VOICE_MODE".equals(action)) {
            int mode = intent.getIntExtra("mode", Configuration.VOICE_MODE_SYSTEM);
            mSpeechService.setVoiceMode(mode);
          }
          else if("com.jsd.x761.ds1pace.SET_TUNED_VOICE".equals(action)) {
            String voice = intent.getStringExtra("voice");
            boolean network = "network".equals(intent.getStringExtra("mode"));
            if(voice != null) {
              mSpeechService.setTunedVoice(network, voice);
            }
          }
          else if("com.jsd.x761.ds1pace.SET_TUNED_PITCH".equals(action)) {
            float pitch = intent.getFloatExtra("pitch", Configuration.AUDIO_SPEECH_PITCH);
            boolean network = "network".equals(intent.getStringExtra("mode"));
            mSpeechService.setTunedPitch(network, pitch);
          }
          else if("com.jsd.x761.ds1pace.SET_TUNED_RATE".equals(action)) {
            float rate = intent.getFloatExtra("rate", Configuration.AUDIO_SPEECH_RATE);
            boolean network = "network".equals(intent.getStringExtra("mode"));
            mSpeechService.setTunedRate(network, rate);
          }
          else if("com.jsd.x761.ds1pace.SET_TUNED_FILTER".equals(action)) {
            String params = intent.getStringExtra("params");
            boolean network = "network".equals(intent.getStringExtra("mode"));
            if(params != null) {
              mSpeechService.setTunedFilterParams(network, params);
            }
          }
          else if("com.jsd.x761.ds1pace.SET_VOICE_BY_NAME".equals(action)) {
            String voice = intent.getStringExtra("voice");
            if(voice != null) {
              mSpeechService.setVoiceByName(voice);
            }
          }
          else if("com.jsd.x761.ds1pace.SET_PITCH_SILENT".equals(action)) {
            float pitch = intent.getFloatExtra("pitch", Configuration.AUDIO_SPEECH_PITCH);
            mSpeechService.setPitchSilent(pitch);
          }
          else if("com.jsd.x761.ds1pace.SET_RATE_SILENT".equals(action)) {
            float rate = intent.getFloatExtra("rate", Configuration.AUDIO_SPEECH_RATE);
            mSpeechService.setRateSilent(rate);
          }
          else if("com.jsd.x761.ds1pace.SET_LOCATION".equals(action)) {
            float lat = intent.getFloatExtra("lat", 0.0f);
            float lng = intent.getFloatExtra("lng", 0.0f);
            if(lat != 0.0f && lng != 0.0f) {
              Log.i(TAG, String.format("SET_LOCATION lat %f lng %f", lat, lng));
              mLocation = new Location("test");
              mLocation.setLatitude(lat);
              mLocation.setLongitude(lng);
              mLocation.setBearing(0);
              checkForReports(0);
            }
          }
        }
      };
      registerReceiver(mSimulateRadarReceiver, simulateFilter, Context.RECEIVER_EXPORTED);

      // Check if DS1 alerts are enabled
      if(Configuration.ENABLE_DS1_ALERTS) {
        if(mDS1ServiceEnabled) {
          mDS1AlertsActive = true;
        }
      }

      // Check if Waze reports are enabled
      if(Configuration.ENABLE_REPORTS) {
        if(mReportsEnabled) {
          mReportsActive = 1;
        }
      }

      // Check if aircraft recognition is enabled
      if(Configuration.ENABLE_AIRCRAFTS) {
        if(mAircraftsEnabled) {
          // Load aircrafts database
          mAircraftsDatabase = new AircraftsDatabase(AlertsActivity.this);
          if(mAircraftsDatabase.getInterestingAircrafts().size() != 0) {
            mAircraftsActive = 1;
          }
        }
      }

      if(!mDS1AlertsActive && mReportsActive == 0 && mAircraftsActive == 0) {
        Log.i(TAG, "no alert sources enabled");
        runOnUiThread(() -> {
          AlertDialog.Builder d = new AlertDialog.Builder(this);
          d.setMessage("No alert sources are enabled.\n\nPlease enable and configure some alert sources in Settings.");
          d.setTitle("Alerts");
          d.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
              mHandler.postDelayed(() -> {
                Intent intent = new Intent(AlertsActivity.this, SettingsMenuActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                finish();
              }, MESSAGE_TOKEN, 1);
            }
          });
          d.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
              finish();
            }
          });
          d.show();
        });
      }

      if(mDS1AlertsActive) {
        // Bind to the DS1 service
        Log.i(TAG, "bindDS1Service()");
        bindDS1Service(() -> {
          Log.i(TAG, "bindDS1Service.onDone");
          if(Configuration.DEBUG_TEST_DS1_ALERTS_TIMER != 0) {
            // Inject test background DS1 alerts every few seconds to help
            // test the app without having to use an actual DS1 device
            // everytime
            Log.i(TAG, "using test background DS1 alerts");
            mDebugBackgroundAlertsTask = () -> {
              try {
                onDS1DeviceData();
              }
              finally {
                for(int r = 1; r < Configuration.DEBUG_TEST_DS1_ALERTS_REPEAT_COUNT; r++) {
                  mHandler.postDelayed(() -> {
                    onDS1DeviceData();
                  }, MESSAGE_TOKEN, Configuration.DEBUG_TEST_DS1_ALERTS_REPEAT_TIMER * r);
                }
                mHandler.postDelayed(mDebugBackgroundAlertsTask, MESSAGE_TOKEN, Configuration.DEBUG_TEST_DS1_ALERTS_TIMER);
              }
            };
            mHandler.postDelayed(mDebugBackgroundAlertsTask, MESSAGE_TOKEN, 1);
          }
        });
      }

      if(mReportsActive != 0 || mAircraftsActive != 0) {
        mLocationActive = true;

        // Start foreground service to keep the app alive in the background
        if(mUseForegroundService) {
          Log.i(TAG, "startForegroundMode()");
          Intent speechServiceIntent = new Intent(AlertsActivity.this, SpeechService.class);
          startService(speechServiceIntent);
          mSpeechService.startForegroundMode();
        }

        // Acquire a wake lock to prevent the CPU from sleeping
        if(mUseWakeLock) {
          Log.i(TAG, "acquireWakeLock()");
          PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
          mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DS1Pace:LocationWakeLock");
          mWakeLock.acquire(4 * 60 * 60 * 1000L);

          // Acquire a WiFi lock to keep the WiFi radio alive
          WifiManager wm = (WifiManager)getSystemService(Context.WIFI_SERVICE);
          mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "DS1Pace:WifiLock");
          mWifiLock.acquire();
        }

        // Monitor network connectivity using ConnectivityManager for instant
        // detection of network changes
        ConnectivityManager cm = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        mNetworkCallback = new ConnectivityManager.NetworkCallback() {
          @Override
          public void onAvailable(@NonNull Network network) {
            Log.i(TAG, "NetworkCallback.onAvailable");
          }

          @Override
          public void onLost(@NonNull Network network) {
            Log.i(TAG, "NetworkCallback.onLost");
            if(mNetworkChangeRunnable != null) {
              mHandler.removeCallbacks(mNetworkChangeRunnable);
            }
            mNetworkChangeRunnable = () -> onNetworkChanged(false);
            mHandler.postDelayed(mNetworkChangeRunnable, MESSAGE_TOKEN, Configuration.NETWORK_LOST_DEBOUNCE_TIMER);
          }

          @Override
          public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities capabilities) {
            boolean validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            Log.i(TAG, String.format("NetworkCallback.onCapabilitiesChanged validated %b", validated));
            if(validated) {
              if(mNetworkChangeRunnable != null) {
                mHandler.removeCallbacks(mNetworkChangeRunnable);
              }
              mNetworkChangeRunnable = () -> onNetworkChanged(true);
              mHandler.postDelayed(mNetworkChangeRunnable, MESSAGE_TOKEN, Configuration.NETWORK_VALIDATED_DEBOUNCE_TIMER);
            }
          }
        };
        cm.registerDefaultNetworkCallback(mNetworkCallback);

        // Also run the periodic HTTP check as a fallback verification
        mNetworkCheckTask = () -> {
          isNetworkConnected(() -> {
            mHandler.postDelayed(mNetworkCheckTask, MESSAGE_TOKEN, Configuration.NETWORK_CONNECT_CHECK_TIMER);
          }, 0);
        };
        mHandler.postDelayed(mNetworkCheckTask, MESSAGE_TOKEN, 1);

        mOnGetInitialLocationTask = () -> {
          if(mReportsActive != 0) {
            // Regularly fetch Waze reports. The next poll is
            // scheduled after the fetch completes (not when it starts)
            // to avoid overlapping requests when the server is slow.
            mCheckForReportsTask = () -> {
              checkForReports(0);
            };
            mHandler.postDelayed(mCheckForReportsTask, MESSAGE_TOKEN, 1);
          }

          if(mAircraftsActive != 0) {
            // Regularly fetch aircrafts
            mCheckForAircraftsTask = () -> {
              try {
                checkForAircrafts(0);
              }
              finally {
                if(Configuration.DEBUG_INJECT_TEST_AIRCRAFTS != 0 || (mAircraftsUser.length() != 0 && mAircraftsPassword.length() != 0)) {
                  mHandler.postDelayed(mCheckForAircraftsTask, MESSAGE_TOKEN, Configuration.AIRCRAFTS_AUTHENTICATED_CHECK_TIMER);
                }
                else {
                  mHandler.postDelayed(mCheckForAircraftsTask, MESSAGE_TOKEN, Configuration.AIRCRAFTS_ANONYMOUS_CHECK_TIMER);
                }
              }
            };
            mHandler.postDelayed(mCheckForAircraftsTask, MESSAGE_TOKEN, 1);
          }
        };

        // Regularly get the current location
        mLocationCallback = new LocationCallback() {
          @Override
          public void onLocationResult(@NonNull LocationResult locationResult) {
            Log.i(TAG, String.format("locationCallback.onLocationResult %s", locationResult));
            Location location = null;
            if(locationResult != null) {
              location = locationResult.getLastLocation();
            }
            if(location == null) {
              // Schedule a location unavailable announcement after some delay
              // as the location may become available again in the meantime
              if(mLocationNotAvailableTask == null) {
                mLocationNotAvailableTask = () -> {
                  AlertsActivity.this.onLocationChanged(null);
                };
                mHandler.postDelayed(mLocationNotAvailableTask, MESSAGE_TOKEN, Configuration.LOCATION_AVAILABILITY_CHECK_TIMER);
              }
            }
            else {
              // Cancel any pending location unavailable announcement
              if(mLocationNotAvailableTask != null) {
                mHandler.removeCallbacks(mLocationNotAvailableTask);
                mLocationNotAvailableTask = null;
              }

              // Report the location
              AlertsActivity.this.onLocationChanged(location);
              if(mOnGetInitialLocationTask != null) {
                mHandler.postDelayed(mOnGetInitialLocationTask, MESSAGE_TOKEN, 1);
                mOnGetInitialLocationTask = null;
              }
            }
          }

          @Override
          public void onLocationAvailability(@NonNull LocationAvailability locationAvailability) {
            Log.i(TAG, String.format("locationCallback.onLocationAvailability %b", locationAvailability.isLocationAvailable()));
            if(!locationAvailability.isLocationAvailable()) {
              // Schedule a location unavailable announcement after some delay
              // as the location may become available again in the meantime
              if(mLocationNotAvailableTask == null) {
                mLocationNotAvailableTask = () -> {
                  AlertsActivity.this.onLocationChanged(null);
                };
                mHandler.postDelayed(mLocationNotAvailableTask, MESSAGE_TOKEN, Configuration.LOCATION_AVAILABILITY_CHECK_TIMER);
              }
            }
          }
        };

        Log.i(TAG, "locationclient.requestLocationUpdates()");
        mLocationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, mLocationInterval)
          .setMinUpdateIntervalMillis(mLocationInterval / 2)
          .setWaitForAccurateLocation(false)
          .build();
        mLocationClient.requestLocationUpdates(mLocationRequest, mLocationCallback, Looper.getMainLooper());
      }
    });
  }

  private void bindSpeechService(Runnable onDone) {
    Log.i(TAG, "bindSpeechService");
    // Bind to the speech service
    mSpeechServiceConnection = new ServiceConnection() {
      @Override
      public void onServiceConnected(ComponentName name, IBinder s) {
        Log.i(TAG, "mSpeechServiceConnection.onServiceConnected");
        mSpeechService = ((SpeechService.ThisBinder)s).getService();
        if(!mBoundSpeechService) {
          mBoundSpeechService = true;
          mAlertsAdapter = new AlertsAdapter(AlertsActivity.this, mSpeechService, mReportsSourceName);
          mAlertsAdapter.setReportsReminderEnabled(mReportsReminderEnabled);
          mAlertsAdapter.setAircraftsReminderEnabled(mAircraftsReminderEnabled);
          mAlertsRecyclerView.setAdapter(mAlertsAdapter);
          mHandler.postDelayed(onDone, MESSAGE_TOKEN, 1);
        }
      }

      @Override
      public void onServiceDisconnected(ComponentName name) {
        Log.i(TAG, "mSpeechServiceConnection.onServiceDisconnected");
      }
    };

    Log.i(TAG, "bindService() mSpeechServiceConnection");
    Intent speechServiceIntent = new Intent(this, SpeechService.class);
    bindService(speechServiceIntent, mSpeechServiceConnection, BIND_AUTO_CREATE);
  }

  public SpeechService getSpeechService() {
    return mSpeechService;
  }

  @Override
  protected void onDS1DeviceConnected() {
    Log.i(TAG, "onDS1DeviceConnected");
    // Configure the DS1 device to notify of alerts in the background
    // and to report settings as well
    mHandler.postDelayed(() -> {
      mDS1Service.enableAlertNotifications();
      mDS1Service.setBackgroundAlert(false);
    }, MESSAGE_TOKEN, Configuration.DS1_SERVICE_SETUP_TIMER);

    // Announce that the DS1 device is connected
    if(mSpeechService != null) {
      if(mDS1ServiceActive == 0) {
        announceStatus("status_radar_back_on", "Radar detector is back on");
      }
      else if(mDS1ServiceActive == 1) {
        announceStatus("status_radar_on", "Radar detector is on");
      }
    }
    super.onDS1DeviceConnected();
  }

  @Override
  protected void onDS1DeviceDisconnected() {
    Log.i(TAG, "onDS1DeviceDisconnected");
    // Announce that the DS1 device is disconnected
    if(mSpeechService != null && mDS1ServiceActive != 0) {
      announceStatus("status_radar_off", "Radar detector is off");
    }
    super.onDS1DeviceDisconnected();

    // Try to reconnect to the DS1 device
    scheduleRefreshDS1Service();
  }

  @Override
  protected void onResume() {
    Log.i(TAG, "onResume");
    super.onResume();

    // Re-read settings that may have changed while in a settings activity
    mReportsSourceURL = mSharedPreferences.getString(getString(R.string.key_reports_url), getString(R.string.default_reports_url));
    mReportsSourceEnv = mSharedPreferences.getString(getString(R.string.key_reports_env), getString(R.string.default_reports_env));
    mReportsPoliceEnabled = mSharedPreferences.getBoolean(getString(R.string.key_reports_police), true);
    mReportsAccidentEnabled = mSharedPreferences.getBoolean(getString(R.string.key_reports_accident), true);
    mReportsHazardEnabled = mSharedPreferences.getBoolean(getString(R.string.key_reports_hazard), false);
    mReportsJamEnabled = mSharedPreferences.getBoolean(getString(R.string.key_reports_jam), false);
    mReportsReminderEnabled = mSharedPreferences.getBoolean(getString(R.string.key_reports_reminder), true);
    SharedPreferences securePrefs = SecurePreferences.get(this);
    mReportsAPIKey = securePrefs.getString(getString(R.string.key_reports_api_key),
      mSharedPreferences.getString(getString(R.string.key_reports_api_key), getString(R.string.default_reports_api_key)));
    mAircraftsSourceURL = mSharedPreferences.getString(getString(R.string.key_aircrafts_url), getString(R.string.default_aircrafts_url));
    mAircraftsUser = securePrefs.getString(getString(R.string.key_aircrafts_user),
      mSharedPreferences.getString(getString(R.string.key_aircrafts_user), getString(R.string.default_aircrafts_user)));
    if(mAircraftsUser.equals(getString(R.string.default_aircrafts_user))) {
      mAircraftsUser = "";
    }
    mAircraftsPassword = securePrefs.getString(getString(R.string.key_aircrafts_password),
      mSharedPreferences.getString(getString(R.string.key_aircrafts_password), getString(R.string.default_aircrafts_password)));
    if(mAircraftsPassword.equals(getString(R.string.default_aircrafts_password))) {
      mAircraftsPassword = "";
    }
    mAircraftsReminderEnabled = mSharedPreferences.getBoolean(getString(R.string.key_aircrafts_reminder), true);
    if(mAlertsAdapter != null) {
      mAlertsAdapter.setReportsReminderEnabled(mReportsReminderEnabled);
      mAlertsAdapter.setAircraftsReminderEnabled(mAircraftsReminderEnabled);
    }

    // Re-check DS1 connection — DS1ScanActivity disconnects the BLE
    // connection when it's destroyed, so we need to reconnect here
    if(mDS1AlertsActive && mDS1Service != null && !mDS1Service.isConnected()) {
      Log.i(TAG, "onResume: DS1 disconnected, scheduling reconnect");
      scheduleRefreshDS1Service();
    }
  }

  @Override
  protected void onDestroy() {
    Log.i(TAG, "onDestroy");
    super.onDestroy();

    if(mSpeechService != null) {
      mSpeechService.stopSpeech();
    }

    if(mAlertsAdapter != null) {
      mAlertsAdapter.onDestroy();
    }

    mHandler.removeCallbacksAndMessages(MESSAGE_TOKEN);

    if(mSimulateRadarReceiver != null) {
      Log.i(TAG, "unregisterReceiver() mSimulateRadarReceiver");
      unregisterReceiver(mSimulateRadarReceiver);
    }
    if(mNetworkCallback != null) {
      Log.i(TAG, "unregisterNetworkCallback()");
      ConnectivityManager cm = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
      cm.unregisterNetworkCallback(mNetworkCallback);
    }
    if(mWifiLock != null && mWifiLock.isHeld()) {
      Log.i(TAG, "releaseWifiLock()");
      mWifiLock.release();
    }
    if(mWakeLock != null && mWakeLock.isHeld()) {
      Log.i(TAG, "releaseWakeLock()");
      mWakeLock.release();
    }
    if(mSpeechService != null && mUseForegroundService) {
      Log.i(TAG, "stopForegroundMode()");
      mSpeechService.stopForegroundMode();
    }
    if(mSpeechServiceConnection != null) {
      Log.i(TAG, "unbindService() mSpeechServiceConnection");
      unbindService(mSpeechServiceConnection);
    }
    if(mNetworkCheckTaskExecutor != null) {
      Log.i(TAG, "networkCheckTaskExecutor.shutdownNow()");
      mNetworkCheckTaskExecutor.shutdownNow();
    }
    if(mAircraftsFetchTaskExecutor != null) {
      Log.i(TAG, "aircraftsFetchTaskExecutor.shutdownNow()");
      mAircraftsFetchTaskExecutor.shutdownNow();
    }
    if(mReportsHttpFetchTask != null) {
      Log.i(TAG, "reportsHttpFetchTask.destroy()");
      mReportsHttpFetchTask.destroy();
    }
    if(mLocationClient != null && mLocationCallback != null) {
      Log.i(TAG, "locationclient.removeLocationUpdates()");
      mLocationClient.removeLocationUpdates(mLocationCallback);
    }
  }

  @Override
  protected void onDS1DeviceData() {
    Log.i(TAG, "onDS1DeviceData");
    List<Alert> alerts = new ArrayList<>();
    if(mDS1Service != null && mDS1Service.isConnected()) {
      // Collect alerts from the DS1 device
      List<DS1Service.RD_Alert> ds1Alerts = mDS1Service.getmAlerts();
      if(ds1Alerts != null) {
        for(DS1Service.RD_Alert ds1Alert : ds1Alerts) {
          if(ds1Alert.detected && !ds1Alert.muted) {
            alerts.add(Alert.fromDS1Alert(ds1Alert));
          }
        }
      }

      // Collect camera alerts from the DS1 AlertEntry list, which
      // includes distance and direction data not available in RD_Alert
      if(mDS1Service instanceof ExtendedDS1Service) {
        List<ExtendedDS1Service.CameraAlert> cameraAlerts = ((ExtendedDS1Service)mDS1Service).getCameraAlerts();
        for(ExtendedDS1Service.CameraAlert cameraAlert : cameraAlerts) {
          Alert alert = Alert.fromDS1CameraAlert(cameraAlert);
          // Replace any matching camera alert from RD_Alert with the
          // richer AlertEntry version that has distance and direction
          boolean replaced = false;
          for(int i = 0; i < alerts.size(); i++) {
            if(alerts.get(i).alertClass == alert.alertClass) {
              alerts.set(i, alert);
              replaced = true;
              break;
            }
          }
          if(!replaced) {
            alerts.add(alert);
          }
        }
      }
    }
    if(Configuration.DEBUG_INJECT_TEST_DS1_ALERTS != 0) {
      // Inject test alerts to help test the app without having to
      // use an actual DS1 device everytime
      Log.i(TAG, "injecting test DS1 alerts");
      DS1Service ds1Service = new DS1Service();
      int n = 0;
      for(String alert : Configuration.DEBUG_TEST_DS1_ALERTS) {
        if(n < Configuration.DEBUG_INJECT_TEST_DS1_ALERTS) {
          alerts.add(Alert.fromDS1Alert(ds1Service.new RD_Alert(alert)));
          n++;
        }
      }
    }

    // If no alerts detected, let the existing clear timer expire naturally
    // instead of immediately clearing, to avoid flickering when the signal
    // drops momentarily between device updates
    if(alerts.isEmpty()) {
      Log.i(TAG, "no alerts detected, waiting for clear timer");
      return;
    }

    List<Alert> newAlerts = new ArrayList<>();
    List<Alert> mAlerts = mAlertsAdapter.getRadarAlerts();
    for(Alert alert : alerts) {
      for(Alert mAlert : mAlerts) {
        // Detect repeating alerts, reuse the existing alert instead of
        // adding the repeated alert to avoid announcing the same alert
        // over and over
        if(alert.alertClass == mAlert.alertClass && alert.band == mAlert.band && alert.frequency == mAlert.frequency) {
          mAlert.direction = alert.direction;
          mAlert.intensity = alert.intensity;
          Log.i(TAG, "repeating alert");
          alert = mAlert;
          break;
        }
      }
      newAlerts.add(alert);
    }

    // Sort alerts by priority
    newAlerts.sort((o1, o2) -> o1.priority - o2.priority);

    // Clear alerts after a few seconds
    if(mClearDS1AlertsTask != null) {
      Log.i(TAG, "removeCallbacks() mClearDS1AlertsTask");
      mHandler.removeCallbacks(mClearDS1AlertsTask);
      mClearDS1AlertsTask = null;
    }

    mAlertsAdapter.setRadarAlerts(newAlerts, () -> startClearAlertsTask());
  }

  private void startClearAlertsTask() {
    if(mClearDS1AlertsTask != null) {
      Log.i(TAG, "removeCallbacks() mClearDS1AlertsTask");
      mHandler.removeCallbacks(mClearDS1AlertsTask);
      mClearDS1AlertsTask = null;
    }
    mClearDS1AlertsTask = () -> {
      Log.i(TAG, "setRadarAlerts(())");
      mSimulatedRadarAlerts.clear();
      mAlertsAdapter.setRadarAlerts(new ArrayList<>(), () -> {
      });
    };

    // Clear alerts after a few seconds without receiving any new alerts
    Log.i(TAG, "postDelayed() mClearDS1AlertsTask");
    mHandler.postDelayed(mClearDS1AlertsTask, MESSAGE_TOKEN, Configuration.DS1_ALERTS_CLEAR_TIMER);
  }

  private void onSimulatedRadarData(String band, float freq, float intensity, float distance, int bearing) {
    Log.i(TAG, String.format("onSimulatedRadarData band %s freq %f intensity %f distance %f bearing %d", band, freq, intensity, distance, bearing));

    Alert newAlert = Alert.fromSimulatedRadar(band, freq, intensity, distance, bearing);

    // Add or update the simulated alert in the accumulated list
    boolean found = false;
    for(int i = 0; i < mSimulatedRadarAlerts.size(); i++) {
      Alert existing = mSimulatedRadarAlerts.get(i);
      if(existing.alertClass == newAlert.alertClass && existing.band == newAlert.band && existing.frequency == newAlert.frequency) {
        existing.intensity = newAlert.intensity;
        existing.direction = newAlert.direction;
        existing.distance = newAlert.distance;
        existing.bearing = newAlert.bearing;
        found = true;
        break;
      }
    }
    if(!found) {
      mSimulatedRadarAlerts.add(newAlert);
    }

    // Process through the same pipeline as onDS1DeviceData
    List<Alert> newAlerts = new ArrayList<>();
    List<Alert> mAlerts = mAlertsAdapter.getRadarAlerts();
    for(Alert alert : mSimulatedRadarAlerts) {
      for(Alert mAlert : mAlerts) {
        if(alert.alertClass == mAlert.alertClass && alert.band == mAlert.band && alert.frequency == mAlert.frequency) {
          mAlert.direction = alert.direction;
          mAlert.intensity = alert.intensity;
          mAlert.distance = alert.distance;
          mAlert.bearing = alert.bearing;
          Log.i(TAG, "repeating simulated alert");
          alert = mAlert;
          break;
        }
      }
      newAlerts.add(alert);
    }

    newAlerts.sort((o1, o2) -> o1.priority - o2.priority);

    if(mClearDS1AlertsTask != null) {
      mHandler.removeCallbacks(mClearDS1AlertsTask);
      mClearDS1AlertsTask = null;
    }

    mAlertsAdapter.setRadarAlerts(newAlerts, () -> startClearAlertsTask());
  }

  private void onSimulatedRadarClear() {
    Log.i(TAG, "onSimulatedRadarClear");
    mSimulatedRadarAlerts.clear();

    if(mClearDS1AlertsTask != null) {
      mHandler.removeCallbacks(mClearDS1AlertsTask);
      mClearDS1AlertsTask = null;
    }

    mAlertsAdapter.setRadarAlerts(new ArrayList<>(), () -> {
    });
  }

  private void onSimulatedReportData(String type, String subType, String city, String street, double lat, double lng) {
    Log.i(TAG, String.format("onSimulatedReportData type %s city %s street %s", type, city, street));
    if(mLocation == null) return;

    Alert newAlert = Alert.fromSimulatedReport(type, subType, city, street, lat, lng);

    // Add or update the simulated report in the accumulated list
    boolean found = false;
    for(int i = 0; i < mSimulatedReportAlerts.size(); i++) {
      Alert existing = mSimulatedReportAlerts.get(i);
      if(existing.isSameReport(newAlert)) {
        mSimulatedReportAlerts.set(i, newAlert);
        found = true;
        break;
      }
    }
    if(!found) {
      mSimulatedReportAlerts.add(newAlert);
    }

    // Trigger a refresh -- the merge code in onReportsData will include
    // simulated reports after distance filtering
    List<Alert> currentReports = new ArrayList<>(mAlertsAdapter.getReportAlerts());
    onReportsData(currentReports);
  }

  private void onSimulatedReportClear() {
    Log.i(TAG, "onSimulatedReportClear");
    mSimulatedReportAlerts.clear();

    // Refresh with only real reports
    List<Alert> remaining = new ArrayList<>(mAlertsAdapter.getReportAlerts());
    onReportsData(remaining);
  }

  private void onSimulatedAircraftData(String transponder, String type, String owner, String manufacturer, double lat, double lng, float altitude) {
    Log.i(TAG, String.format("onSimulatedAircraftData transponder %s type %s owner %s", transponder, type, owner));
    if(mLocation == null) return;

    Alert newAlert = Alert.fromSimulatedAircraft(transponder, type, owner, manufacturer, lat, lng, altitude);

    // Add or update the simulated aircraft in the accumulated list
    boolean found = false;
    for(int i = 0; i < mSimulatedAircraftAlerts.size(); i++) {
      Alert existing = mSimulatedAircraftAlerts.get(i);
      if(existing.isSameAircraft(newAlert)) {
        mSimulatedAircraftAlerts.set(i, newAlert);
        found = true;
        break;
      }
    }
    if(!found) {
      mSimulatedAircraftAlerts.add(newAlert);
    }

    // Trigger a refresh -- the merge code in onAircraftsData will include
    // simulated aircraft after distance filtering
    List<Alert> currentAircrafts = new ArrayList<>(mAlertsAdapter.getAircraftAlerts());
    onAircraftsData(currentAircrafts);
  }

  private void onSimulatedAircraftClear() {
    Log.i(TAG, "onSimulatedAircraftClear");
    mSimulatedAircraftAlerts.clear();

    // Refresh with only real aircraft
    List<Alert> remaining = new ArrayList<>(mAlertsAdapter.getAircraftAlerts());
    onAircraftsData(remaining);
  }

  protected void onLocationChanged(Location location) {
    Log.i(TAG, "onLocationChanged");
    mHandler.postDelayed(() -> {
      if(location != null) {
        mLastLocation = mLocation;
        mLocation = location;

        boolean hasBearing = false;
        if(Configuration.DEBUG_USE_ZERO_BEARING) {
          mBearing = 0.0f;
          mLocation.setBearing(mBearing);
          hasBearing = true;
        }
        else if(!Configuration.USE_COMPUTED_LOCATION_BEARING) {
          if(mLocation.hasBearing()) {
            mBearing = mLocation.getBearing();
            hasBearing = true;
          }
          else {
            // Use the last recorded bearing if no new current bearing
            Log.i(TAG, String.format("using last known bearing %f", mBearing));
            mLocation.setBearing(mBearing);
          }
        }
        else {
          Location bearingRef = mBearingLocation != null ? mBearingLocation : mLastLocation;
          if(bearingRef != null) {
            if(Geospatial.getDistance(bearingRef, mLocation) > Configuration.COMPUTED_BEARING_DISTANCE_THRESHOLD) {
              mBearing = Geospatial.getBearing(bearingRef, mLocation);
              mBearingLocation = mLocation;
              hasBearing = true;
            }
            else {
              Log.i(TAG, String.format("using last known bearing %f", mBearing));
            }
            mLocation.setBearing(mBearing);
          }
          else {
            Log.i(TAG, String.format("using last known bearing %f", mBearing));
            mLocation.setBearing(mBearing);
          }
        }
        Log.i(
          TAG,
          String.format("location lat %f lng %f bearing %f", (float)mLocation.getLatitude(), (float)mLocation.getLongitude(), mLocation.getBearing()));

        if(Configuration.DEBUG_ANNOUNCE_VEHICLE_BEARING) {
          if(hasBearing) {
            DecimalFormat df = new DecimalFormat("0.#");
            mSpeechService.announceEvent(String.format("Vehicle bearing is %s", df.format(mBearing)), () -> {
            });
          }
          else {
            mSpeechService.announceEvent("No vehicle bearing", () -> {
            });
          }
        }

        // Refresh reports and aircrafts with the new location
        if(mReportsActive != 0) {
          List<Alert> updatedReports = new ArrayList<>();
          for(Alert alert : mAlertsAdapter.getReportAlerts()) {
            updatedReports.add(Alert.fromReport(mLocation, alert));
          }
          onReportsData(updatedReports);
        }
        if(mAircraftsActive != 0) {
          List<Alert> updatedAircrafts = new ArrayList<>();
          for(Alert alert : mAlertsAdapter.getAircraftAlerts()) {
            updatedAircrafts.add(Alert.fromAircraft(mLocation, alert));
          }
          onAircraftsData(updatedAircrafts);
        }
      }
      else {
        Log.i(TAG, "location not available");
        mLocation = null;
        mLastLocation = null;
        mBearingLocation = null;
      }

      // Announce location availability changes
      if(location == null) {
        mLocationActiveImage.setColorFilter(getColor(R.color.status_icon_inactive));
        if(mLocationActive) {
          mLocationActive = false;
          announceStatus("status_location_off", "Location is off");
        }
      }
      else {
        mLocationActiveImage.setColorFilter(getColor(R.color.status_icon_active));
        if(!mLocationActive) {
          mLocationActive = true;
          announceStatus("status_location_back_on", "Location is back on");
        }
      }
    }, MESSAGE_TOKEN, 1);
  }

  private void isNetworkConnected(Runnable onDone, int retryCount) {
    Log.i(TAG, "isNetworkConnected");
    final int version = mNetworkChangeVersion;
    Runnable networkCheckTask = () -> {
      boolean connected;
      HttpURLConnection connection = null;
      try {
        // Doing this is actually more reliable than checking for an active
        // network on the Android connectivity manager
        // Android's standard connectivity check endpoint — returns 204 with no body,
        // lightweight and reliable, same URL used by the OS captive portal detection
        URL url = new URL("https://connectivitycheck.gstatic.com/generate_204");
        Log.i(TAG, String.format("openConnection() %s", url));
        connection = (HttpURLConnection)url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Connection", "close");
        connection.setConnectTimeout(Configuration.NETWORK_CONNECT_TIMEOUT);
        connection.setReadTimeout(Configuration.NETWORK_CONNECT_TIMEOUT);
        connection.setInstanceFollowRedirects(false);
        connection.connect();
        int responseCode = connection.getResponseCode();
        Log.i(TAG, String.format("connection responseCode %d", responseCode));
        connected = responseCode == 204;
      }
      catch(Exception e) {
        Log.e(TAG, String.format("Exception %s", e));
        connected = false;
      }
      finally {
        if(connection != null) {
          connection.disconnect();
        }
      }
      final boolean networkConnected = connected;
      Log.i(TAG, String.format("isNetworkConnected %b", networkConnected));

      if(!networkConnected && retryCount < Configuration.NETWORK_CONNECT_RETRY_COUNT) {
        // Retry a few times to work around short lived connectivity issues
        Log.i(TAG, "post retry isNetworkConnected()");
        mHandler.postDelayed(() -> {
          isNetworkConnected(onDone, retryCount + 1);
        }, MESSAGE_TOKEN, Configuration.NETWORK_CONNECT_RETRY_TIMER);
        return;
      }

      mHandler.postDelayed(() -> {
        if(mNetworkChangeVersion != version) {
          // ConnectivityManager handled a state change since this check
          // started, discard this stale result
          onDone.run();
          return;
        }
        onNetworkChanged(networkConnected);
        onDone.run();
      }, MESSAGE_TOKEN, 1);
    };
    mNetworkCheckTaskExecutor.execute(networkCheckTask);
  }

  private void onNetworkChanged(boolean connected) {
    Log.i(TAG, String.format("onNetworkChanged %b", connected));
    mNetworkChangeVersion++;
    if(connected) {
      mNetworkConnectedImage.setColorFilter(getColor(R.color.status_icon_active));
    }
    else {
      mNetworkConnectedImage.setColorFilter(getColor(R.color.status_icon_inactive));
    }
    if(!connected && mNetworkConnected) {
      mNetworkConnected = false;
      announceStatus("status_network_offline", "Network is offline, alerts are paused");
      // Silently deactivate alert subsystems without individual announcements
      if(mCheckForReportsTask != null && mReportsActive != 0) {
        mReportsActive = 0;
        mReportsActiveImage.setColorFilter(getColor(R.color.status_icon_inactive));
      }
      if(mCheckForAircraftsTask != null && mAircraftsActive != 0) {
        mAircraftsActive = 0;
        mAircraftsActiveImage.setColorFilter(getColor(R.color.status_icon_inactive));
      }
    }
    else if(connected && !mNetworkConnected) {
      mNetworkConnected = true;
      announceStatus("status_network_online", "Network is back online");
      // Immediately re-check reports and aircraft alerts
      if(mCheckForReportsTask != null) {
        checkForReports(0);
      }
      if(mCheckForAircraftsTask != null) {
        checkForAircrafts(0);
      }
    }
    // Reschedule the periodic HTTP check to avoid stale results
    if(mNetworkCheckTask != null) {
      mHandler.removeCallbacks(mNetworkCheckTask);
      mHandler.postDelayed(mNetworkCheckTask, MESSAGE_TOKEN, Configuration.NETWORK_CONNECT_CHECK_TIMER);
    }
  }

  private void scheduleNextReportsPoll() {
    if(mCheckForReportsTask != null) {
      mHandler.removeCallbacks(mCheckForReportsTask);
      mHandler.postDelayed(mCheckForReportsTask, MESSAGE_TOKEN, Configuration.REPORTS_CHECK_TIMER);
    }
  }

  private void checkForReports(int retryCount) {
    Log.i(TAG, String.format("checkForReports retryCount %d", retryCount));

    // Fetch Waze reports in a radius around the current location
    if(mNetworkConnected) {
      if(mLocation != null) {
        Log.i(TAG, "reportsHttpFetchTask.fetch()");
        mReportsHttpFetchTask.fetch(mReportsSourceURL, mReportsSourceEnv, mReportsAPIKey, mLocation,
          mReportsPoliceEnabled, mReportsAccidentEnabled, mReportsHazardEnabled, mReportsJamEnabled,
          reports -> {
            if(reports == null) {
              Log.i(TAG, "reportsHttpFetchTask.onDone null reports");
              if(retryCount < Configuration.REPORTS_CHECK_RETRY_COUNT) {
                // Retry after a delay, starting the timer AFTER the
                // response arrives to avoid overlapping requests
                Log.i(TAG, "post retry checkForReports()");
                mHandler.postDelayed(() -> {
                  checkForReports(retryCount + 1);
                }, MESSAGE_TOKEN, Configuration.REPORTS_CHECK_RETRY_TIMER);
              }
              else {
                onReportsData(null);
                // Schedule next poll after retry exhaustion
                scheduleNextReportsPoll();
              }
            }
            else {
              Log.i(TAG, String.format("reportsHttpFetchTask.onDone %d reports", reports.size()));
              onReportsData(reports);
              // Schedule next poll after successful fetch
              scheduleNextReportsPoll();
            }
          });
      }
      else {
        onReportsData(null);
        scheduleNextReportsPoll();
      }
    }
    else {
      onReportsData(null);
      scheduleNextReportsPoll();
    }
  }

  protected void onReportsData(List<Alert> reports) {
    if(reports == null) {
      mReportsConsecutiveFailures++;
      if(mReportsConsecutiveFailures >= Configuration.REPORTS_OFF_DEBOUNCE_COUNT) {
        mReportsActiveImage.setColorFilter(getColor(R.color.status_icon_inactive));
        if(mReportsActive != 0) {
          mReportsActive = 0;
          announceStatus("status_waze_off", "Waze alerts are off");
        }
      }
      return;
    }
    else {
      mReportsConsecutiveFailures = 0;
      mReportsActiveImage.setColorFilter(getColor(R.color.status_icon_active));
      if(mReportsActive == 0) {
        mReportsActive = 2;
        announceStatus("status_waze_back_on", "Waze alerts are back on");
      }
      else if(mReportsActive == 1) {
        mReportsActive = 2;
        announceStatus("status_waze_on", "Waze alerts are on");
      }
    }

    mHandler.postDelayed(() -> {
      // Filter out reports beyond configured distance
      List<Alert> inRangeReports = new ArrayList<>();
      for(Alert report : reports) {
        if(report.distance <= Configuration.REPORTS_MAX_DISTANCE) {
          inRangeReports.add(report);
        }
      }

      // Filter out duplicate reports
      List<Alert> uniqueReports = new ArrayList<>();
      for(Alert report : inRangeReports) {
        boolean duplicate = false;
        for(Alert uniqueReport : uniqueReports) {
          if(report.isDuplicateReport(uniqueReport)) {
            duplicate = true;
            break;
          }
        }
        if(!duplicate) {
          uniqueReports.add(report);
        }
      }

      // Merge in simulated reports that aren't duplicates
      if(mLocation != null) {
        for(Alert simReport : mSimulatedReportAlerts) {
          Alert simWithLocation = Alert.fromReport(mLocation, simReport);
          boolean duplicate = false;
          for(Alert uniqueReport : uniqueReports) {
            if(simWithLocation.isSameReport(uniqueReport) || simWithLocation.isDuplicateReport(uniqueReport)) {
              duplicate = true;
              break;
            }
          }
          if(!duplicate) {
            uniqueReports.add(simWithLocation);
          }
        }
      }

      // Update existing reports with their new position, matching by
      // exact location first, then falling back to duplicate detection
      // to handle coordinate drift across fetches
      List<Alert> newReports = new ArrayList<>();
      List<Alert> mReports = mAlertsAdapter.getReportAlerts();
      for(Alert report : uniqueReports) {
        for(Alert mReport : mReports) {
          if(report.isSameReport(mReport) || report.isDuplicateReport(mReport)) {
            Log.i(TAG, String.format("existing report with new distance %f", report.distance));
            mReport.distance = report.distance;
            mReport.bearing = report.bearing;
            mReport.priority = report.priority;
            report = mReport;
            break;
          }
        }
        newReports.add(report);
      }

      // Sort final list of reports by priority
      newReports.sort(Comparator.comparingInt(o -> o.priority));

      mAlertsAdapter.setReportAlerts(newReports, () -> {
      });
    }, MESSAGE_TOKEN, 1);
  }

  private void checkForAircrafts(int retryCount) {
    Log.i(TAG, "checkForAircrafts");

    // Fetch aircraft state vectors in a radius around the current location
    if(mNetworkConnected) {
      if(mLocation != null) {
        AircraftsFetchTask aircraftsFetchTask = new AircraftsFetchTask(mAircraftsSourceURL, mAircraftsUser, mAircraftsPassword, mAircraftsDatabase,
          mLocation) {
          @Override
          protected void onDone(List<Alert> aircrafts) {
            runOnUiThread(() -> {
              if(aircrafts == null) {
                Log.i(TAG, "aircraftsFetchTask.onDone null aircraft state vectors");
                if(retryCount < Configuration.AIRCRAFTS_CHECK_RETRY_COUNT) {
                  // Retry a few times before giving up, to work around short
                  // lived connectivity issues
                  Log.i(TAG, "post retry checkForAircrafts()");
                  mHandler.postDelayed(() -> {
                    checkForAircrafts(retryCount + 1);
                  }, MESSAGE_TOKEN, Configuration.AIRCRAFTS_CHECK_RETRY_TIMER);
                }
                else {
                  onAircraftsData(null);
                }
              }
              else {
                Log.i(TAG, String.format("aircraftsFetchTask.onDone %d aircraft state vectors", aircrafts.size()));
                onAircraftsData(aircrafts);
              }
            });
          }
        };

        Log.i(TAG, "aircraftsFetchTask.execute()");
        mAircraftsFetchTaskExecutor.execute(aircraftsFetchTask);
      }
      else {
        onAircraftsData(null);
      }
    }
    else {
      onAircraftsData(null);
    }
  }

  protected void onAircraftsData(List<Alert> aircrafts) {
    if(aircrafts == null) {
      mAircraftsActiveImage.setColorFilter(getColor(R.color.status_icon_inactive));
      if(mAircraftsActive != 0) {
        mAircraftsActive = 0;
        announceStatus("status_aircraft_off", "Aircraft alerts are off");
      }
      return;
    }
    else {
      mAircraftsActiveImage.setColorFilter(getColor(R.color.status_icon_active));
      if(mAircraftsActive == 0) {
        mAircraftsActive = 2;
        announceStatus("status_aircraft_back_on", "Aircraft alerts are back on");
      }
      else if(mAircraftsActive == 1) {
        mAircraftsActive = 2;
        announceStatus("status_aircraft_on", "Aircraft alerts are on");
      }
    }

    mHandler.postDelayed(() -> {
      // Filter out reports beyond configured distance
      List<Alert> inRangeAircrafts = new ArrayList<>();
      for(Alert aircraft : aircrafts) {
        if(aircraft.distance <= Configuration.AIRCRAFTS_MAX_DISTANCE) {
          inRangeAircrafts.add(aircraft);
        }
      }

      // Merge in simulated aircraft that aren't duplicates
      if(mLocation != null) {
        for(Alert simAircraft : mSimulatedAircraftAlerts) {
          Alert simWithLocation = Alert.fromAircraft(mLocation, simAircraft);
          boolean duplicate = false;
          for(Alert inRangeAircraft : inRangeAircrafts) {
            if(simWithLocation.isSameAircraft(inRangeAircraft)) {
              duplicate = true;
              break;
            }
          }
          if(!duplicate) {
            inRangeAircrafts.add(simWithLocation);
          }
        }
      }

      // Update existing aircraft state vectors with their new position
      List<Alert> newAircrafts = new ArrayList<>();
      List<Alert> mAircrafts = mAlertsAdapter.getAircraftAlerts();
      for(Alert aircraft : inRangeAircrafts) {
        for(Alert mAircraft : mAircrafts) {
          if(aircraft.isSameAircraft(mAircraft)) {
            Log.i(TAG, String.format("existing aircraft state vector with new distance %f", aircraft.distance));
            mAircraft.distance = aircraft.distance;
            mAircraft.latitude = aircraft.latitude;
            mAircraft.longitude = aircraft.longitude;
            mAircraft.bearing = aircraft.bearing;
            aircraft = mAircraft;
            break;
          }
        }
        newAircrafts.add(aircraft);
      }

      // Sort aircraft state vectors by priority
      newAircrafts.sort(Comparator.comparingInt(o -> o.priority));

      mAlertsAdapter.setAircraftAlerts(newAircrafts, () -> {
      });
    }, MESSAGE_TOKEN, 1);
  }

  /**
   * Announce a status event, using pre-recorded segments when available
   * or falling back to system TTS. The engine decision is deferred until
   * the speech service is fully ready so the configured voice backend is
   * used even for announcements queued during startup.
   */
  private void announceStatus(String segmentId, String ttsText) {
    if(mDemoMode) return;
    mSpeechService.isReady(() -> {
      if(mSpeechService.usePreRecorded()) {
        mSpeechService.announceSegments(SegmentBuilder.buildStatusSegments(segmentId), () -> {});
      }
      else {
        mSpeechService.announceEvent(ttsText, () -> {});
      }
    });
  }

  public boolean isMuted() {
    return mMuted;
  }

  public void updateEmptyState(int itemCount) {
    if(mEmptyStateText != null) {
      mEmptyStateText.setVisibility(itemCount == 0 ? View.VISIBLE : View.GONE);
    }
  }

  public void onSettingsClick(View v) {
    Log.i(TAG, "onSettingsClick");
    Intent intent = new Intent(this, SettingsMenuActivity.class);
    startActivity(intent);
  }
}
