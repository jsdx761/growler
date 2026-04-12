package com.jsd.x761.ds1pace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.location.Location;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class AircraftsFetchTaskTest {

  private Location makeLocation(double lat, double lng) {
    Location loc = new Location("test");
    loc.setLatitude(lat);
    loc.setLongitude(lng);
    loc.setBearing(0f);
    return loc;
  }

  /** Build an OpenSky state vector JSONArray with 17 elements. */
  private JSONArray makeStateVector(String transponder, double lng, double lat,
                                    double baroAlt, boolean onGround, Object geoAlt)
      throws Exception {
    JSONArray sv = new JSONArray();
    sv.put(0, transponder);
    sv.put(1, "CALL    ");
    sv.put(2, "United States");
    sv.put(3, 0);
    sv.put(4, 0);
    sv.put(5, lng);
    sv.put(6, lat);
    sv.put(7, baroAlt);
    sv.put(8, onGround);
    sv.put(9, 0);
    sv.put(10, 0);
    sv.put(11, 0);
    sv.put(12, JSONObject.NULL);
    sv.put(13, geoAlt);
    sv.put(14, "1200");
    sv.put(15, false);
    sv.put(16, 0);
    return sv;
  }

  private JSONObject makeResponse(JSONArray... stateVectors) throws Exception {
    JSONArray states = new JSONArray();
    for(JSONArray sv : stateVectors) {
      states.put(sv);
    }
    JSONObject json = new JSONObject();
    json.put("time", 1700000000);
    json.put("states", states);
    return json;
  }

  private Map<String, String[]> makeDatabase(String... csvEntries) {
    Map<String, String[]> db = new HashMap<>();
    for(String entry : csvEntries) {
      String[] parts = entry.split(",");
      db.put(parts[0], parts);
    }
    return db;
  }

  // --- Basic parsing ---

  @Test
  public void parseAircrafts_validAircraft_returned() throws Exception {
    Location vehicle = makeLocation(37.3382, -121.8863);
    Map<String, String[]> db = makeDatabase("abc123,Cessna,L1P,Police Dept");
    // Aircraft ~0.2 miles away, airborne
    JSONObject json = makeResponse(
      makeStateVector("abc123", -121.89, 37.34, 500.0, false, 500.0));

    List<Alert> result = AircraftsFetchTask.parseAircrafts(json, vehicle, db);

    assertNotNull(result);
    assertEquals(1, result.size());
    assertEquals("abc123", result.get(0).transponder);
    assertEquals("Airplane", result.get(0).type);
    assertEquals("Police Dept", result.get(0).owner);
    assertEquals("Cessna", result.get(0).manufacturer);
    assertTrue(result.get(0).distance > 0);
    assertTrue(result.get(0).distance < 5.0f);
  }

  @Test
  public void parseAircrafts_helicopter_correctType() throws Exception {
    Location vehicle = makeLocation(37.3382, -121.8863);
    Map<String, String[]> db = makeDatabase("heli01,Bell,H1S,News Org");
    JSONObject json = makeResponse(
      makeStateVector("heli01", -121.89, 37.34, 300.0, false, 300.0));

    List<Alert> result = AircraftsFetchTask.parseAircrafts(json, vehicle, db);

    assertEquals(1, result.size());
    assertEquals("Helicopter", result.get(0).type);
  }

  @Test
  public void parseAircrafts_drone_correctType() throws Exception {
    Location vehicle = makeLocation(37.3382, -121.8863);
    Map<String, String[]> db = makeDatabase("drn001,DJI,L1E,Unknown");
    JSONObject json = makeResponse(
      makeStateVector("drn001", -121.89, 37.34, 100.0, false, 100.0));

    List<Alert> result = AircraftsFetchTask.parseAircrafts(json, vehicle, db);

    assertEquals(1, result.size());
    assertEquals("Drone", result.get(0).type);
  }

  // --- Filtering ---

  @Test
  public void parseAircrafts_notInDatabase_filtered() throws Exception {
    Location vehicle = makeLocation(37.3382, -121.8863);
    Map<String, String[]> db = makeDatabase("abc123,Cessna,L1P,Police Dept");
    // "unknown" transponder not in database
    JSONObject json = makeResponse(
      makeStateVector("unknown", -121.89, 37.34, 500.0, false, 500.0));

    List<Alert> result = AircraftsFetchTask.parseAircrafts(json, vehicle, db);

    assertNotNull(result);
    assertEquals(0, result.size());
  }

  @Test
  public void parseAircrafts_onGround_filtered() throws Exception {
    Location vehicle = makeLocation(37.3382, -121.8863);
    Map<String, String[]> db = makeDatabase("abc123,Cessna,L1P,Police Dept");
    // Aircraft on the ground
    JSONObject json = makeResponse(
      makeStateVector("abc123", -121.89, 37.34, 0.0, true, 0.0));

    List<Alert> result = AircraftsFetchTask.parseAircrafts(json, vehicle, db);

    assertNotNull(result);
    assertEquals(0, result.size());
  }

  @Test
  public void parseAircrafts_beyondMaxDistance_filtered() throws Exception {
    Location vehicle = makeLocation(37.3382, -121.8863);
    Map<String, String[]> db = makeDatabase("abc123,Cessna,L1P,Police Dept");
    // Aircraft ~11 miles away (well beyond 5-mile max)
    JSONObject json = makeResponse(
      makeStateVector("abc123", -121.9, 37.5, 3000.0, false, 3000.0));

    List<Alert> result = AircraftsFetchTask.parseAircrafts(json, vehicle, db);

    assertNotNull(result);
    assertEquals(0, result.size());
  }

  @Test
  public void parseAircrafts_multipleAircrafts_mixedFiltering() throws Exception {
    Location vehicle = makeLocation(37.3382, -121.8863);
    Map<String, String[]> db = makeDatabase(
      "close1,Cessna,L1P,Police Dept",
      "ground,Bell,H1S,News Org",
      "far01,Piper,L1P,Sheriff");

    JSONObject json = makeResponse(
      // In range, airborne -> kept
      makeStateVector("close1", -121.89, 37.34, 500.0, false, 500.0),
      // In range but on ground -> filtered
      makeStateVector("ground", -121.89, 37.34, 0.0, true, 0.0),
      // Airborne but too far -> filtered
      makeStateVector("far01", -121.9, 37.5, 3000.0, false, 3000.0),
      // Not in database -> filtered
      makeStateVector("nodb1", -121.89, 37.34, 500.0, false, 500.0));

    List<Alert> result = AircraftsFetchTask.parseAircrafts(json, vehicle, db);

    assertNotNull(result);
    assertEquals(1, result.size());
    assertEquals("close1", result.get(0).transponder);
  }

  // --- Empty / missing states ---

  @Test
  public void parseAircrafts_emptyStates_returnsEmptyList() throws Exception {
    Location vehicle = makeLocation(37.3382, -121.8863);
    Map<String, String[]> db = makeDatabase("abc123,Cessna,L1P,Police Dept");
    JSONObject json = new JSONObject();
    json.put("time", 1700000000);
    json.put("states", new JSONArray());

    List<Alert> result = AircraftsFetchTask.parseAircrafts(json, vehicle, db);

    assertNotNull(result);
    assertEquals(0, result.size());
  }

  @Test
  public void parseAircrafts_nullStates_returnsEmptyList() throws Exception {
    Location vehicle = makeLocation(37.3382, -121.8863);
    Map<String, String[]> db = makeDatabase("abc123,Cessna,L1P,Police Dept");
    JSONObject json = new JSONObject();
    json.put("time", 1700000000);
    json.put("states", JSONObject.NULL);

    List<Alert> result = AircraftsFetchTask.parseAircrafts(json, vehicle, db);

    assertNotNull(result);
    assertEquals(0, result.size());
  }

  @Test
  public void parseAircrafts_noStatesKey_returnsEmptyList() throws Exception {
    Location vehicle = makeLocation(37.3382, -121.8863);
    Map<String, String[]> db = makeDatabase("abc123,Cessna,L1P,Police Dept");
    JSONObject json = new JSONObject();
    json.put("time", 1700000000);

    List<Alert> result = AircraftsFetchTask.parseAircrafts(json, vehicle, db);

    assertNotNull(result);
    assertEquals(0, result.size());
  }

  @Test
  public void parseAircrafts_emptyDatabase_returnsEmptyList() throws Exception {
    Location vehicle = makeLocation(37.3382, -121.8863);
    Map<String, String[]> db = new HashMap<>();
    JSONObject json = makeResponse(
      makeStateVector("abc123", -121.89, 37.34, 500.0, false, 500.0));

    List<Alert> result = AircraftsFetchTask.parseAircrafts(json, vehicle, db);

    assertNotNull(result);
    assertEquals(0, result.size());
  }

  // --- Malformed data ---

  @Test
  public void parseAircrafts_malformedStateVector_skippedGracefully() throws Exception {
    Location vehicle = makeLocation(37.3382, -121.8863);
    Map<String, String[]> db = makeDatabase(
      "good01,Cessna,L1P,Police Dept",
      "bad001,Bell,H1S,News Org");

    // Build a response with one good and one malformed state vector
    JSONArray states = new JSONArray();
    // Good entry
    states.put(makeStateVector("good01", -121.89, 37.34, 500.0, false, 500.0));
    // Malformed entry (empty array)
    states.put(new JSONArray());
    JSONObject json = new JSONObject();
    json.put("time", 1700000000);
    json.put("states", states);

    List<Alert> result = AircraftsFetchTask.parseAircrafts(json, vehicle, db);

    assertNotNull(result);
    assertEquals(1, result.size());
    assertEquals("good01", result.get(0).transponder);
  }

  @Test
  public void parseAircrafts_geoAltitudeNull_fallsBackToBaro() throws Exception {
    Location vehicle = makeLocation(37.3382, -121.8863);
    Map<String, String[]> db = makeDatabase("abc123,Cessna,L1P,Police Dept");
    // geo_altitude is null, baro_altitude is 1500
    JSONObject json = makeResponse(
      makeStateVector("abc123", -121.89, 37.34, 1500.0, false, JSONObject.NULL));

    List<Alert> result = AircraftsFetchTask.parseAircrafts(json, vehicle, db);

    assertNotNull(result);
    assertEquals(1, result.size());
    assertEquals(1500.0f, result.get(0).altitude, 1.0f);
  }

  // --- Distance and bearing computation ---

  @Test
  public void parseAircrafts_computesDistanceAndBearing() throws Exception {
    Location vehicle = makeLocation(37.3382, -121.8863);
    vehicle.setBearing(0f); // heading north
    Map<String, String[]> db = makeDatabase("abc123,Cessna,L1P,Police Dept");
    // Aircraft slightly north-east of vehicle
    JSONObject json = makeResponse(
      makeStateVector("abc123", -121.88, 37.35, 500.0, false, 500.0));

    List<Alert> result = AircraftsFetchTask.parseAircrafts(json, vehicle, db);

    assertEquals(1, result.size());
    assertTrue("Distance should be positive", result.get(0).distance > 0);
    assertTrue("Bearing should be set", result.get(0).bearing >= 1 && result.get(0).bearing <= 12);
  }

  // --- Realistic OpenSky response ---

  @Test
  public void parseAircrafts_openSkyFormat_parsesRealResponse() throws Exception {
    Location vehicle = makeLocation(37.3382, -121.8863);
    Map<String, String[]> db = makeDatabase(
      "a6165b,Cessna,L1P,Local Police",
      "a54f11,Robinson,H1S,News Helicopter");

    String openSkyJson = """
      {
        "time": 1700000000,
        "states": [
          [
            "a6165b", "N505FC  ", "United States", 0, 0,
            -121.93, 37.36, 1676.4, false, 123.89,
            274.76, -5.2, null, 1653.54, "7253", false, 0
          ],
          [
            "a54f11", "N362Q   ", "United States", 0, 0,
            -121.89, 37.35, 259.08, false, 43.66,
            145.56, 0.33, null, 205.74, "1200", false, 0
          ],
          [
            "unknown", "SWA458  ", "United States", 0, 0,
            -122.2, 37.71, null, true, 0,
            306.56, null, null, null, null, false, 0
          ]
        ]
      }
    """;
    JSONObject json = new JSONObject(openSkyJson);

    List<Alert> result = AircraftsFetchTask.parseAircrafts(json, vehicle, db);

    assertNotNull(result);
    // Two known aircraft in range and airborne; "unknown" filtered (not in db)
    assertEquals(2, result.size());
    assertEquals("a6165b", result.get(0).transponder);
    assertEquals("Airplane", result.get(0).type);
    assertEquals("a54f11", result.get(1).transponder);
    assertEquals("Helicopter", result.get(1).type);
  }
}
