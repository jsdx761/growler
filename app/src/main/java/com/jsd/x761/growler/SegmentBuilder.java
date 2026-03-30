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

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds lists of pre-recorded segment IDs for each type of alert
 * announcement.
 *
 * Prefers COMPLETE SENTENCE segments when available (e.g. "radar_ka_34_7"
 * for the full phrase "K A band thirty four point seven Radar") since they
 * have natural prosody from Voxtral. Falls back to ATOMIC SEGMENTS
 * concatenated at runtime when no complete sentence exists.
 *
 * Every build method guarantees a non-empty list so the user always hears
 * something, even for unexpected input.  When a specific segment is not
 * available the announcement is simplified — for example an aircraft with
 * an unknown organization is announced as "Unidentified Helicopter" rather
 * than being silently dropped.
 */
public class SegmentBuilder {

  // ── Band key mapping (used for complete sentence IDs) ──────────────────

  private static String bandToKey(int band) {
    return switch(band) {
      case Alert.ALERT_BAND_X -> "x";
      case Alert.ALERT_BAND_K -> "k";
      case Alert.ALERT_BAND_KA -> "ka";
      case Alert.ALERT_BAND_POP_K -> "pop_k";
      case Alert.ALERT_BAND_MRCD -> "mrcd";
      case Alert.ALERT_BAND_MRCT -> "mrct";
      case Alert.ALERT_BAND_GT3 -> "gt3";
      case Alert.ALERT_BAND_GT4 -> "gt4";
      default -> null;
    };
  }

  // ── Radar ──────────────────────────────────────────────────────────────

  /**
   * Build the segment list for a radar alert.
   *
   * Priority order:
   *  1. Complete sentence "radar_[band]_[freq]" (e.g. "radar_ka_34_7")
   *  2. Complete band-only "radar_[band]" when no frequency or freq is
   *     outside the pre-recorded range
   *  3. Atomic [band] + [freq] + [class_radar] when the frequency is in
   *     range but no complete sentence was pre-recorded for that band
   *  4. Atomic [band] + [class_radar] dropping unknown frequency
   *  5. Bare "class_radar" if even the band is unknown
   */
  public static List<Segment> buildRadarSegments(Alert alert, SpeechService speech) {
    String bandKey = bandToKey(alert.band);
    if(bandKey == null) {
      return List.of(new Segment("class_radar"));
    }

    boolean hasFreq = alert.frequency >= 1;
    String freqKey = hasFreq ? frequencyToKey(alert.frequency) : null;

    // 1. Try complete sentence with frequency
    if(hasFreq && freqKey != null) {
      String completeId = String.format("radar_%s_%s", bandKey, freqKey);
      if(speech.hasSegment(completeId)) {
        return List.of(new Segment(completeId));
      }
    }

    // 2. If frequency is outside pre-recorded range or no frequency,
    //    use the band-only complete sentence
    if(!hasFreq || !speech.hasSegment("freq_" + freqKey)) {
      String bandOnlyId = "radar_" + bandKey;
      if(speech.hasSegment(bandOnlyId)) {
        return List.of(new Segment(bandOnlyId));
      }
    }

    // 3. Atomic approach: [band] + [freq] + [class_radar]
    //    All within the same noun phrase — tight gaps
    List<Segment> segments = new ArrayList<>();
    String bandAtom = "band_" + bandKey;
    if(speech.hasSegment(bandAtom)) {
      segments.add(new Segment(bandAtom));
    }
    if(hasFreq && freqKey != null) {
      String freqAtom = "freq_" + freqKey;
      if(speech.hasSegment(freqAtom)) {
        segments.add(new Segment(freqAtom, Segment.GAP_TIGHT));
      }
    }
    segments.add(new Segment("class_radar",
      segments.isEmpty() ? Segment.GAP_NONE : Segment.GAP_TIGHT));
    return segments;
  }

  // ── Laser ──────────────────────────────────────────────────────────────

  /**
   * Build the segment list for a laser alert.
   */
  public static List<Segment> buildLaserSegments(SpeechService speech) {
    if(speech.hasSegment("radar_laser")) {
      return List.of(new Segment("radar_laser"));
    }
    return List.of(new Segment("class_laser"));
  }

