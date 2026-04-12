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

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.jsd.x761.ds1pace.R;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A view adapter that displays and announce a list of alerts.
 */
public class AlertsAdapter extends RecyclerView.Adapter<AlertsAdapter.ViewHolder> {
  private static final String TAG = "ALERTS_ADAPTER";
  public static final String MESSAGE_TOKEN = AlertsActivity.MESSAGE_TOKEN;

  private static final int ANNOUNCEMENT_NONE = 0;
  private static final int ANNOUNCEMENT_RADAR = 1;
  private static final int ANNOUNCEMENT_OTHER = 2;

  private final AlertsActivity mActivity;
  private final SpeechService mSpeechService;
  private final Handler mHandler = new Handler(Looper.getMainLooper());
  private final List<Alert> mRadarAlerts = new ArrayList<>();
  private final List<Alert> mReportAlerts = new ArrayList<>();
  private final List<Alert> mAircraftAlerts = new ArrayList<>();
  private final List<Alert> mItems = new ArrayList<>();
  private Runnable mRadarReminderTask;
  private boolean mRadarReminderRunning = false;
  private int mAnnouncementType = ANNOUNCEMENT_NONE;
  private int mAnnouncementGeneration = 0;
  private final List<Alert> mCurrentAnnounces = new ArrayList<>();
  private String mCurrentAnnouncementCategory = null;
  private final List<Alert> mInterruptedAnnounces = new ArrayList<>();
  private String mInterruptedAnnouncementCategory = null;
  private Runnable mReportsReminderTask;
  private Runnable mAircraftsReminderTask;
  private final String mReportsSourceName;
  private boolean mReportsReminderEnabled = false;
  private boolean mAircraftsReminderEnabled = false;
  private boolean mForceAnnounce = false;
  private boolean mReportsAllClear = true;
  private boolean mAircraftsAllClear = true;
  private Runnable mAllClearTask;

  public AlertsAdapter(AlertsActivity activity, SpeechService speechService, String reportsSourceName) {
    mActivity = activity;
    mSpeechService = speechService;
    mReportsSourceName = reportsSourceName;

    // Play earcon reminders at regular intervals while radar alerts are active
    // instead of tying earcon play to each radar event reception
    mRadarReminderTask = () -> {
      if(mRadarAlerts.size() > 0 && mAnnouncementType == ANNOUNCEMENT_NONE) {
        List<Alert> announces = new ArrayList<>();
        for(Alert alert : mRadarAlerts) {
          if(!alert.muted) {
            announces.add(alert);
          }
        }
        if(announces.size() > 0) {
          Log.i(TAG, "playAlertAnnounce() radar reminder pos 0");
          AlertsAdapter.this.playAlertAnnounce(
            announces, "radar-reminder", 0, false, 0, Configuration.DS1_ALERTS_MAX_EARCON_ANNOUNCES, (audioFocus) -> {
              if(audioFocus) {
                Log.i(TAG, "abandonAudioFocus()");
                mSpeechService.abandonAudioFocus(() -> {
                  Log.i(TAG, "radarReminder.onDone.run()");
                });
              }
            });
        }
      }
      if(mRadarReminderRunning) {
        mHandler.postDelayed(mRadarReminderTask, MESSAGE_TOKEN, Configuration.DS1_ALERTS_REMINDER_TIMER);
      }
    };

    // Play reminders regularly while there are active reports and aircraft
    // state vectors
    mReportsReminderTask = () -> {
      if(mReportAlerts.size() > 0 && mAnnouncementType == ANNOUNCEMENT_NONE) {
        if(mReportsReminderEnabled) {
          List<Alert> announces = new ArrayList<>();
          for(Alert report : mReportAlerts) {
            announces.add(report);
          }

          mAnnouncementType = ANNOUNCEMENT_OTHER;
          mCurrentAnnounces.clear();
          mCurrentAnnounces.addAll(announces);
          mCurrentAnnouncementCategory = "report";
          mForceAnnounce = true;

          Log.i(TAG, "playAlertAnnounce() report reminder pos 0");
          AlertsAdapter.this.playAlertAnnounce(
            announces, "report-reminder", 0, false, Configuration.REPORTS_MAX_SPEECH_ANNOUNCES, Configuration.REPORTS_MAX_EARCON_ANNOUNCES, (audioFocus) -> {

              mAnnouncementType = ANNOUNCEMENT_NONE;
              mCurrentAnnounces.clear();
              mCurrentAnnouncementCategory = null;
              mForceAnnounce = false;

              if(audioFocus) {
                Log.i(TAG, "abandonAudioFocus()");
                mSpeechService.abandonAudioFocus(() -> {
                  Log.i(TAG, "reportsReminder.onDone.run()");
                });
              }
            });
        }
        else {
          List<Alert> announces = new ArrayList<>();
          announces.add(mReportAlerts.get(0));

          Log.i(TAG, "playAlertAnnounce() report pos 0");
          AlertsAdapter.this.playAlertAnnounce(
            announces, "report", 0, false, 0, 1, (audioFocus) -> {

              if(audioFocus) {
                Log.i(TAG, "abandonAudioFocus()");
                mSpeechService.abandonAudioFocus(() -> {
                  Log.i(TAG, "setReportAlerts.onDone.run()");
                });
              }
            });
        }
      }
      mHandler.postDelayed(mReportsReminderTask, MESSAGE_TOKEN, Configuration.REPORTS_REMINDER_TIMER);
    };
    mHandler.postDelayed(mReportsReminderTask, MESSAGE_TOKEN, Configuration.REPORTS_REMINDER_TIMER);

    mAircraftsReminderTask = () -> {
      if(mAircraftAlerts.size() > 0 && mAnnouncementType == ANNOUNCEMENT_NONE) {
        if(mAircraftsReminderEnabled) {
          List<Alert> announces = new ArrayList<>();
          for(Alert aircraft : mAircraftAlerts) {
            announces.add(aircraft);
          }

          mAnnouncementType = ANNOUNCEMENT_OTHER;
          mCurrentAnnounces.clear();
          mCurrentAnnounces.addAll(announces);
          mCurrentAnnouncementCategory = "aircraft";
          mForceAnnounce = true;

          Log.i(TAG, "playAlertAnnounce() aircraft reminder pos 0");
          AlertsAdapter.this.playAlertAnnounce(
            announces, "aircraft-reminder", 0, false, Configuration.AIRCRAFTS_MAX_SPEECH_ANNOUNCES, Configuration.AIRCRAFTS_MAX_EARCON_ANNOUNCES, (audioFocus) -> {

              mAnnouncementType = ANNOUNCEMENT_NONE;
              mCurrentAnnounces.clear();
              mCurrentAnnouncementCategory = null;
              mForceAnnounce = false;

              if(audioFocus) {
                Log.i(TAG, "abandonAudioFocus()");
                mSpeechService.abandonAudioFocus(() -> {
                  Log.i(TAG, "aircraftsReminder.onDone.run()");
                });
              }
            });
        }
        else {
          List<Alert> announces = new ArrayList<>();
          announces.add(mAircraftAlerts.get(0));

          Log.i(TAG, "playAlertAnnounce() aircraft pos 0");
          AlertsAdapter.this.playAlertAnnounce(
            announces, "aircraft", 0, false, 0, 1, (audioFocus) -> {

              if(audioFocus) {
                Log.i(TAG, "abandonAudioFocus()");
                mSpeechService.abandonAudioFocus(() -> {
                  Log.i(TAG, "setReportAlerts.onDone.run()");
                });
              }
            });
        }
      }
      mHandler.postDelayed(mAircraftsReminderTask, MESSAGE_TOKEN, Configuration.AIRCRAFTS_REMINDER_TIMER);
    };
    mHandler.postDelayed(mAircraftsReminderTask, MESSAGE_TOKEN, Configuration.AIRCRAFTS_REMINDER_TIMER);

    mAllClearTask = () -> {
      if(mReportAlerts.size() == 0 && !mReportsAllClear) {
        mReportsAllClear = true;
        if(mSpeechService.usePreRecorded()) {
          mSpeechService.announceSegments(SegmentBuilder.buildAllClearSegments(false), () -> {});
        }
        else {
          mSpeechService.announceEvent("Waze alerts are all clear now", () -> {});
        }
      }
      if(mAircraftAlerts.size() == 0 && !mAircraftsAllClear) {
        mAircraftsAllClear = true;
        if(mSpeechService.usePreRecorded()) {
          mSpeechService.announceSegments(SegmentBuilder.buildAllClearSegments(true), () -> {});
        }
        else {
          mSpeechService.announceEvent("Aircraft alerts are all clear now", () -> {});
        }
      }
      mHandler.postDelayed(mAllClearTask, MESSAGE_TOKEN, Configuration.ALL_CLEAR_REMINDER_TIMER);
    };
    mHandler.postDelayed(mAllClearTask, MESSAGE_TOKEN, Configuration.ALL_CLEAR_REMINDER_TIMER);
  }

