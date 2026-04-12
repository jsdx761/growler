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

/**
 * Various internal configuration properties.
 */
public class Configuration {

  public static final boolean DEBUG = false;
  public static final boolean DEMO = false;
  public static final long SPLASH_TIMER = 2000;
  public static final String DS1_SERVICE_SCAN_NAME = "DS1@E1";
  public static final long DS1_SERVICE_CONNECT_WAIT_TIMER = 5000;
  public static final long DS1_SERVICE_SETUP_TIMER = 1000;
  public static final long DS1_SERVICE_RECONNECT_TIMER = 5000;
  public static final long ALL_CLEAR_REMINDER_TIMER = 10000;
  public static final boolean ENABLE_DS1_ALERTS = true;
  public static final long DS1_ALERTS_CLEAR_TIMER = 10000;
  public static final int DS1_ALERTS_MAX_SPEECH_ANNOUNCES = DEBUG ? 1 : 3;
  public static final int DS1_ALERTS_MAX_EARCON_ANNOUNCES = DEBUG ? 1 : 3;
  public static final long DS1_ALERTS_REMINDER_TIMER = 1000;
  public static final int AUDIO_ADJUST_RAISE_COUNT = 0;
  public static final float AUDIO_SPEECH_PITCH = 0.90f;
  public static final float AUDIO_SPEECH_RATE = 1.1f;
  public static final long AUDIO_EARCON_TIMER = 500;

  // Appearance mode selection
  public static final int APPEARANCE_MODE_SYSTEM = 0;
  public static final int APPEARANCE_MODE_DARK = 1;
  public static final int APPEARANCE_MODE_LIGHT = 2;

  // Voice mode selection
  public static final int VOICE_MODE_SYSTEM = 0;
  public static final int VOICE_MODE_PRERECORDED = 1;
  public static final int VOICE_MODE_TUNED_LOCAL = 2;
  public static final int VOICE_MODE_PRERECORDED_ELEVENLABS = 3;
  public static final int VOICE_MODE_PRERECORDED_ELEVENLABS_075 = 4;
  public static final int VOICE_MODE_PRERECORDED_ELEVENLABS_090 = 5;
  public static final int VOICE_MODE_PRERECORDED_VOXTRAL_110 = 6;
  public static final int VOICE_MODE_PRERECORDED_VOXTRAL_120 = 7;
  public static final int VOICE_MODE_PRERECORDED_ELEVENLABS_100 = 8;
  public static final int VOICE_MODE_PRERECORDED_ELEVENLABS_110 = 9;
  public static final int VOICE_MODE_PRERECORDED_ELEVENLABS_120 = 10;
  // Optimized filter defaults for tuned local TTS (en-gb-x-gbc-local).
  // Found by Optuna optimization (150 trials, score 0.7024).
  // Reference: combined_raw-esv2-speech-100p.wav (Adobe denoised/enhanced).
  // Can be overridden via SharedPreferences / ADB SET_TUNED_FILTER.
  public static final String TUNED_LOCAL_VOICE = "en-gb-x-gbc-local";
  public static final float TUNED_LOCAL_PITCH = 1.0f;
  public static final float TUNED_LOCAL_RATE = 0.8f;
  public static final String TUNED_LOCAL_FILTER =
    "3.0,375.0,0.5,1000.0,2.25,6.0,4000.0,1.0,5.0,3000.0,-8.0,4.5";
  public static final long CURRENT_LOCATION_TIMER = 5000;
  public static final boolean USE_COMPUTED_LOCATION_BEARING = true;
  public static final long LOCATION_AVAILABILITY_CHECK_TIMER = 10000;
  public static final float COMPUTED_BEARING_DISTANCE_THRESHOLD = 40.0f;
  public static final long NETWORK_CONNECT_CHECK_TIMER = 15000;
  public static final int NETWORK_CONNECT_TIMEOUT = 3000;
  public static final int NETWORK_CONNECT_RETRY_COUNT = 1;
  public static final long NETWORK_CONNECT_RETRY_TIMER = 5000;
  public static final long NETWORK_LOST_DEBOUNCE_TIMER = 10000;
  public static final long NETWORK_VALIDATED_DEBOUNCE_TIMER = 15000;
  public static final boolean ENABLE_REPORTS = true;
  public static final long REPORTS_CHECK_TIMER = 24000;
  public static final int REPORTS_CHECK_RETRY_COUNT = 3;
  public static final long REPORTS_CHECK_RETRY_TIMER = 10000;
  public static final int REPORTS_OFF_DEBOUNCE_COUNT = 2;
  public static final float REPORTS_DUPLICATE_DISTANCE = 0.125f;
  public static final int REPORTS_CONNECT_TIMEOUT = 15000;
  public static final boolean ENABLE_AIRCRAFTS = true;
  public static final int AIRCRAFTS_CHECK_RETRY_COUNT = 2;
  public static final long AIRCRAFTS_CHECK_RETRY_TIMER = 5000;
  public static final long AIRCRAFTS_ANONYMOUS_CHECK_TIMER = 240000;
  public static final long AIRCRAFTS_AUTHENTICATED_CHECK_TIMER = 24000;
  public static final int AIRCRAFTS_CONNECT_TIMEOUT = 5000;
  public static final boolean DEBUG_USE_NULL_DS1_SERVICE = DEBUG;
  public static final long DEBUG_NULL_DS1_SERVICE_SCAN_TIMER = 2000;
  public static final int DEBUG_INJECT_TEST_DS1_ALERTS = DEBUG ? 1 : DEMO ? 1 : 0;
  public static final long DEBUG_TEST_DS1_ALERTS_TIMER = DEBUG ? 40000 : DEMO ? 40000 : 0;
  public static final long DEBUG_TEST_DS1_ALERTS_REPEAT_TIMER = DEBUG ? 300 : DEMO ? 300 : 0;
  public static final long DEBUG_TEST_DS1_ALERTS_REPEAT_COUNT = DEBUG ? 5 : DEMO ? 5 : 0;

