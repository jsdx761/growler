package com.jsd.x761.ds1pace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.location.Location;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class AlertTest {

  private Location makeLocation(double lat, double lng) {
    Location loc = new Location("test");
    loc.setLatitude(lat);
    loc.setLongitude(lng);
    loc.setBearing(0f);
    return loc;
  }

  // --- fromReport (JSONObject) ---

  @Test
  public void fromReport_json_parsesPoliceAlert() throws Exception {
    Location vehicle = makeLocation(37.3382, -121.8863);
    JSONObject json = new JSONObject();
    json.put("type", "POLICE");
    json.put("subtype", "POLICE_VISIBLE");
    json.put("city", "San Jose, CA");
    json.put("street", "US-101 S");
    json.put("nThumbsUp", 3);
    JSONObject location = new JSONObject();
    location.put("x", -121.89);
    location.put("y", 37.34);
    json.put("location", location);

    Alert alert = Alert.fromReport(vehicle, json);

    assertEquals(Alert.ALERT_CLASS_REPORT, alert.alertClass);
    assertEquals("POLICE", alert.type);
    assertEquals("POLICE_VISIBLE", alert.subType);
    assertEquals("San Jose", alert.city);
    assertEquals("US-101 S", alert.street);
    assertEquals(3, alert.thumbsUp);
    assertEquals(-121.89, alert.longitude, 0.001);
    assertEquals(37.34, alert.latitude, 0.001);
    assertTrue(alert.distance > 0);
    assertTrue(alert.priority > 0);
  }

  @Test
  public void fromReport_json_parsesAccidentAlert() throws Exception {
    Location vehicle = makeLocation(37.3382, -121.8863);
    JSONObject json = new JSONObject();
    json.put("type", "ACCIDENT");
    json.put("subtype", "");
    json.put("city", "Sunnyvale, CA");
    json.put("street", "to I-280 N");
    JSONObject location = new JSONObject();
    location.put("x", -122.0);
    location.put("y", 37.4);
    json.put("location", location);

    Alert alert = Alert.fromReport(vehicle, json);

    assertEquals("ACCIDENT", alert.type);
    assertEquals("Sunnyvale", alert.city);
    // "to " prefix should be stripped from street
    assertEquals("I-280 N", alert.street);
  }

  @Test
  public void fromReport_json_stripsStateFromCity() throws Exception {
    Location vehicle = makeLocation(37.3382, -121.8863);
    JSONObject json = new JSONObject();
    json.put("type", "POLICE");
    json.put("city", "San Francisco, CA");
    JSONObject location = new JSONObject();
    location.put("x", -122.4);
    location.put("y", 37.7);
    json.put("location", location);

    Alert alert = Alert.fromReport(vehicle, json);
    assertEquals("San Francisco", alert.city);
  }

  @Test
  public void fromReport_json_cityWithoutState_unchanged() throws Exception {
    Location vehicle = makeLocation(37.3382, -121.8863);
    JSONObject json = new JSONObject();
    json.put("type", "POLICE");
    json.put("city", "San Jose");
    JSONObject location = new JSONObject();
    location.put("x", -121.89);
    location.put("y", 37.34);
    json.put("location", location);

    Alert alert = Alert.fromReport(vehicle, json);
    assertEquals("San Jose", alert.city);
  }

  @Test
  public void fromReport_json_missingOptionalFields_defaults() throws Exception {
    Location vehicle = makeLocation(37.3382, -121.8863);
    JSONObject json = new JSONObject();
    json.put("type", "POLICE");
    JSONObject location = new JSONObject();
    location.put("x", -121.89);
    location.put("y", 37.34);
    json.put("location", location);

    Alert alert = Alert.fromReport(vehicle, json);

    assertEquals("POLICE", alert.type);
    assertEquals("", alert.subType);
    assertEquals("", alert.city);
    assertEquals("", alert.street);
    assertEquals(0, alert.thumbsUp);
  }

  @Test
  public void fromReport_json_computesBearing() throws Exception {
    // Target due east of vehicle -> should be 3 o'clock (vehicle heading north)
    Location vehicle = makeLocation(37.0, -122.0);
    vehicle.setBearing(0f);
    JSONObject json = new JSONObject();
    json.put("type", "POLICE");
    JSONObject location = new JSONObject();
    location.put("x", -121.98);
    location.put("y", 37.0);
    json.put("location", location);

    Alert alert = Alert.fromReport(vehicle, json);
    assertEquals(3, alert.bearing);
  }

  @Test
  public void fromReport_json_computesBearing_vehicleHeadingEast() throws Exception {
    // Vehicle heading east, target due north -> should be 9 o'clock (to the left)
    Location vehicle = makeLocation(37.0, -122.0);
    vehicle.setBearing(90f);
    JSONObject json = new JSONObject();
    json.put("type", "POLICE");
    JSONObject location = new JSONObject();
    location.put("x", -122.0);
    location.put("y", 37.02);
    json.put("location", location);

    Alert alert = Alert.fromReport(vehicle, json);
    assertEquals(9, alert.bearing);
  }

  // --- fromReport (Alert) ---

  @Test
  public void fromReport_alert_updatesDistanceAndBearing() throws Exception {
    Location vehicle1 = makeLocation(37.3382, -121.8863);
    JSONObject json = new JSONObject();
    json.put("type", "POLICE");
    json.put("subtype", "POLICE_VISIBLE");
    json.put("city", "San Jose, CA");
    json.put("street", "Main St");
    JSONObject location = new JSONObject();
    location.put("x", -121.89);
    location.put("y", 37.35);
    json.put("location", location);

    Alert original = Alert.fromReport(vehicle1, json);
    float originalDistance = original.distance;

    // Move vehicle closer to the report
    Location vehicle2 = makeLocation(37.345, -121.89);
    Alert updated = Alert.fromReport(vehicle2, original);

    assertEquals("POLICE", updated.type);
    assertEquals("POLICE_VISIBLE", updated.subType);
    assertEquals("San Jose", updated.city);
    assertEquals("Main St", updated.street);
    assertEquals(original.longitude, updated.longitude, 0.001);
    assertEquals(original.latitude, updated.latitude, 0.001);
    assertTrue(updated.distance < originalDistance);
  }

  // --- fromAircraft (JSONArray) ---

  @Test
  public void fromAircraft_json_parsesAirplane() throws Exception {
    Location vehicle = makeLocation(37.3382, -121.8863);
    JSONArray jsonAircraft = new JSONArray();
    jsonAircraft.put(0, "a6165b");     // transponder
    jsonAircraft.put(1, "N505FC  ");   // callSign
    jsonAircraft.put(2, "United States");
    jsonAircraft.put(3, 0);
    jsonAircraft.put(4, 0);
    jsonAircraft.put(5, -121.93);      // longitude
    jsonAircraft.put(6, 37.36);        // latitude
    jsonAircraft.put(7, 1676.4);       // altitude (baro)
    jsonAircraft.put(8, false);        // onGround
    jsonAircraft.put(9, 123.89);
    jsonAircraft.put(10, 274.76);
    jsonAircraft.put(11, -5.2);
    jsonAircraft.put(12, JSONObject.NULL);
    jsonAircraft.put(13, 1653.54);     // altitude (geo)
    jsonAircraft.put(14, "7253");
    jsonAircraft.put(15, false);
    jsonAircraft.put(16, 0);

    // Aircraft info: [transponder, manufacturer, icao, owner]
    String[] aircraftInfo = {"a6165b", "Cessna", "L1P", "Local Police Dept"};

    Alert alert = Alert.fromAircraft(vehicle, jsonAircraft, aircraftInfo);

    assertEquals(Alert.ALERT_CLASS_AIRCRAFT, alert.alertClass);
    assertEquals("a6165b", alert.transponder);
    assertEquals("N505FC  ", alert.callSign);
    assertFalse(alert.onGround);
    assertEquals("Local Police Dept", alert.owner);
    assertEquals("Cessna", alert.manufacturer);
    assertEquals("Airplane", alert.type); // L1P matches ^[LSAT]..$
    assertEquals(1653.54f, alert.altitude, 0.1f);
    assertTrue(alert.distance > 0);
  }

  @Test
  public void fromAircraft_json_helicopter() throws Exception {
    Location vehicle = makeLocation(37.3382, -121.8863);
    JSONArray jsonAircraft = new JSONArray();
    jsonAircraft.put(0, "abc123");
    jsonAircraft.put(1, "HELO1   ");
    jsonAircraft.put(2, "United States");
    jsonAircraft.put(3, 0);
    jsonAircraft.put(4, 0);
    jsonAircraft.put(5, -121.9);
    jsonAircraft.put(6, 37.34);
    jsonAircraft.put(7, 500.0);
    jsonAircraft.put(8, false);
    jsonAircraft.put(9, 0);
    jsonAircraft.put(10, 0);
    jsonAircraft.put(11, 0);
    jsonAircraft.put(12, JSONObject.NULL);
    jsonAircraft.put(13, 500.0);
    jsonAircraft.put(14, "1200");
    jsonAircraft.put(15, false);
    jsonAircraft.put(16, 0);

    String[] aircraftInfo = {"abc123", "Bell", "H1S", "News Org"};

    Alert alert = Alert.fromAircraft(vehicle, jsonAircraft, aircraftInfo);
    assertEquals("Helicopter", alert.type); // H1S matches ^[HG]..$
    assertEquals("News Org", alert.owner);
  }

  @Test
  public void fromAircraft_json_drone() throws Exception {
    Location vehicle = makeLocation(37.3382, -121.8863);
    JSONArray jsonAircraft = new JSONArray();
    jsonAircraft.put(0, "def456");
    jsonAircraft.put(1, "DRN1    ");
    jsonAircraft.put(2, "United States");
    jsonAircraft.put(3, 0);
    jsonAircraft.put(4, 0);
    jsonAircraft.put(5, -121.9);
    jsonAircraft.put(6, 37.34);
    jsonAircraft.put(7, 100.0);
    jsonAircraft.put(8, false);
    jsonAircraft.put(9, 0);
    jsonAircraft.put(10, 0);
    jsonAircraft.put(11, 0);
    jsonAircraft.put(12, JSONObject.NULL);
    jsonAircraft.put(13, 100.0);
    jsonAircraft.put(14, "1200");
    jsonAircraft.put(15, false);
    jsonAircraft.put(16, 0);

    String[] aircraftInfo = {"def456", "DJI", "L1E", "Unknown"}; // ends with E = Drone

    Alert alert = Alert.fromAircraft(vehicle, jsonAircraft, aircraftInfo);
    assertEquals("Drone", alert.type);
  }

  @Test
  public void fromAircraft_json_nullAircraftInfo_defaultsToAircraft() throws Exception {
    Location vehicle = makeLocation(37.3382, -121.8863);
    JSONArray jsonAircraft = new JSONArray();
    jsonAircraft.put(0, "xyz789");
    jsonAircraft.put(1, "TEST    ");
    jsonAircraft.put(2, "United States");
    jsonAircraft.put(3, 0);
    jsonAircraft.put(4, 0);
    jsonAircraft.put(5, -121.9);
    jsonAircraft.put(6, 37.34);
    jsonAircraft.put(7, 1000.0);
    jsonAircraft.put(8, false);
    jsonAircraft.put(9, 0);
    jsonAircraft.put(10, 0);
    jsonAircraft.put(11, 0);
    jsonAircraft.put(12, JSONObject.NULL);
    jsonAircraft.put(13, 1000.0);
    jsonAircraft.put(14, "1200");
    jsonAircraft.put(15, false);
    jsonAircraft.put(16, 0);

    Alert alert = Alert.fromAircraft(vehicle, jsonAircraft, null);
    assertEquals("Aircraft", alert.type);
    assertEquals("", alert.owner);
  }

  // --- fromAircraft (Alert) ---

  @Test
  public void fromAircraft_alert_updatesDistanceAndBearing() throws Exception {
    Location vehicle1 = makeLocation(37.3382, -121.8863);
    JSONArray jsonAircraft = new JSONArray();
    jsonAircraft.put(0, "a6165b");
    jsonAircraft.put(1, "N505FC  ");
    jsonAircraft.put(2, "United States");
    jsonAircraft.put(3, 0);
    jsonAircraft.put(4, 0);
    jsonAircraft.put(5, -121.93);
    jsonAircraft.put(6, 37.36);
    jsonAircraft.put(7, 1676.4);
    jsonAircraft.put(8, false);
    jsonAircraft.put(9, 0);
    jsonAircraft.put(10, 0);
    jsonAircraft.put(11, 0);
    jsonAircraft.put(12, JSONObject.NULL);
    jsonAircraft.put(13, 1653.54);
    jsonAircraft.put(14, "7253");
    jsonAircraft.put(15, false);
    jsonAircraft.put(16, 0);

    String[] aircraftInfo = {"a6165b", "Cessna", "L1P", "Police Dept"};
    Alert original = Alert.fromAircraft(vehicle1, jsonAircraft, aircraftInfo);
    float originalDistance = original.distance;

    // Move vehicle closer
    Location vehicle2 = makeLocation(37.355, -121.93);
    Alert updated = Alert.fromAircraft(vehicle2, original);

    assertEquals("a6165b", updated.transponder);
    assertEquals("Police Dept", updated.owner);
    assertEquals("Cessna", updated.manufacturer);
    assertTrue(updated.distance < originalDistance);
  }

  // --- isDuplicateReport ---

  @Test
  public void isDuplicateReport_sameLocation_isTrue() {
    Alert a = new Alert();
    a.alertClass = Alert.ALERT_CLASS_REPORT;
    a.type = "POLICE";
    a.latitude = 37.3382;
    a.longitude = -121.8863;

    Alert b = new Alert();
    b.alertClass = Alert.ALERT_CLASS_REPORT;
    b.type = "POLICE";
    b.latitude = 37.3382;
    b.longitude = -121.8863;

    assertTrue(a.isDuplicateReport(b));
  }

  @Test
  public void isDuplicateReport_closeBy_isTrue() {
    Alert a = new Alert();
    a.alertClass = Alert.ALERT_CLASS_REPORT;
    a.type = "POLICE";
    a.latitude = 37.3382;
    a.longitude = -121.8863;

    Alert b = new Alert();
    b.alertClass = Alert.ALERT_CLASS_REPORT;
    b.type = "POLICE";
    b.latitude = 37.3385;  // ~100 feet away
    b.longitude = -121.8865;

    assertTrue(a.isDuplicateReport(b));
  }

  @Test
  public void isDuplicateReport_farAway_isFalse() {
    Alert a = new Alert();
    a.alertClass = Alert.ALERT_CLASS_REPORT;
    a.type = "POLICE";
    a.latitude = 37.3382;
    a.longitude = -121.8863;

    Alert b = new Alert();
    b.alertClass = Alert.ALERT_CLASS_REPORT;
    b.type = "POLICE";
    b.latitude = 37.36;  // ~1.5 miles away
    b.longitude = -121.89;

    assertFalse(a.isDuplicateReport(b));
  }

  @Test
  public void isDuplicateReport_differentType_isFalse() {
    Alert a = new Alert();
    a.alertClass = Alert.ALERT_CLASS_REPORT;
    a.type = "POLICE";
    a.latitude = 37.3382;
    a.longitude = -121.8863;

    Alert b = new Alert();
    b.alertClass = Alert.ALERT_CLASS_REPORT;
    b.type = "ACCIDENT";
    b.latitude = 37.3382;
    b.longitude = -121.8863;

    assertFalse(a.isDuplicateReport(b));
  }

  @Test
  public void isDuplicateReport_differentAlertClass_isFalse() {
    Alert a = new Alert();
    a.alertClass = Alert.ALERT_CLASS_REPORT;
    a.type = "POLICE";
    a.latitude = 37.3382;
    a.longitude = -121.8863;

    Alert b = new Alert();
    b.alertClass = Alert.ALERT_CLASS_RADAR;
    b.type = "POLICE";
    b.latitude = 37.3382;
    b.longitude = -121.8863;

    assertFalse(a.isDuplicateReport(b));
  }

  // --- isSameReport ---

  @Test
  public void isSameReport_identicalFields_isTrue() {
    Alert a = new Alert();
    a.alertClass = Alert.ALERT_CLASS_REPORT;
    a.city = "San Jose";
    a.street = "Main St";
    a.latitude = 37.3382;
    a.longitude = -121.8863;

    Alert b = new Alert();
    b.alertClass = Alert.ALERT_CLASS_REPORT;
    b.city = "San Jose";
    b.street = "Main St";
    b.latitude = 37.3382;
    b.longitude = -121.8863;

    assertTrue(a.isSameReport(b));
  }

  @Test
  public void isSameReport_differentStreet_isFalse() {
    Alert a = new Alert();
    a.alertClass = Alert.ALERT_CLASS_REPORT;
    a.city = "San Jose";
    a.street = "Main St";
    a.latitude = 37.3382;
    a.longitude = -121.8863;

    Alert b = new Alert();
    b.alertClass = Alert.ALERT_CLASS_REPORT;
    b.city = "San Jose";
    b.street = "1st St";
    b.latitude = 37.3382;
    b.longitude = -121.8863;

    assertFalse(a.isSameReport(b));
  }

  @Test
  public void isSameReport_differentCoordinates_isFalse() {
    Alert a = new Alert();
    a.alertClass = Alert.ALERT_CLASS_REPORT;
    a.city = "San Jose";
    a.street = "Main St";
    a.latitude = 37.3382;
    a.longitude = -121.8863;

    Alert b = new Alert();
    b.alertClass = Alert.ALERT_CLASS_REPORT;
    b.city = "San Jose";
    b.street = "Main St";
    b.latitude = 37.34;
    b.longitude = -121.8863;

    assertFalse(a.isSameReport(b));
  }

  // --- isSameAircraft ---

  @Test
  public void isSameAircraft_sameTransponder_isTrue() {
    Alert a = new Alert();
    a.alertClass = Alert.ALERT_CLASS_AIRCRAFT;
    a.transponder = "a6165b";

    Alert b = new Alert();
    b.alertClass = Alert.ALERT_CLASS_AIRCRAFT;
    b.transponder = "a6165b";

    assertTrue(a.isSameAircraft(b));
  }

  @Test
  public void isSameAircraft_differentTransponder_isFalse() {
    Alert a = new Alert();
    a.alertClass = Alert.ALERT_CLASS_AIRCRAFT;
    a.transponder = "a6165b";

    Alert b = new Alert();
    b.alertClass = Alert.ALERT_CLASS_AIRCRAFT;
    b.transponder = "b7276c";

    assertFalse(a.isSameAircraft(b));
  }

  // --- shouldAnnounceReport ---

  @Test
  public void shouldAnnounceReport_neverAnnounced_isTrue() {
    Alert a = new Alert();
    a.announced = 0;
    assertTrue(a.shouldAnnounceReport());
  }

  @Test
  public void shouldAnnounceReport_distanceDecreased_isTrue() {
    Alert a = new Alert();
    a.announced = 1;
    a.announceDistance = 2.0f;
    a.distance = 1.5f; // Closed by 0.5 miles >= REPORTS_REMINDER_DISTANCE (0.25)
    a.announceBearing = 12;
    a.bearing = 12;
    assertTrue(a.shouldAnnounceReport());
  }

  @Test
  public void shouldAnnounceReport_distanceNotEnough_isFalse() {
    Alert a = new Alert();
    a.announced = 1;
    a.announceDistance = 2.0f;
    a.distance = 1.9f; // Only 0.1 miles closer, less than 0.25
    a.announceBearing = 12;
    a.bearing = 12;
    assertFalse(a.shouldAnnounceReport());
  }

  @Test
  public void shouldAnnounceReport_bearingChanged_isTrue() {
    Alert a = new Alert();
    a.announced = 1;
    a.announceDistance = 2.0f;
    a.distance = 2.0f; // No distance change
    a.announceBearing = 12;
    a.bearing = 3; // Changed by 3 hours
    assertTrue(a.shouldAnnounceReport());
  }

  @Test
  public void shouldAnnounceReport_bearingNotEnough_isFalse() {
    Alert a = new Alert();
    a.announced = 1;
    a.announceDistance = 2.0f;
    a.distance = 2.0f;
    a.announceBearing = 12;
    a.bearing = 1; // Only 1 hour change, less than 3
    assertFalse(a.shouldAnnounceReport());
  }

  // --- shouldAnnounceAircraft ---

  @Test
  public void shouldAnnounceAircraft_neverAnnounced_isTrue() {
    Alert a = new Alert();
    a.announced = 0;
    assertTrue(a.shouldAnnounceAircraft());
  }

  @Test
  public void shouldAnnounceAircraft_distanceDecreased_isTrue() {
    Alert a = new Alert();
    a.announced = 1;
    a.announceDistance = 5.0f;
    a.distance = 3.5f; // Closed by 1.5 miles >= AIRCRAFTS_REMINDER_DISTANCE (1.0)
    a.announceBearing = 12;
    a.bearing = 12;
    assertTrue(a.shouldAnnounceAircraft());
  }

  @Test
  public void shouldAnnounceAircraft_distanceNotEnough_isFalse() {
    Alert a = new Alert();
    a.announced = 1;
    a.announceDistance = 5.0f;
    a.distance = 4.5f; // Only 0.5 miles closer, less than 1.0
    a.announceBearing = 12;
    a.bearing = 12;
    assertFalse(a.shouldAnnounceAircraft());
  }

  // --- toDebugString ---

  @Test
  public void toDebugString_report() {
    Alert a = new Alert();
    a.alertClass = Alert.ALERT_CLASS_REPORT;
    assertEquals("report", a.toDebugString());
  }

  @Test
  public void toDebugString_aircraft() {
    Alert a = new Alert();
    a.alertClass = Alert.ALERT_CLASS_AIRCRAFT;
    assertEquals("aircraft", a.toDebugString());
  }

  @Test
  public void toDebugString_radar() {
    Alert a = new Alert();
    a.alertClass = Alert.ALERT_CLASS_RADAR;
    assertEquals("alert", a.toDebugString());
  }

  // --- Waze Live Map JSON format compatibility ---

  @Test
  public void fromReport_wazeFormat_parsesAllFields() throws Exception {
    // Exact JSON format returned by the Waze live-map API
    String wazeJson = """
      {
        "country": "US",
        "nThumbsUp": 4,
        "city": "Richmond, CA",
        "reportRating": 2,
        "reportByMunicipalityUser": "false",
        "reliability": 10,
        "type": "POLICE",
        "fromNodeId": 4831079,
        "uuid": "4e009ca4-621f-499b-812e-b0473edbe55d",
        "speed": 0,
        "reportMood": 144,
        "subtype": "POLICE_HIDING",
        "street": "to I-80 E",
        "additionalInfo": "",
        "toNodeId": 4867199,
        "location": {
          "x": -122.074604,
          "y": 37.915422
        },
        "id": "alert-1305945917/4e009ca4-621f-499b-812e-b0473edbe55d",
        "pubMillis": 1773207271000,
        "confidence": 0,
        "roadType": 1,
        "magvar": 359,
        "wazeData": "usa,-122.074604,37.915422,06de5d15"
      }
    """;

    Location vehicle = makeLocation(37.9, -122.0);
    JSONObject json = new JSONObject(wazeJson);
    Alert alert = Alert.fromReport(vehicle, json);

    assertEquals(Alert.ALERT_CLASS_REPORT, alert.alertClass);
    assertEquals("POLICE", alert.type);
    assertEquals("POLICE_HIDING", alert.subType);
    assertEquals("Richmond", alert.city);
    assertEquals("I-80 E", alert.street); // "to " prefix stripped
    assertEquals(4, alert.thumbsUp);
    assertEquals(-122.074604, alert.longitude, 0.000001);
    assertEquals(37.915422, alert.latitude, 0.000001);
    assertTrue(alert.distance > 0);
    assertTrue(alert.bearing >= 1 && alert.bearing <= 12);
  }

  @Test
  public void fromReport_wazeFormat_accident() throws Exception {
    String wazeJson = """
      {
        "country": "US",
        "inscale": false,
        "city": "San Francisco, CA",
        "reportRating": 1,
        "type": "ACCIDENT",
        "subtype": "",
        "street": "Utah St",
        "location": {
          "x": -122.405395,
          "y": 37.753474
        }
      }
    """;

    Location vehicle = makeLocation(37.75, -122.4);
    JSONObject json = new JSONObject(wazeJson);
    Alert alert = Alert.fromReport(vehicle, json);

    assertEquals("ACCIDENT", alert.type);
    assertEquals("San Francisco", alert.city);
    assertEquals("Utah St", alert.street);
    assertEquals(0, alert.thumbsUp); // Not present in JSON
  }

  // --- getDisplayName ---

  @Test
  public void getDisplayName_police() {
    Alert a = new Alert();
    a.type = "POLICE";
    a.subType = "";
    assertEquals("Speed Trap", a.getDisplayName());
  }

  @Test
  public void getDisplayName_policeHiding() {
    Alert a = new Alert();
    a.type = "POLICE";
    a.subType = "POLICE_HIDING";
    assertEquals("Speed Trap", a.getDisplayName());
  }

  @Test
  public void getDisplayName_accident() {
    Alert a = new Alert();
    a.type = "ACCIDENT";
    a.subType = "";
    assertEquals("Accident", a.getDisplayName());
  }

  @Test
  public void getDisplayName_hazardConstruction() {
    Alert a = new Alert();
    a.type = "HAZARD";
    a.subType = "HAZARD_ON_ROAD_CONSTRUCTION";
    assertEquals("Construction", a.getDisplayName());
  }

  @Test
  public void getDisplayName_hazardEmergencyVehicle() {
    Alert a = new Alert();
    a.type = "HAZARD";
    a.subType = "HAZARD_ON_ROAD_EMERGENCY_VEHICLE";
    assertEquals("Emergency Vehicle", a.getDisplayName());
  }

  @Test
  public void getDisplayName_hazardLaneClosed() {
    Alert a = new Alert();
    a.type = "HAZARD";
    a.subType = "HAZARD_ON_ROAD_LANE_CLOSED";
    assertEquals("Lane Closed", a.getDisplayName());
  }

  @Test
  public void getDisplayName_hazardStoppedVehicle() {
    Alert a = new Alert();
    a.type = "HAZARD";
    a.subType = "HAZARD_ON_ROAD_CAR_STOPPED";
    assertEquals("Stopped Vehicle", a.getDisplayName());

    a.subType = "HAZARD_ON_SHOULDER_CAR_STOPPED";
    assertEquals("Stopped Vehicle", a.getDisplayName());
  }

  @Test
  public void getDisplayName_hazardPothole() {
    Alert a = new Alert();
    a.type = "HAZARD";
    a.subType = "HAZARD_ON_ROAD_POT_HOLE";
    assertEquals("Pothole", a.getDisplayName());
  }

  @Test
  public void getDisplayName_hazardObject() {
    Alert a = new Alert();
    a.type = "HAZARD";
    a.subType = "HAZARD_ON_ROAD_OBJECT";
    assertEquals("Road Obstacle", a.getDisplayName());
  }

  @Test
  public void getDisplayName_hazardIce() {
    Alert a = new Alert();
    a.type = "HAZARD";
    a.subType = "HAZARD_ON_ROAD_ICE";
    assertEquals("Ice on Road", a.getDisplayName());
  }

  @Test
  public void getDisplayName_hazardFlooding() {
    Alert a = new Alert();
    a.type = "HAZARD";
    a.subType = "HAZARD_ON_ROAD_FLOOD";
    assertEquals("Flooding", a.getDisplayName());

    a.subType = "HAZARD_WEATHER_FLOOD";
    assertEquals("Flooding", a.getDisplayName());
  }

  @Test
  public void getDisplayName_hazardWeather() {
    Alert a = new Alert();
    a.type = "HAZARD";

    a.subType = "HAZARD_WEATHER_FOG";
    assertEquals("Fog", a.getDisplayName());

    a.subType = "HAZARD_WEATHER_HEAVY_RAIN";
    assertEquals("Heavy Rain", a.getDisplayName());

    a.subType = "HAZARD_WEATHER_HEAVY_SNOW";
    assertEquals("Heavy Snow", a.getDisplayName());

    a.subType = "HAZARD_WEATHER_HAIL";
    assertEquals("Hail", a.getDisplayName());

    a.subType = "HAZARD_WEATHER";
    assertEquals("Weather Hazard", a.getDisplayName());
  }

  @Test
  public void getDisplayName_hazardAnimalOnRoad() {
    Alert a = new Alert();
    a.type = "HAZARD";
    a.subType = "HAZARD_ON_SHOULDER_ANIMALS";
    assertEquals("Animal on Road", a.getDisplayName());
  }

  @Test
  public void getDisplayName_hazardGeneric() {
    Alert a = new Alert();
    a.type = "HAZARD";
    a.subType = "";
    assertEquals("Hazard", a.getDisplayName());

    a.subType = "HAZARD_UNKNOWN_SUBTYPE";
    assertEquals("Hazard", a.getDisplayName());
  }

  @Test
  public void getDisplayName_jamHeavyTraffic() {
    Alert a = new Alert();
    a.type = "JAM";
    a.subType = "JAM_HEAVY_TRAFFIC";
    assertEquals("Heavy Traffic", a.getDisplayName());
  }

  @Test
  public void getDisplayName_jamStandstill() {
    Alert a = new Alert();
    a.type = "JAM";
    a.subType = "JAM_STAND_STILL_TRAFFIC";
    assertEquals("Standstill Traffic", a.getDisplayName());
  }

  @Test
  public void getDisplayName_jamModerate() {
    Alert a = new Alert();
    a.type = "JAM";
    a.subType = "JAM_MODERATE_TRAFFIC";
    assertEquals("Moderate Traffic", a.getDisplayName());
  }

  @Test
  public void getDisplayName_jamGeneric() {
    Alert a = new Alert();
    a.type = "JAM";
    a.subType = "";
    assertEquals("Traffic Jam", a.getDisplayName());
  }
}