  @NonNull
  @Override
  public ViewHolder onCreateViewHolder(ViewGroup vg, int vt) {
    Log.i(TAG, "onCreateViewHolder");
    View v = LayoutInflater.from(vg.getContext()).inflate(R.layout.alert_item, vg, false);
    return new ViewHolder(v);
  }

  @Override
  public void onBindViewHolder(@NonNull ViewHolder vh, int pos) {
    Log.i(TAG, String.format("onBindViewHolder pos %d", pos));

    Alert alert = mItems.get(pos);

    // Show the class of alert
    String alertClass = "";
    switch(alert.alertClass) {
      case Alert.ALERT_CLASS_RADAR:
        alertClass = "Radar";
        break;
      case Alert.ALERT_CLASS_LASER:
        alertClass = "Laser";
        break;
      case Alert.ALERT_CLASS_SPEED_CAM:
        alertClass = "Speed Cam";
        break;
      case Alert.ALERT_CLASS_RED_LIGHT_CAM:
        alertClass = "Red Light Cam";
        break;
      case Alert.ALERT_CLASS_USER_MARK:
        alertClass = "User Mark";
        break;
      case Alert.ALERT_CLASS_LOCKOUT:
        alertClass = "Lockout";
        break;
      case Alert.ALERT_CLASS_REPORT:
        alertClass = alert.getDisplayName();
        break;
      case Alert.ALERT_CLASS_AIRCRAFT:
        alertClass = alert.type;
        break;
    }

    if(alert.alertClass == Alert.ALERT_CLASS_RADAR) {
      // For radar alerts, show band as the primary label, frequency as
      // secondary detail, and signal strength — DS1 isn't directional
      // so bearing is not shown
      String band = switch(alert.band) {
        case 0 -> "X Band";
        case 1 -> "K Band";
        case 2 -> "Ka Band";
        case 3 -> "Pop K Band";
        case 4 -> "MRCD";
        case 5 -> "MRCT";
        case 6 -> "GT3";
        case 7 -> "GT4";
        default -> "Radar";
      };
      vh.typeText.setText(band);
      vh.strengthProgressBar.setProgress((int)alert.intensity);
      vh.bearingText.setText("");

      if(alert.frequency < 1) {
        vh.banddOrLocationText.setText("");
        vh.frequencyOrDistanceText.setText("");
      }
      else {
        DecimalFormat df = new DecimalFormat("0.#");
        vh.banddOrLocationText.setText(String.format("%s GHz", df.format(alert.frequency)));
        vh.frequencyOrDistanceText.setText("");
      }
    }
    else if(alert.alertClass == Alert.ALERT_CLASS_LASER) {
      // For laser alerts, show max intensity — DS1 isn't directional
      // so bearing is not shown
      vh.typeText.setText(alertClass);
      vh.strengthProgressBar.setProgress(100);
      vh.bearingText.setText("");
      vh.frequencyOrDistanceText.setText("");
      vh.banddOrLocationText.setText("");
    }
    else if(alert.alertClass == Alert.ALERT_CLASS_REPORT) {
      // For crow-sourced reports, show the report direction in clock bearing
      // format, the distance and the reported street or city
      vh.typeText.setText(alertClass);
      vh.frequencyOrDistanceText.setText("");
      vh.bearingText.setText(String.format("%d o'clock", alert.bearing));
      vh.strengthProgressBar.setProgress(Math.round(Geospatial.getStrength(alert.distance, Configuration.REPORTS_MAX_DISTANCE) * 100.0f));
      String locationText;
      if(alert.street.length() != 0) {
        if("POLICE_HIDING".equals(alert.subType)) {
          locationText = String.format("hidden on %s", alert.street);
        }
        else {
          locationText = String.format("on %s", alert.street);
        }
      }
      else if(alert.city.length() != 0) {
        if("POLICE_HIDING".equals(alert.subType)) {
          locationText = String.format("hidden in %s", alert.city);
        }
        else {
          locationText = String.format("in %s", alert.city);
        }
      }
      else {
        if("POLICE_HIDING".equals(alert.subType)) {
          locationText = "hidden at unknown location";
        }
        else {
          locationText = "at unknown location";
        }
      }
      vh.banddOrLocationText.setText(locationText);
      if(alert.distance != 0) {
        DecimalFormat df = new DecimalFormat("0.#");
        float distance = alert.distance;
        if(Configuration.DEMO) {
          distance = Math.min(alert.distance, Configuration.DEMO_REPORTS_MAX_ANNOUNCED_DISTANCE);
        }
        vh.frequencyOrDistanceText.setText(String.format("%s %s", df.format(distance), distance >= 2.0f ? "miles" : "mile"));
      }
      else {
        vh.frequencyOrDistanceText.setText("");
      }
    }
    else if(alert.alertClass == Alert.ALERT_CLASS_AIRCRAFT) {
      // For aircraft vector states, show the report direction in clock bearing
      // format, the distance and the reported street or city
      if(alert.manufacturer.length() != 0) {
        String manufacturer = alert.manufacturer.substring(0, 1).toUpperCase() + alert.manufacturer.substring(1).toLowerCase();
        vh.typeText.setText(String.format("%s %s", manufacturer, alertClass));
      }
      else {
        vh.typeText.setText(alertClass);
      }
      vh.frequencyOrDistanceText.setText("");
      vh.bearingText.setText(String.format("%d o'clock", alert.bearing));
      vh.strengthProgressBar.setProgress(Math.round(Geospatial.getStrength(alert.distance, Configuration.AIRCRAFTS_MAX_DISTANCE) * 100.0f));
      vh.banddOrLocationText.setText(alert.owner.length() != 0 ? alert.owner : "unidentified");
      if(alert.distance != 0) {
        DecimalFormat df = new DecimalFormat("0.#");
        float distance = alert.distance;
        if(Configuration.DEMO) {
          distance = Math.min(alert.distance, Configuration.DEMO_AIRCRAFTS_MAX_ANNOUNCED_DISTANCE);
        }
        vh.frequencyOrDistanceText.setText(String.format("%s %s", df.format(distance), distance >= 2.0f ? "miles" : "mile"));
      }
      else {
        vh.frequencyOrDistanceText.setText("");
      }
    }
    else if(alert.alertClass == Alert.ALERT_CLASS_SPEED_CAM || alert.alertClass == Alert.ALERT_CLASS_RED_LIGHT_CAM
      || alert.alertClass == Alert.ALERT_CLASS_USER_MARK || alert.alertClass == Alert.ALERT_CLASS_LOCKOUT) {
      // For camera alerts, show the direction in clock bearing format and
      // the distance like Waze reports
      vh.typeText.setText(alertClass);
      vh.bearingText.setText(String.format("%d o'clock", alert.bearing != 0 ? alert.bearing : 12));
      vh.banddOrLocationText.setText("");
      if(alert.distance != 0) {
        vh.strengthProgressBar.setProgress(Math.round(Geospatial.getStrength(alert.distance, Configuration.REPORTS_MAX_DISTANCE) * 100.0f));
        DecimalFormat df = new DecimalFormat("0.#");
        vh.frequencyOrDistanceText.setText(String.format("%s %s", df.format(alert.distance), alert.distance >= 2.0f ? "miles" : "mile"));
      }
      else {
        vh.strengthProgressBar.setProgress(100);
        vh.frequencyOrDistanceText.setText("");
      }
    }
    else {
      // For other types of alerts, just show a max intensity and a default
      // 12 o'clock direction
      vh.typeText.setText(alertClass);
      vh.strengthProgressBar.setProgress(100);
      vh.bearingText.setText("12 o'clock");
      vh.frequencyOrDistanceText.setText("");
      vh.banddOrLocationText.setText("");
    }

    // Color-code the progress bar and card background by threat class
    int progressTint;
    int progress = vh.strengthProgressBar.getProgress();
    if(progress >= 70) {
      progressTint = 0xFFE53935; // red - high threat
    }
    else if(progress >= 35) {
      progressTint = 0xFFFFA726; // amber - medium threat
    }
    else {
      progressTint = 0xFF66BB6A; // green - low threat
    }
    vh.strengthProgressBar.getProgressDrawable().setTint(progressTint);

    // Subtle background tint by alert class
    if(alert.alertClass == Alert.ALERT_CLASS_LASER
      || alert.alertClass == Alert.ALERT_CLASS_SPEED_CAM
      || alert.alertClass == Alert.ALERT_CLASS_RED_LIGHT_CAM) {
      vh.itemView.setBackgroundColor(0x20E53935); // red tint — laser/cameras
    }
    else if(alert.alertClass == Alert.ALERT_CLASS_RADAR && alert.band == Alert.ALERT_BAND_KA) {
      vh.itemView.setBackgroundColor(0x20FFA726); // amber tint — KA band
    }
    else if(alert.alertClass == Alert.ALERT_CLASS_REPORT && "POLICE".equals(alert.type)) {
      vh.itemView.setBackgroundColor(0x20E53935); // red tint — speed traps (same as laser/cameras)
    }
    else if(alert.alertClass == Alert.ALERT_CLASS_REPORT && "ACCIDENT".equals(alert.type)) {
      vh.itemView.setBackgroundColor(0x20FFA726); // amber tint — accidents
    }
    else if(alert.alertClass == Alert.ALERT_CLASS_REPORT && "HAZARD".equals(alert.type)) {
      vh.itemView.setBackgroundColor(0x20FFEE58); // yellow tint — hazards
    }
    else if(alert.alertClass == Alert.ALERT_CLASS_REPORT && "JAM".equals(alert.type)) {
      vh.itemView.setBackgroundColor(0x2042A5F5); // blue tint — traffic jams
    }
    else if(alert.alertClass == Alert.ALERT_CLASS_AIRCRAFT) {
      vh.itemView.setBackgroundColor(0x20AB47BC); // purple tint — aircraft
    }
    else {
      // Default surface variant background for other radar bands etc.
      vh.itemView.setBackgroundColor(0x00000000);
    }
  }