  // ── Camera / Mark ─────────────────────────────────────────────────────

  /**
   * Build the segment list for a camera/mark alert.
   * Tries complete "camera_[type]_[bearing]" first, then atomic fallback.
   * Always produces at least one segment.
   */
  public static List<Segment> buildCameraSegments(Alert alert, SpeechService speech) {
    List<Segment> segments = new ArrayList<>();

    String typeKey = switch(alert.alertClass) {
      case Alert.ALERT_CLASS_SPEED_CAM -> "speed_cam";
      case Alert.ALERT_CLASS_RED_LIGHT_CAM -> "red_light_cam";
      case Alert.ALERT_CLASS_USER_MARK -> "user_mark";
      case Alert.ALERT_CLASS_LOCKOUT -> "lockout";
      default -> null;
    };

    // Try complete sentence: "Speed Cam at 3 o'clock"
    boolean usedComplete = false;
    if(typeKey != null && alert.bearing != 0) {
      String completeId = String.format("camera_%s_%d", typeKey, alert.bearing);
      if(speech.hasSegment(completeId)) {
        segments.add(new Segment(completeId));
        usedComplete = true;
      }
    }

    if(!usedComplete) {
      // Atomic fallback
      if(typeKey != null) {
        String typeAtom = "type_" + typeKey;
        if(speech.hasSegment(typeAtom)) {
          segments.add(new Segment(typeAtom));
        }
        else {
          segments.add(new Segment("class_radar"));
        }
      }
      else {
        segments.add(new Segment("class_radar"));
      }
      if(alert.bearing != 0) {
        segments.add(new Segment("bearing_" + alert.bearing, Segment.GAP_CLAUSE));
      }
    }

    // Append distance — clause boundary from bearing or type
    if(alert.distance != 0) {
      String distSegment = distanceToSegmentId(alert.distance);
      if(distSegment != null && speech.hasSegment(distSegment)) {
        segments.add(new Segment(distSegment, Segment.GAP_CLAUSE));
      }
    }

    return segments;
  }

  // ── Waze / Crowdsourced Reports ───────────────────────────────────────

  /**
   * Build the segment list for a Waze/crowdsourced report alert.
   * Always produces at least one segment — unknown report types fall back
   * to "report_hazard" (generic "Hazard").
   */
  public static List<Segment> buildReportSegments(Alert alert, SpeechService speech) {
    List<Segment> segments = new ArrayList<>();

    // Map to display name segment; use generic "Hazard" for unknown types
    String displaySegment = reportTypeToSegmentId(alert);

    // Determine road type for combined segment lookup
    boolean hidden = "POLICE_HIDING".equals(alert.subType);
    String roadSegment = null;
    if(alert.street.length() != 0) {
      roadSegment = RoadType.classify(alert.street, hidden);
    }
    else if(hidden) {
      roadSegment = "road_hidden_road";
    }

    // For re-announcements, try complete "[type]_now" sentence
    if(alert.announced > 1) {
      String nowId = displaySegment + "_now";
      if(speech.hasSegment(nowId)) {
        segments.add(new Segment(nowId));
      }
      else if(speech.hasSegment(displaySegment)) {
        segments.add(new Segment(displaySegment));
        segments.add(new Segment("word_now", Segment.GAP_TIGHT));
      }
      else {
        segments.add(new Segment("report_hazard"));
        segments.add(new Segment("word_now", Segment.GAP_TIGHT));
      }
    }
    else {
      // Try complete "type + road" sentence (e.g. "Speed Trap on Highway")
      boolean usedCombined = false;
      if(roadSegment != null) {
        String combinedId = displaySegment + "_" + roadSegment;
        if(speech.hasSegment(combinedId)) {
          segments.add(new Segment(combinedId));
          usedCombined = true;
        }
      }

      if(!usedCombined) {
        // Fall back to separate type and road segments
        if(speech.hasSegment(displaySegment)) {
          segments.add(new Segment(displaySegment));
        }
        else {
          segments.add(new Segment("report_hazard"));
        }
        if(roadSegment != null && speech.hasSegment(roadSegment)) {
          segments.add(new Segment(roadSegment, Segment.GAP_PHRASE));
        }
      }
    }

    // Bearing — clause boundary from the main description
    if(alert.bearing != 0) {
      segments.add(new Segment("bearing_" + alert.bearing, Segment.GAP_CLAUSE));
    }

    // Distance — clause boundary
    float distance = alert.distance;
    if(Configuration.DEMO) {
      distance = Math.min(distance, Configuration.DEMO_REPORTS_MAX_ANNOUNCED_DISTANCE);
    }
    if(distance != 0) {
      String distSegment = distanceToSegmentId(distance);
      if(distSegment != null && speech.hasSegment(distSegment)) {
        segments.add(new Segment(distSegment, Segment.GAP_CLAUSE));
      }
    }

    return segments;
  }