  public static final String[] DEBUG_TEST_DS1_ALERTS = DEBUG_INJECT_TEST_DS1_ALERTS != 0 ? new String[]{
    "1,123,Red Light Cam,10,0,0,F,0",
    "1,124,Speed Cam,10,0,0,F,0",
    "1,125,KA,10,456,34.7,F,1",
    "1,126,K,10,456,24.1,F,1",
    "1,127,Laser,10,456,29.8,F,1"
  } : new String[]{};

  public static final boolean DEBUG_USE_ZERO_BEARING = DEBUG;
  public static final boolean DEBUG_ANNOUNCE_VEHICLE_BEARING = false;
  public static final int DEBUG_INJECT_TEST_REPORTS = DEBUG ? 1 : 0;
  public static final float REPORTS_MAX_DISTANCE = DEBUG ? 50.0f : DEMO ? 4.0f : 2.0f;
  public static final float DEMO_REPORTS_MAX_ANNOUNCED_DISTANCE = 2.0f;
  public static final long REPORTS_REMINDER_TIMER = 60000;
  public static final float REPORTS_REMINDER_DISTANCE = 0.5f;
  public static final int REPORTS_MAX_SPEECH_ANNOUNCES = 3;
  public static final int REPORTS_MAX_EARCON_ANNOUNCES = 1;
  // Earcons always play on every report announcement now
  // public static final boolean REPORTS_EARCON_REMINDER = true;