  @Override
  public int getItemCount() {
    return mItems.size();
  }

  public List<Alert> getRadarAlerts() {
    return mRadarAlerts;
  }

  public List<Alert> getReportAlerts() {
    return mReportAlerts;
  }

  public List<Alert> getAircraftAlerts() {
    return mAircraftAlerts;
  }

  public void setReportsReminderEnabled(boolean enabled) {
    mReportsReminderEnabled = enabled;
  }

  public void setAircraftsReminderEnabled(boolean enabled) {
    mAircraftsReminderEnabled = enabled;
  }

  private void playEarconAnnounce(List<Alert> alerts, int pos, boolean audioFocus, int maxEarcons, PlayAlertAnnounceOnDone onDone) {
    playEarconAnnounce(alerts, pos, audioFocus, maxEarcons, false, mAnnouncementGeneration, onDone);
  }

  private void playEarconAnnounce(List<Alert> alerts, int pos, boolean audioFocus, int maxEarcons, boolean doubleEarcon, int generation, PlayAlertAnnounceOnDone onDone) {
    if(generation != mAnnouncementGeneration) return;
    Log.i(TAG, String.format("playEarconAnnounce %d", pos));
    if(pos >= maxEarcons) {
      mHandler.postDelayed(() -> {
        if(generation != mAnnouncementGeneration) return;
        Log.i(TAG, String.format("playEarconAnnounce.onDone.run() %b", audioFocus));
        onDone.run(audioFocus);
      }, MESSAGE_TOKEN, 1);
      return;
    }

    Alert alert = alerts.get(pos);

    String earcon;
    if(alert.alertClass == Alert.ALERT_CLASS_RADAR) {
      earcon = switch(alert.band) {
        case Alert.ALERT_BAND_X -> "[s2]";
        case Alert.ALERT_BAND_K, Alert.ALERT_BAND_POP_K -> "[s4]";
        case Alert.ALERT_BAND_KA -> "[s3]";
        case Alert.ALERT_BAND_MRCD, Alert.ALERT_BAND_MRCT -> "[s10]";
        case Alert.ALERT_BAND_GT3, Alert.ALERT_BAND_GT4 -> "[s6]";
        default -> "[s6]";
      };
    }
    else {
      earcon = switch(alert.alertClass) {
        case Alert.ALERT_CLASS_LASER -> "[s5]";
        case Alert.ALERT_CLASS_SPEED_CAM, Alert.ALERT_CLASS_RED_LIGHT_CAM -> "[s1]";
        case Alert.ALERT_CLASS_REPORT -> "[s8]";
        case Alert.ALERT_CLASS_USER_MARK, Alert.ALERT_CLASS_LOCKOUT -> "[s9]";
        case Alert.ALERT_CLASS_AIRCRAFT -> "[s7]";
        default -> "[s6]";
      };
    }


    // Play the sound indicating the class of alert on the voice call stream
    Runnable playTask = () -> {
      if(generation != mAnnouncementGeneration) return;
      mSpeechService.playEarcon(earcon);
      if(doubleEarcon && (alert.alertClass == Alert.ALERT_CLASS_RADAR || alert.alertClass == Alert.ALERT_CLASS_LASER)) {
        mSpeechService.playEarcon(earcon);
      }
      mHandler.postDelayed(() -> {
        if(generation != mAnnouncementGeneration) return;
        Log.i(TAG, String.format("playEarconAnnounce.onDone.run() %b", true));
        onDone.run(true);
      }, MESSAGE_TOKEN, Configuration.AUDIO_EARCON_TIMER);
    };
    if(!audioFocus) {
      mSpeechService.requestAudioFocus(() -> {
        playTask.run();
      });
    }
    else {
      playTask.run();
    }
  }