  // ── Aircraft ──────────────────────────────────────────────────────────

  /**
   * Build the segment list for an aircraft alert.
   * Always produces at least one segment — unknown orgs fall back to
   * "Unidentified", unknown types to "Aircraft".
   */
  public static List<Segment> buildAircraftSegments(Alert alert, SpeechService speech) {
    List<Segment> segments = new ArrayList<>();

    String orgKey = OrganizationCategory.categorize(alert.owner);
    // Strip "org_" prefix for the complete sentence key
    String orgShort = orgKey.startsWith("org_") ? orgKey.substring(4) : orgKey;

    String typeKey = switch(alert.type) {
      case "Airplane" -> "airplane";
      case "Helicopter" -> "helicopter";
      case "Drone" -> "drone";
      default -> "aircraft";
    };

    // Try complete org+mfg+type sentence first, then org+type, then atomic
    String mfgSegment = ManufacturerCategory.categorize(alert.manufacturer);
    String mfgShort = mfgSegment != null && mfgSegment.startsWith("mfg_")
      ? mfgSegment.substring(4) : null;

    boolean usedComplete = false;

    // 1. Try complete org+mfg+type (e.g. "aircraft_police_airbus_helicopter")
    if(mfgShort != null) {
      String completeId = String.format("aircraft_%s_%s_%s", orgShort, mfgShort, typeKey);
      if(speech.hasSegment(completeId)) {
        segments.add(new Segment(completeId));
        usedComplete = true;
      }
    }

    // 2. Try complete org+type (e.g. "aircraft_police_helicopter")
    if(!usedComplete) {
      String orgTypeId = String.format("aircraft_%s_%s", orgShort, typeKey);
      if(speech.hasSegment(orgTypeId)) {
        segments.add(new Segment(orgTypeId));
        usedComplete = true;
      }
    }

    // 3. Atomic fallback: org + mfg + type — tight gaps within the phrase
    if(!usedComplete) {
      if(speech.hasSegment(orgKey)) {
        segments.add(new Segment(orgKey));
      }
      if(mfgSegment != null && speech.hasSegment(mfgSegment)) {
        segments.add(new Segment(mfgSegment,
          segments.isEmpty() ? Segment.GAP_NONE : Segment.GAP_TIGHT));
      }
      segments.add(new Segment("aircraft_" + typeKey,
        segments.isEmpty() ? Segment.GAP_NONE : Segment.GAP_TIGHT));
    }

    // Guarantee at least one segment was added
    if(segments.isEmpty()) {
      segments.add(new Segment("aircraft_aircraft"));
    }

    // "now" for re-announcements — tight join to the type
    if(alert.announced > 1) {
      segments.add(new Segment("word_now", Segment.GAP_TIGHT));
    }

    // Bearing — clause boundary
    if(alert.bearing != 0) {
      segments.add(new Segment("bearing_" + alert.bearing, Segment.GAP_CLAUSE));
    }

    // Distance — clause boundary
    float distance = alert.distance;
    if(Configuration.DEMO) {
      distance = Math.min(distance, Configuration.DEMO_AIRCRAFTS_MAX_ANNOUNCED_DISTANCE);
    }
    if(distance != 0) {
      String distSegment = distanceToSegmentId(distance);
      if(distSegment != null && speech.hasSegment(distSegment)) {
        segments.add(new Segment(distSegment, Segment.GAP_CLAUSE));
      }
    }

    return segments;
  }

  // ── Status / All-Clear ────────────────────────────────────────────────