  public static final String DEBUG_TEST_REPORTS = DEBUG_INJECT_TEST_REPORTS != 0 ? """
      {
        "alerts" : [
            {
              "additionalInfo" : "",
              "city" : "East Palo Alto, CA",
              "confidence" : 0,
              "country" : "US",
              "inscale" : true,
              "isJamUnifiedAlert" : false,
              "location" : {
                  "x" : -122.141221,
                  "y" : 37.458524
              },
              "magvar" : 25,
              "nImages" : 0,
              "reliability" : 5,
              "reportRating" : 4,
              "roadType" : 6,
              "street" : "University Ave",
              "subtype" : "HAZARD_ON_ROAD_CONSTRUCTION",
              "type" : "ACCIDENT"
            },
            {
              "additionalInfo" : "",
              "city" : "San Mateo, CA",
              "confidence" : 2,
              "country" : "US",
              "inscale" : true,
              "isJamUnifiedAlert" : false,
              "location" : {
                  "x" : -122.325196,
                  "y" : 37.581049
              },
              "magvar" : 139,
              "nThumbsUp" : 4,
              "reliability" : 9,
              "reportRating" : 5,
              "roadType" : 3,
              "street" : "US-101 S",
              "subtype" : "POLICE_VISIBLE",
              "type" : "POLICE"
            },
            {
              "additionalInfo" : "",
              "city" : "San Bruno, CA",
              "confidence" : 0,
              "country" : "US",
              "inscale" : false,
              "isJamUnifiedAlert" : false,
              "location" : {
                  "x" : -122.403133,
                  "y" : 37.631098
              },
              "magvar" : 172,
              "nThumbsUp" : 1,
              "reliability" : 6,
              "reportRating" : 2,
              "roadType" : 4,
              "street" : "Exit 6A: US-101 S » San Jose / SF Intl Airport",
              "subtype" : "POLICE_VISIBLE",
              "type" : "POLICE"
            },
            {
              "additionalInfo" : "",
              "city" : "San Bruno, CA",
              "confidence" : 0,
              "country" : "US",
              "inscale" : true,
              "isJamUnifiedAlert" : false,
              "location" : {
                  "x" : -122.402707,
                  "y" : 37.630026
              },
              "magvar" : 174,
              "nThumbsUp" : 1,
              "reliability" : 7,
              "reportRating" : 5,
              "roadType" : 3,
              "street" : "US-101 S",
              "subtype" : "POLICE_VISIBLE",
              "type" : "POLICE"
            }
        ]
      }
    """ : "";

  public static final int DEBUG_INJECT_TEST_AIRCRAFTS = DEBUG ? 1 : DEMO ? 1 : 0;
  public static final float AIRCRAFTS_MAX_DISTANCE = DEBUG ? 50.0f : DEMO ? 50.0f : 5.0f;
  public static final float DEMO_AIRCRAFTS_MAX_ANNOUNCED_DISTANCE = 5.0f;
  public static final long AIRCRAFTS_REMINDER_TIMER = 60000;
  public static final float AIRCRAFTS_REMINDER_DISTANCE = 1.0f;
  public static final int AIRCRAFTS_MAX_SPEECH_ANNOUNCES = 3;
  public static final int AIRCRAFTS_MAX_EARCON_ANNOUNCES = 1;
  // Earcons always play on every aircraft announcement now
  // public static final boolean AIRCRAFTS_EARCON_REMINDER = true;

  // The following test transponder icao24 addresses correspond to interesting
  // aircrafts in the aircrafts database
  // a6165b
  // a54f11
  // a3566a
  public static final String DEBUG_TEST_AIRCRAFTS = DEBUG_INJECT_TEST_AIRCRAFTS != 0 ? """
      {
        "states" : [
            [
              "ab5d36",
              "EJM831  ",
              "United States",
              0,
              0,
              -121.9169,
              37.7466,
              8008.62,
              false,
              209.61,
              351.53,
              13,
              null,
              8161.02,
              "1757",
              false,
              0
            ],
            [
              "a6165b",
              "",
              "United States",
              0,
              0,
              -121.9982,
              37.498,
              1676.4,
              false,
              123.89,
              274.76,
              -5.2,
              null,
              1653.54,
              "7253",
              false,
              0
            ],
            [
              "a2d3b3",
              "SWA458  ",
              "United States",
              0,
              0,
              -122.2155,
              37.7109,
              null,
              true,
              0,
              306.56,
              null,
              null,
              null,
              null,
              false,
              0
            ],
            [
              "a54f11",
              "N362Q   ",
              "United States",
              0,
              0,
              -122.0931,
              37.4636,
              259.08,
              false,
              43.66,
              145.56,
              0.33,
              null,
              205.74,
              "1200",
              false,
              0
            ],
            [
              "ad9e83",
              "N977JW  ",
              "United States",
              0,
              0,
              -122.114,
              37.4544,
              null,
              true,
              2.83,
              244.69,
              null,
              null,
              null,
              "1200",
              false,
              0
            ],
            [
              "a3566a",
              "N505FC  ",
              "United States",
              0,
              0,
              -121.9344,
              37.3616,
              null,
              true,
              0,
              230.62,
              null,
              null,
              null,
              "6042",
              false,
              0
            ],
            [
              "ad5460",
              "SWA1682 ",
              "United States",
              0,
              0,
              -122.2136,
              37.7023,
              null,
              true,
              0.51,
              210.94,
              null,
              null,
              null,
              null,
              false,
              0
            ]
        ]
      }
    """ : "";

}