  protected void playSpeechAnnounce(List<Alert> alerts, int pos, boolean audioFocus, int maxSpeech, PlayAlertAnnounceOnDone onDone) {
    playSpeechAnnounce(alerts, pos, audioFocus, maxSpeech, mAnnouncementGeneration, onDone);
  }

  protected void playSpeechAnnounce(List<Alert> alerts, int pos, boolean audioFocus, int maxSpeech, int generation, PlayAlertAnnounceOnDone onDone) {
    if(generation != mAnnouncementGeneration) return;
    Log.i(TAG, String.format("playSpeechAnnounce %d", pos));
    if(pos >= maxSpeech) {
      mHandler.postDelayed(() -> {
        if(generation != mAnnouncementGeneration) return;
        Log.i(TAG, "playSpeechAnnounce.onDone.run()");
        onDone.run(audioFocus);
      }, MESSAGE_TOKEN, 1);
      return;
    }

    Alert alert = alerts.get(pos);
    if(alert.announced > 1 && !mForceAnnounce) {
      Log.i(TAG, String.format("alert announced %d", alert.announced));
      mHandler.postDelayed(() -> {
        if(generation != mAnnouncementGeneration) return;
        Log.i(TAG, "playSpeechAnnounce.onDone.run()");
        onDone.run(audioFocus);
      }, MESSAGE_TOKEN, 1);
      return;
    }

    String alertClass = "";
    switch(alert.alertClass) {
      case Alert.ALERT_CLASS_RADAR:
        alertClass = "Radar";
        break;
      case Alert.ALERT_CLASS_LASER:
        alertClass = "Laser";
        break;
      case Alert.ALERT_CLASS_SPEED_CAM:
        alertClass = "Speed Cam";
        break;
      case Alert.ALERT_CLASS_RED_LIGHT_CAM:
        alertClass = "Red Light Cam";
        break;
      case Alert.ALERT_CLASS_USER_MARK:
        alertClass = "User Mark";
        break;
      case Alert.ALERT_CLASS_LOCKOUT:
        alertClass = "Lockout";
        break;
      case Alert.ALERT_CLASS_REPORT:
        alertClass = alert.getDisplayName();
        break;
      case Alert.ALERT_CLASS_AIRCRAFT:
        alertClass = alert.type;
        break;
    }

    // Build speech announce content — either as segment IDs (pre-recorded)
    // or as a text string (system TTS)
    boolean useSegments = mSpeechService.usePreRecorded();
    List<Segment> segments = null;
    String speech = "";

    if(useSegments) {
      // Build pre-recorded segment list, passing SpeechService so
      // SegmentBuilder can check for complete sentence segments
      if(alert.alertClass == Alert.ALERT_CLASS_RADAR) {
        segments = SegmentBuilder.buildRadarSegments(alert, mSpeechService);
      }
      else if(alert.alertClass == Alert.ALERT_CLASS_LASER) {
        segments = SegmentBuilder.buildLaserSegments(mSpeechService);
      }
      else if(alert.alertClass == Alert.ALERT_CLASS_REPORT) {
        segments = SegmentBuilder.buildReportSegments(alert, mSpeechService);
      }
      else if(alert.alertClass == Alert.ALERT_CLASS_AIRCRAFT) {
        segments = SegmentBuilder.buildAircraftSegments(alert, mSpeechService);
      }
      else if(alert.alertClass == Alert.ALERT_CLASS_SPEED_CAM || alert.alertClass == Alert.ALERT_CLASS_RED_LIGHT_CAM
        || alert.alertClass == Alert.ALERT_CLASS_USER_MARK || alert.alertClass == Alert.ALERT_CLASS_LOCKOUT) {
        segments = SegmentBuilder.buildCameraSegments(alert, mSpeechService);
      }
      else {
        // Unknown alert class — announce as generic radar alert
        segments = SegmentBuilder.buildRadarSegments(alert, mSpeechService);
      }
    }
    else {
      // Build text string for system TTS (original logic)
      if(alert.alertClass == Alert.ALERT_CLASS_RADAR) {
        String band = switch(alert.band) {
          case Alert.ALERT_BAND_X -> "X band";
          case Alert.ALERT_BAND_K -> "K band";
          case Alert.ALERT_BAND_KA -> "K A band";
          case Alert.ALERT_BAND_POP_K -> "Pop K band";
          case Alert.ALERT_BAND_MRCD -> "M R C D";
          case Alert.ALERT_BAND_MRCT -> "M R C T";
          case Alert.ALERT_BAND_GT3 -> "G T 3";
          case Alert.ALERT_BAND_GT4 -> "G T 4";
          default -> "";
        };
        speech += band;
        if(alert.frequency >= 1) {
          DecimalFormat df = new DecimalFormat("0.#");
          speech += String.format(" %s", df.format(alert.frequency));
        }
        speech += String.format(" %s", alertClass);
      }
      else if(alert.alertClass == Alert.ALERT_CLASS_REPORT) {
        speech += alertClass;
        if(alert.announced > 1) {
          speech += " now";
        }
        if(alert.bearing != 0) {
          speech += String.format(" at %d o'clock", alert.bearing);
        }
        if(alert.distance != 0) {
          DecimalFormat df = new DecimalFormat("0.#");
          float distance = alert.distance;
          if(Configuration.DEMO) {
            distance = Math.min(alert.distance, Configuration.DEMO_REPORTS_MAX_ANNOUNCED_DISTANCE);
          }
          speech += String.format(" %s %s away", df.format(distance), distance >= 2.0f ? "miles" : "mile");
        }
        String locationText;
        if(alert.street.length() != 0) {
          if("POLICE_HIDING".equals(alert.subType)) {
            locationText = String.format("hidden on %s", alert.street);
          }
          else {
            locationText = String.format("on %s", alert.street);
          }
        }
        else if(alert.city.length() != 0) {
          if("POLICE_HIDING".equals(alert.subType)) {
            locationText = String.format("hidden in %s", alert.city);
          }
          else {
            locationText = String.format("in %s", alert.city);
          }
        }
        else {
          if("POLICE_HIDING".equals(alert.subType)) {
            locationText = "hidden at unknown location";
          }
          else {
            locationText = "at unknown location";
          }
        }
        speech += String.format(" %s", locationText);
      }
      else if(alert.alertClass == Alert.ALERT_CLASS_AIRCRAFT) {
        if(alert.owner.length() != 0) {
          speech += alert.owner;
        }
        else {
          speech += "Unidentified";
        }
        if(alert.manufacturer.length() != 0) {
          String manufacturer = alert.manufacturer.substring(0, 1).toUpperCase() + alert.manufacturer.substring(1).toLowerCase();
          speech += String.format(" %s", manufacturer);
        }
        speech += String.format(" %s", alertClass);
        if(alert.announced > 1) {
          speech += " now";
        }
        if(alert.bearing != 0) {
          speech += String.format(" at %d o'clock", alert.bearing);
        }
        if(alert.distance != 0) {
          DecimalFormat df = new DecimalFormat("0.#");
          float distance = alert.distance;
          if(Configuration.DEMO) {
            distance = Math.min(alert.distance, Configuration.DEMO_AIRCRAFTS_MAX_ANNOUNCED_DISTANCE);
          }
          speech += String.format(" %s %s away", df.format(distance), distance >= 2.0f ? "miles" : "mile");
        }
      }
      else if(alert.alertClass == Alert.ALERT_CLASS_SPEED_CAM || alert.alertClass == Alert.ALERT_CLASS_RED_LIGHT_CAM
        || alert.alertClass == Alert.ALERT_CLASS_USER_MARK || alert.alertClass == Alert.ALERT_CLASS_LOCKOUT) {
        speech += alertClass;
        if(alert.bearing != 0) {
          speech += String.format(" at %d o'clock", alert.bearing);
        }
        if(alert.distance != 0) {
          DecimalFormat df = new DecimalFormat("0.#");
          speech += String.format(" %s %s away", df.format(alert.distance), alert.distance >= 2.0f ? "miles" : "mile");
        }
      }
      else {
        speech += alertClass;
      }
    }

    // Play the speech announce on the voice call stream
    String uuid = UUID.randomUUID().toString();
    mSpeechService.addOnUtteranceProgressCallback(uuid, () -> {
      if(generation != mAnnouncementGeneration) return;
      Log.i(TAG, String.format("UtteranceProgressListener.onDone %s", uuid));
      mSpeechService.removeOnUtteranceProgressCallback(uuid);
      Log.i(TAG, "playSpeechAnnounce.onDone.run()");
      onDone.run(true);
    });

    List<Segment> playSegments = segments;
    String playSpeech = speech;
    Runnable playTask = () -> {
      if(generation != mAnnouncementGeneration) return;
      if(mActivity.isMuted()) {
        mSpeechService.removeOnUtteranceProgressCallback(uuid);
        onDone.run(true);
        return;
      }
      if(playSegments != null) {
        mSpeechService.playSegments(playSegments, uuid);
      }
      else {
        mSpeechService.playSpeech(playSpeech, uuid);
      }
    };
    if(!audioFocus) {
      mSpeechService.requestAudioFocus(() -> {
        playTask.run();
      });
    }
    else {
      playTask.run();
    }
  }