  /**
   * Build segments for an "all clear" announcement.
   */
  public static List<Segment> buildAllClearSegments(boolean aircraft) {
    return List.of(new Segment(aircraft ? "status_aircraft_all_clear" : "status_waze_all_clear"));
  }

  /**
   * Build segments for a status event announcement.
   */
  public static List<Segment> buildStatusSegments(String statusId) {
    return List.of(new Segment(statusId));
  }

  // ── Mapping helpers ────────────────────────────────────────────────────

  /**
   * Map a Waze report alert to its display name segment ID.
   * Always returns a non-null value — unknown types map to "report_hazard".
   */
  static String reportTypeToSegmentId(Alert alert) {
    return switch(alert.type) {
      case "POLICE" -> "report_speed_trap";
      case "ACCIDENT" -> "report_accident";
      case "HAZARD" -> switch(alert.subType) {
        case "HAZARD_ON_ROAD_CONSTRUCTION" -> "report_construction";
        case "HAZARD_ON_ROAD_EMERGENCY_VEHICLE" -> "report_emergency_vehicle";
        case "HAZARD_ON_ROAD_LANE_CLOSED" -> "report_lane_closed";
        case "HAZARD_ON_ROAD_CAR_STOPPED", "HAZARD_ON_SHOULDER_CAR_STOPPED" -> "report_stopped_vehicle";
        case "HAZARD_ON_ROAD_POT_HOLE" -> "report_pothole";
        case "HAZARD_ON_ROAD_OBJECT" -> "report_road_obstacle";
        case "HAZARD_ON_ROAD_ICE" -> "report_ice_on_road";
        case "HAZARD_ON_ROAD_OIL" -> "report_oil_on_road";
        case "HAZARD_ON_ROAD_FLOOD", "HAZARD_WEATHER_FLOOD" -> "report_flooding";
        case "HAZARD_ON_ROAD" -> "report_road_hazard";
        case "HAZARD_ON_SHOULDER_ANIMALS" -> "report_animal_on_road";
        case "HAZARD_ON_SHOULDER_MISSING_SIGN" -> "report_missing_sign";
        case "HAZARD_ON_SHOULDER" -> "report_shoulder_hazard";
        case "HAZARD_WEATHER_FOG" -> "report_fog";
        case "HAZARD_WEATHER_HAIL" -> "report_hail";
        case "HAZARD_WEATHER_HEAVY_RAIN" -> "report_heavy_rain";
        case "HAZARD_WEATHER_HEAVY_SNOW" -> "report_heavy_snow";
        case "HAZARD_WEATHER" -> "report_weather_hazard";
        default -> "report_hazard";
      };
      case "JAM" -> switch(alert.subType) {
        case "JAM_STAND_STILL_TRAFFIC" -> "report_standstill_traffic";
        case "JAM_HEAVY_TRAFFIC" -> "report_heavy_traffic";
        case "JAM_MODERATE_TRAFFIC" -> "report_moderate_traffic";
        default -> "report_traffic_jam";
      };
      // Unknown top-level type — announce as generic hazard
      default -> "report_hazard";
    };
  }

  /**
   * Convert a radar frequency to a key string for complete sentence IDs.
   * E.g. 34.7 -> "34_7", 35.0 -> "35_0"
   */
  static String frequencyToKey(float frequency) {
    float rounded = Math.round(frequency * 10.0f) / 10.0f;
    int whole = (int) rounded;
    int decimal = Math.round((rounded - whole) * 10);
    return String.format("%d_%d", whole, decimal);
  }

  /**
   * Convert a radar frequency (GHz) to an atomic segment ID.
   */
  static String frequencyToSegmentId(float frequency) {
    return "freq_" + frequencyToKey(frequency);
  }

  /**
   * Convert a distance in miles to a pre-recorded segment ID.
   * Clamps to 0.1-5.0 range and rounds to nearest 0.1.
   */
  static String distanceToSegmentId(float distance) {
    float clamped = Math.max(0.1f, Math.min(5.0f, distance));
    float rounded = Math.round(clamped * 10.0f) / 10.0f;
    int whole = (int) rounded;
    int decimal = Math.round((rounded - whole) * 10);
    return String.format("dist_%d_%d", whole, decimal);
  }
}