  public void cancelAnnouncements() {
    mAnnouncementGeneration++;
    if(mSpeechService != null) {
      mSpeechService.stopSpeech();
      mSpeechService.abandonAudioFocus(() -> {});
    }
  }

  protected interface PlayAlertAnnounceOnDone {
    void run(boolean audioFocus);
  }

  protected void playAlertAnnounce(
    List<Alert> alerts, String type, int pos, boolean audioFocus, int maxSpeech, int maxEarcons, PlayAlertAnnounceOnDone onDone) {
    playAlertAnnounce(alerts, type, pos, audioFocus, maxSpeech, maxEarcons, false, mAnnouncementGeneration, onDone);
  }

  protected void playAlertAnnounce(
    List<Alert> alerts, String type, int pos, boolean audioFocus, int maxSpeech, int maxEarcons, boolean doubleEarcon, int generation, PlayAlertAnnounceOnDone onDone) {
    if(generation != mAnnouncementGeneration) return;
    Log.i(TAG, String.format("playAlertAnnounce %s pos %d %b", type, pos, audioFocus));
    if(pos == alerts.size()) {
      mHandler.postDelayed(() -> {
        if(generation != mAnnouncementGeneration) return;
        Log.i(TAG, String.format("playAlertAnnounce.onDone.run() %b", audioFocus));
        onDone.run(audioFocus);
      }, MESSAGE_TOKEN, 1);
      return;
    }

    // Play a sound for each alert
    Log.i(TAG, String.format("playEarconAnnounce() %s %d", type, pos));
    playEarconAnnounce(alerts, pos, audioFocus, maxEarcons, doubleEarcon, generation, (audioFocus2) -> {
      if(generation != mAnnouncementGeneration) return;
      // Play a speech announce for the alert
      mHandler.postDelayed(() -> {
        if(generation != mAnnouncementGeneration) return;
        Log.i(TAG, String.format("playSpeechAnnounce() %s %d", type, pos));
        playSpeechAnnounce(alerts, pos, audioFocus2, maxSpeech, generation, (audioFocus3) -> {
          if(generation != mAnnouncementGeneration) return;
          mHandler.postDelayed(() -> {
            if(generation != mAnnouncementGeneration) return;
            // Announce the next alert
            Log.i(TAG, String.format("playAlertAnnounce() %s %d %b", type, pos + 1, audioFocus3));
            playAlertAnnounce(alerts, type, pos + 1, audioFocus3, maxSpeech, maxEarcons, doubleEarcon, generation, onDone);
          }, MESSAGE_TOKEN, 1);
        });
      }, MESSAGE_TOKEN, 1);
    });
  }

  public void setRadarAlerts(List<Alert> alerts, Runnable onDone) {
    Log.i(TAG, "setRadarAlerts");
    // Combine alerts, reports and aircrafts in a single list of alerts
    mRadarAlerts.clear();
    mRadarAlerts.addAll(alerts);
    rebuildItems();

    if(mRadarAlerts.size() > 0) {
      // Find truly new alerts that haven't been announced yet
      List<Alert> newAnnounces = new ArrayList<>();
      for(Alert alert : mRadarAlerts) {
        if(!alert.muted && alert.announced == 0) {
          newAnnounces.add(alert);
        }
      }
      if(newAnnounces.size() != 0) {
        // Stop the reminder while the announcement plays to prevent earcons
        // from piling up in the TTS queue behind the speech
        stopRadarReminderTask();

        // If a non-radar announcement is in progress, interrupt it
        if(mAnnouncementType == ANNOUNCEMENT_OTHER) {
          Log.i(TAG, "interrupting non-radar announcement for radar priority");
          mInterruptedAnnounces.clear();
          mInterruptedAnnounces.addAll(mCurrentAnnounces);
          mInterruptedAnnouncementCategory = mCurrentAnnouncementCategory;

          // Reset announced count so they get re-announced after radar
          for(Alert alert : mInterruptedAnnounces) {
            if(alert.announced > 0) alert.announced--;
          }

          // Increment generation to kill the old callback chain
          mAnnouncementGeneration++;
          mSpeechService.stopSpeech();
          mSpeechService.abandonAudioFocus(() -> {});
        }

        if(mAnnouncementType != ANNOUNCEMENT_RADAR) {
          // Mark as announced and start the announcement
          for(Alert alert : newAnnounces) {
            alert.announced = 1;
          }
          mAnnouncementType = ANNOUNCEMENT_RADAR;
          int generation = mAnnouncementGeneration;

          // Announce the new alerts with double earcon + speech
          Log.i(TAG, "playAlertAnnounce() alert pos 0");
          playAlertAnnounce(
            newAnnounces, "alert", 0, false, Configuration.DS1_ALERTS_MAX_SPEECH_ANNOUNCES, Configuration.DS1_ALERTS_MAX_EARCON_ANNOUNCES, true, generation, (audioFocus) -> {
              onRadarAnnouncementDone(audioFocus, generation, onDone);
            });
        }
        else {
          // Radar announcement already in progress, new alerts will be
          // announced when the current announcement completes
          onDone.run();
        }
      }
      else {
        // No new alerts, only start reminder if no announcement is in progress
        if(mAnnouncementType == ANNOUNCEMENT_NONE) {
          startRadarReminderTask();
        }
        onDone.run();
      }
    }
    else {
      // Alerts cleared, stop the reminder
      stopRadarReminderTask();
      Log.i(TAG, "setRadarAlerts.onDone.run()");
      onDone.run();
    }
  }

  private void replayInterruptedAnnouncements(boolean audioFocus, Runnable onDone) {
    List<Alert> toReplay = new ArrayList<>(mInterruptedAnnounces);
    String category = mInterruptedAnnouncementCategory;
    mInterruptedAnnounces.clear();
    mInterruptedAnnouncementCategory = null;

    if(toReplay.size() == 0 || category == null) {
      if(audioFocus) {
        mSpeechService.abandonAudioFocus(() -> onDone.run());
      }
      else {
        onDone.run();
      }
      return;
    }

    Log.i(TAG, String.format("replaying %d interrupted %s announcements", toReplay.size(), category));
    int maxSpeech = "report".equals(category) ? Configuration.REPORTS_MAX_SPEECH_ANNOUNCES : Configuration.AIRCRAFTS_MAX_SPEECH_ANNOUNCES;
    int maxEarcons = "report".equals(category) ? Configuration.REPORTS_MAX_EARCON_ANNOUNCES : Configuration.AIRCRAFTS_MAX_EARCON_ANNOUNCES;

    mAnnouncementType = ANNOUNCEMENT_OTHER;
    mCurrentAnnounces.clear();
    mCurrentAnnounces.addAll(toReplay);
    mCurrentAnnouncementCategory = category;
    int generation = mAnnouncementGeneration;

    playAlertAnnounce(toReplay, category, 0, audioFocus, maxSpeech, maxEarcons, false, generation, (audioFocus2) -> {
      mAnnouncementType = ANNOUNCEMENT_NONE;
      mCurrentAnnounces.clear();
      mCurrentAnnouncementCategory = null;

      if(audioFocus2) {
        Log.i(TAG, "abandonAudioFocus()");
        mSpeechService.abandonAudioFocus(() -> {
          Log.i(TAG, "replayInterrupted.onDone.run()");
          onDone.run();
        });
      }
      else {
        onDone.run();
      }
    });
  }

  private void onRadarAnnouncementDone(boolean audioFocus, int generation, Runnable onDone) {
    // Check for any new radar alerts that arrived during the announcement
    List<Alert> pendingAnnounces = new ArrayList<>();
    for(Alert alert : mRadarAlerts) {
      if(!alert.muted && alert.announced == 0) {
        alert.announced = 1;
        pendingAnnounces.add(alert);
      }
    }

    if(pendingAnnounces.size() > 0) {
      Log.i(TAG, String.format("announcing %d pending radar alerts", pendingAnnounces.size()));
      playAlertAnnounce(pendingAnnounces, "alert", 0, audioFocus,
        Configuration.DS1_ALERTS_MAX_SPEECH_ANNOUNCES, Configuration.DS1_ALERTS_MAX_EARCON_ANNOUNCES,
        true, generation, (audioFocus2) -> {
          onRadarAnnouncementDone(audioFocus2, generation, onDone);
        });
    }
    else {
      mAnnouncementType = ANNOUNCEMENT_NONE;
      startRadarReminderTask();

      // Replay interrupted announcements if any
      if(mInterruptedAnnounces.size() > 0) {
        replayInterruptedAnnouncements(audioFocus, onDone);
      }
      else {
        if(audioFocus) {
          Log.i(TAG, "abandonAudioFocus()");
          mSpeechService.abandonAudioFocus(() -> {
            Log.i(TAG, "setRadarAlerts.onDone.run()");
            onDone.run();
          });
        }
        else {
          onDone.run();
        }
      }
    }
  }

  private void startRadarReminderTask() {
    if(!mRadarReminderRunning) {
      mRadarReminderRunning = true;
      mHandler.postDelayed(mRadarReminderTask, MESSAGE_TOKEN, Configuration.DS1_ALERTS_REMINDER_TIMER);
    }
  }

  private void stopRadarReminderTask() {
    mRadarReminderRunning = false;
    mHandler.removeCallbacks(mRadarReminderTask);
  }

  public void setReportAlerts(List<Alert> reports, Runnable onDone) {
    Log.i(TAG, "setReportAlerts");
    // Combine alerts, reports and aircrafts in a single list of alerts
    mReportAlerts.clear();
    mReportAlerts.addAll(reports);
    rebuildItems();

    if(mReportAlerts.size() > 0) {
      mReportsAllClear = false;

      // Announce the reports if they've not been announced yet or if their
      // distance or bearing has changed significantly since then
      List<Alert> announces = new ArrayList<>();
      for(Alert report : mReportAlerts) {
        if(report.shouldAnnounceReport()) {
          announces.add(report);
        }
      }

      if(announces.size() != 0 && mAnnouncementType == ANNOUNCEMENT_NONE) {
        for(Alert report : announces) {
          report.announced += 1;
          report.announceDistance = report.distance;
          report.announceBearing = report.bearing;
        }
        mAnnouncementType = ANNOUNCEMENT_OTHER;
        mCurrentAnnounces.clear();
        mCurrentAnnounces.addAll(announces);
        mCurrentAnnouncementCategory = "report";
        mForceAnnounce = true;

        // Reschedule the reminder task for later as some reports are going
        // to be announced right away
        mHandler.removeCallbacks(mReportsReminderTask);
        mHandler.postDelayed(mReportsReminderTask, MESSAGE_TOKEN, Configuration.REPORTS_REMINDER_TIMER);

        // Announce the reports
        Log.i(TAG, "playAlertAnnounce() report pos 0");
        playAlertAnnounce(
          announces, "report", 0, false, Configuration.REPORTS_MAX_SPEECH_ANNOUNCES, Configuration.REPORTS_MAX_EARCON_ANNOUNCES, (audioFocus) -> {

            mAnnouncementType = ANNOUNCEMENT_NONE;
            mCurrentAnnounces.clear();
            mCurrentAnnouncementCategory = null;
            mForceAnnounce = false;

            // Abandon audio focus once done
            if(audioFocus) {
              Log.i(TAG, "abandonAudioFocus()");
              mSpeechService.abandonAudioFocus(() -> {
                Log.i(TAG, "setReportAlerts.onDone.run()");
                onDone.run();
              });
            }
            else {
              onDone.run();
            }
          });
      }
    }
    else {
      Log.i(TAG, "setReportAlerts.onDone.run()");
      onDone.run();
    }
  }

  public void setAircraftAlerts(List<Alert> aircrafts, Runnable onDone) {
    Log.i(TAG, "setAircraftAlerts");
    // Combine alerts, aircrafts and aircrafts in a single list of alerts
    mAircraftAlerts.clear();
    mAircraftAlerts.addAll(aircrafts);
    rebuildItems();

    if(mAircraftAlerts.size() > 0) {
      mAircraftsAllClear = false;

      // Announce the aircrafts if they've not been announced yet or if their
      // distance or bearing has changed significantly since then
      List<Alert> announces = new ArrayList<>();
      for(Alert aircraft : mAircraftAlerts) {
        if(aircraft.shouldAnnounceAircraft()) {
          announces.add(aircraft);
        }
      }

      if(announces.size() != 0 && mAnnouncementType == ANNOUNCEMENT_NONE) {
        for(Alert aircraft : announces) {
          aircraft.announced += 1;
          aircraft.announceDistance = aircraft.distance;
          aircraft.announceBearing = aircraft.bearing;
        }
        mAnnouncementType = ANNOUNCEMENT_OTHER;
        mCurrentAnnounces.clear();
        mCurrentAnnounces.addAll(announces);
        mCurrentAnnouncementCategory = "aircraft";
        mForceAnnounce = true;

        // Reschedule the reminder task for later as some reports are going
        // to be announced right away
        mHandler.removeCallbacks(mAircraftsReminderTask);
        mHandler.postDelayed(mAircraftsReminderTask, MESSAGE_TOKEN, Configuration.AIRCRAFTS_REMINDER_TIMER);

        // Announce the aircrafts
        Log.i(TAG, "playAlertAnnounce() aircraft pos 0");
        playAlertAnnounce(
          announces, "aircraft", 0, false, Configuration.AIRCRAFTS_MAX_SPEECH_ANNOUNCES, Configuration.AIRCRAFTS_MAX_EARCON_ANNOUNCES, (audioFocus) -> {

            mAnnouncementType = ANNOUNCEMENT_NONE;
            mCurrentAnnounces.clear();
            mCurrentAnnouncementCategory = null;
            mForceAnnounce = false;

            // Abandon audio focus once done
            if(audioFocus) {
              Log.i(TAG, "abandonAudioFocus()");
              mSpeechService.abandonAudioFocus(() -> {
                Log.i(TAG, "setAircraftAlerts.onDone.run()");
                onDone.run();
              });
            }
            else {
              onDone.run();
            }
          });
      }
    }
    else {
      Log.i(TAG, "setAircraftAlerts.onDone.run()");
      onDone.run();
    }
  }

  public static class ViewHolder extends RecyclerView.ViewHolder {
    public TextView typeText;
    public TextView frequencyOrDistanceText;
    public TextView banddOrLocationText;
    public TextView bearingText;
    public ProgressBar strengthProgressBar;

    public ViewHolder(View v) {
      super(v);

      typeText = v.findViewById(R.id.typeText);
      frequencyOrDistanceText = v.findViewById(R.id.frequencyOrDistanceText);
      banddOrLocationText = v.findViewById(R.id.bandOrLocationText);
      bearingText = v.findViewById(R.id.bearingText);
      strengthProgressBar = v.findViewById(R.id.strengthProgressBar);
    }
  }

  private void rebuildItems() {
    List<Alert> oldItems = new ArrayList<>(mItems);
    mItems.clear();
    mItems.addAll(mRadarAlerts);
    mItems.addAll(mReportAlerts);
    mItems.addAll(mAircraftAlerts);
    List<Alert> newItems = new ArrayList<>(mItems);
    DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
      @Override
      public int getOldListSize() { return oldItems.size(); }
      @Override
      public int getNewListSize() { return newItems.size(); }
      @Override
      public boolean areItemsTheSame(int oldPos, int newPos) {
        Alert o = oldItems.get(oldPos);
        Alert n = newItems.get(newPos);
        if(o.alertClass != n.alertClass) return false;
        if(o.alertClass == Alert.ALERT_CLASS_REPORT) return o.isSameReport(n);
        if(o.alertClass == Alert.ALERT_CLASS_AIRCRAFT) return o.isSameAircraft(n);
        return o.band == n.band && o.alertClass == n.alertClass;
      }
      @Override
      public boolean areContentsTheSame(int oldPos, int newPos) {
        return false;
      }
    });
    mActivity.runOnUiThread(() -> {
      result.dispatchUpdatesTo(this);
      mActivity.updateEmptyState(mItems.size());
    });
  }

  public void onDestroy() {
    mHandler.removeCallbacksAndMessages(MESSAGE_TOKEN);
  }
}
