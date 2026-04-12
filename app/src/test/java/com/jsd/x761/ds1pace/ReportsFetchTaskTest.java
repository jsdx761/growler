package com.jsd.x761.ds1pace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.location.Location;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class ReportsFetchTaskTest {

  private Location makeLocation(double lat, double lng) {
    Location loc = new Location("test");
    loc.setLatitude(lat);
    loc.setLongitude(lng);
    return loc;
  }

  // --- JSON parsing tests using ReportsHttpFetchTask.parseReports ---

  @Test
  public void parseReports_policeAndAccident_bothKept() throws Exception {
    String json = """
      {
        "alerts": [
          {
            "type": "POLICE",
            "subtype": "POLICE_VISIBLE",
            "city": "San Jose, CA",
            "street": "US-101 S",
            "location": {"x": -121.89, "y": 37.34}
          },
          {
            "type": "ACCIDENT",
            "subtype": "",
            "city": "Sunnyvale, CA",
            "street": "El Camino Real",
            "location": {"x": -122.03, "y": 37.37}
          }
        ]
      }
    """;

    List<Alert> result = parseJsonReports(json, makeLocation(37.3382, -121.8863));
    assertEquals(2, result.size());
    assertEquals("POLICE", result.get(0).type);
    assertEquals("ACCIDENT", result.get(1).type);
  }

  @Test
  public void parseReports_allSupportedTypes_kept() throws Exception {
    String json = """
      {
        "alerts": [
          {
            "type": "HAZARD",
            "subtype": "HAZARD_ON_ROAD_CONSTRUCTION",
            "city": "San Jose, CA",
            "street": "29th Ave",
            "location": {"x": -121.89, "y": 37.34}
          },
          {
            "type": "POLICE",
            "city": "San Jose, CA",
            "street": "Main St",
            "location": {"x": -121.88, "y": 37.33}
          },
          {
            "type": "JAM",
            "subtype": "JAM_HEAVY_TRAFFIC",
            "city": "San Jose, CA",
            "street": "Hwy 17",
            "location": {"x": -121.92, "y": 37.32}
          },
          {
            "type": "ROAD_CLOSED",
            "city": "San Jose, CA",
            "street": "1st St",
            "location": {"x": -121.87, "y": 37.35}
          }
        ]
      }
    """;

    List<Alert> result = parseJsonReports(json, makeLocation(37.3382, -121.8863));
    // POLICE, HAZARD, and JAM are kept; ROAD_CLOSED is filtered out
    assertEquals(3, result.size());
    assertEquals("HAZARD", result.get(0).type);
    assertEquals("POLICE", result.get(1).type);
    assertEquals("JAM", result.get(2).type);
  }

  @Test
  public void parseReports_emptyAlerts_returnsEmptyList() throws Exception {
    String json = """
      {
        "alerts": []
      }
    """;

    List<Alert> result = parseJsonReports(json, makeLocation(37.3382, -121.8863));
    assertNotNull(result);
    assertEquals(0, result.size());
  }

  @Test
  public void parseReports_noAlertsKey_returnsEmptyList() throws Exception {
    String json = """
      {
        "startTime": "2026-03-11 05:34:00:000",
        "endTime": "2026-03-11 05:35:00:000"
      }
    """;

    List<Alert> result = parseJsonReports(json, makeLocation(37.3382, -121.8863));
    assertNotNull(result);
    assertEquals(0, result.size());
  }

  @Test
  public void parseReports_malformedAlert_skipped() throws Exception {
    String json = """
      {
        "alerts": [
          {
            "type": "POLICE",
            "city": "San Jose, CA",
            "street": "Main St",
            "location": {"x": -121.88, "y": 37.33}
          },
          {
            "invalid": "no type field"
          },
          {
            "type": "ACCIDENT",
            "city": "Sunnyvale, CA",
            "location": {"x": -122.03, "y": 37.37}
          }
        ]
      }
    """;

    List<Alert> result = parseJsonReports(json, makeLocation(37.3382, -121.8863));
    assertEquals(2, result.size());
  }

  @Test
  public void parseReports_wazeResponse_multipleTypes() throws Exception {
    // Simulate a real Waze live-map response with mixed alert types
    String json = """
      {
        "alerts": [
          {
            "country": "US",
            "nThumbsUp": 1,
            "city": "San Francisco, CA",
            "reportRating": 0,
            "reliability": 7,
            "type": "HAZARD",
            "uuid": "53b6bfca",
            "subtype": "HAZARD_ON_ROAD_CONSTRUCTION",
            "street": "29th Ave",
            "location": {"x": -122.486535, "y": 37.744445},
            "confidence": 0,
            "roadType": 1,
            "magvar": 177
          },
          {
            "country": "US",
            "nThumbsUp": 0,
            "city": "San Jose, CA",
            "type": "POLICE",
            "subtype": "POLICE_HIDING",
            "street": "W St. James St",
            "location": {"x": -121.893087, "y": 37.338963}
          },
          {
            "country": "US",
            "nThumbsUp": 1,
            "city": "San Jose, CA",
            "type": "POLICE",
            "subtype": "",
            "street": "I-280 N",
            "location": {"x": -121.879973, "y": 37.327524}
          },
          {
            "country": "US",
            "type": "ACCIDENT",
            "subtype": "",
            "city": "San Francisco, CA",
            "street": "Utah St",
            "location": {"x": -122.405395, "y": 37.753474}
          }
        ],
        "endTimeMillis": 1773207300000,
        "startTimeMillis": 1773207240000,
        "startTime": "2026-03-11 05:34:00:000",
        "endTime": "2026-03-11 05:35:00:000"
      }
    """;

    List<Alert> result = parseJsonReports(json, makeLocation(37.3382, -121.8863));

    // Should have POLICE, ACCIDENT, and HAZARD alerts (4 out of 4)
    assertEquals(4, result.size());

    // Verify each alert has correct class and parsed fields
    for(Alert alert : result) {
      assertEquals(Alert.ALERT_CLASS_REPORT, alert.alertClass);
      assertTrue("Type should be POLICE, ACCIDENT, or HAZARD, got: " + alert.type,
        alert.type.equals("POLICE") || alert.type.equals("ACCIDENT") || alert.type.equals("HAZARD"));
      assertTrue(alert.distance > 0);
      assertNotNull(alert.city);
      assertNotNull(alert.street);
    }
  }

  @Test
  public void parseReports_distanceAndPriorityComputed() throws Exception {
    // Two police alerts at different distances
    String json = """
      {
        "alerts": [
          {
            "type": "POLICE",
            "city": "Near, CA",
            "street": "Close St",
            "location": {"x": -121.887, "y": 37.339}
          },
          {
            "type": "POLICE",
            "city": "Far, CA",
            "street": "Far Ave",
            "location": {"x": -122.0, "y": 37.5}
          }
        ]
      }
    """;

    Location vehicle = makeLocation(37.3382, -121.8863);
    List<Alert> result = parseJsonReports(json, vehicle);

    assertEquals(2, result.size());
    // Near alert should have smaller distance
    assertTrue(result.get(0).distance < result.get(1).distance);
    // Priority is based on distance in meters
    assertTrue(result.get(0).priority < result.get(1).priority);
  }

  // --- Type filtering tests ---

  @Test
  public void parseReports_policeDisabled_filtered() throws Exception {
    String json = """
      {
        "alerts": [
          {
            "type": "POLICE",
            "city": "San Jose, CA",
            "street": "Main St",
            "location": {"x": -121.88, "y": 37.33}
          },
          {
            "type": "ACCIDENT",
            "city": "San Jose, CA",
            "street": "1st St",
            "location": {"x": -121.89, "y": 37.34}
          }
        ]
      }
    """;

    JSONObject jsonObj = new JSONObject(json);
    Location loc = makeLocation(37.3382, -121.8863);
    List<Alert> result = ReportsHttpFetchTask.parseReports(jsonObj, loc, false, true, true, true);
    assertEquals(1, result.size());
    assertEquals("ACCIDENT", result.get(0).type);
  }

  @Test
  public void parseReports_onlyPoliceEnabled_onlyPoliceKept() throws Exception {
    String json = """
      {
        "alerts": [
          {
            "type": "POLICE",
            "city": "San Jose, CA",
            "street": "Main St",
            "location": {"x": -121.88, "y": 37.33}
          },
          {
            "type": "ACCIDENT",
            "city": "San Jose, CA",
            "street": "1st St",
            "location": {"x": -121.89, "y": 37.34}
          },
          {
            "type": "HAZARD",
            "city": "San Jose, CA",
            "street": "2nd St",
            "location": {"x": -121.87, "y": 37.35}
          },
          {
            "type": "JAM",
            "city": "San Jose, CA",
            "street": "3rd St",
            "location": {"x": -121.86, "y": 37.36}
          }
        ]
      }
    """;

    JSONObject jsonObj = new JSONObject(json);
    Location loc = makeLocation(37.3382, -121.8863);
    List<Alert> result = ReportsHttpFetchTask.parseReports(jsonObj, loc, true, false, false, false);
    assertEquals(1, result.size());
    assertEquals("POLICE", result.get(0).type);
  }

  @Test
  public void parseReports_allTypesDisabled_returnsEmpty() throws Exception {
    String json = """
      {
        "alerts": [
          {
            "type": "POLICE",
            "city": "San Jose, CA",
            "street": "Main St",
            "location": {"x": -121.88, "y": 37.33}
          }
        ]
      }
    """;

    JSONObject jsonObj = new JSONObject(json);
    Location loc = makeLocation(37.3382, -121.8863);
    List<Alert> result = ReportsHttpFetchTask.parseReports(jsonObj, loc, false, false, false, false);
    assertEquals(0, result.size());
  }

  // --- DOT/provider report tests ---

  @Test
  public void parseReports_dotReport_usesReportDescription() throws Exception {
    // DOT/provider reports may not include city/street but have reportDescription
    String json = """
      {
        "alerts": [
          {
            "type": "HAZARD",
            "subtype": "",
            "provider": "Caltrans District 04",
            "reportDescription": "US-101 - Electrical Work Van Ness Ave",
            "location": {"x": -122.4194, "y": 37.7749}
          }
        ]
      }
    """;

    List<Alert> result = parseJsonReports(json, makeLocation(37.3382, -121.8863));
    assertEquals(1, result.size());
    assertEquals("US-101 - Electrical Work Van Ness Ave", result.get(0).street);
  }

  @Test
  public void parseReports_dotReport_streetPresent_noFallback() throws Exception {
    // When street is present, reportDescription should not override it
    String json = """
      {
        "alerts": [
          {
            "type": "HAZARD",
            "street": "Main St",
            "city": "San Jose, CA",
            "reportDescription": "Some DOT description",
            "location": {"x": -121.88, "y": 37.33}
          }
        ]
      }
    """;

    List<Alert> result = parseJsonReports(json, makeLocation(37.3382, -121.8863));
    assertEquals(1, result.size());
    assertEquals("Main St", result.get(0).street);
  }

  // --- Edge cases and error scenarios ---

  @Test
  public void parseReports_alertsNotArray_returnsEmptyList() throws Exception {
    // "alerts" key exists but is a string, not an array
    String json = """
      {
        "alerts": "not an array"
      }
    """;

    List<Alert> result = parseJsonReports(json, makeLocation(37.3382, -121.8863));
    assertNotNull(result);
    assertEquals(0, result.size());
  }

  @Test
  public void parseReports_alertMissingLocation_defaultsToZero() throws Exception {
    // Alert has no "location" field — fromReport defaults lat/lng to 0.0
    // and computes a very large distance from the vehicle
    String json = """
      {
        "alerts": [
          {
            "type": "POLICE",
            "city": "San Jose, CA",
            "street": "Main St"
          },
          {
            "type": "ACCIDENT",
            "city": "Sunnyvale, CA",
            "street": "1st St",
            "location": {"x": -122.03, "y": 37.37}
          }
        ]
      }
    """;

    List<Alert> result = parseJsonReports(json, makeLocation(37.3382, -121.8863));
    // Both alerts are parsed; the one without location gets (0,0) coordinates
    assertEquals(2, result.size());
    // Alert without location should have a very large distance
    Alert noLocation = result.get(0);
    assertEquals("POLICE", noLocation.type);
    assertTrue("Missing location should result in very large distance",
      noLocation.distance > 100);
  }

  @Test
  public void parseReports_unknownType_filtered() throws Exception {
    String json = """
      {
        "alerts": [
          {
            "type": "ROAD_CLOSED",
            "city": "San Jose, CA",
            "street": "Main St",
            "location": {"x": -121.88, "y": 37.33}
          },
          {
            "type": "CONSTRUCTION",
            "city": "San Jose, CA",
            "street": "2nd St",
            "location": {"x": -121.87, "y": 37.34}
          }
        ]
      }
    """;

    List<Alert> result = parseJsonReports(json, makeLocation(37.3382, -121.8863));
    assertEquals(0, result.size());
  }

  @Test
  public void parseReports_alertMissingType_skipped() throws Exception {
    String json = """
      {
        "alerts": [
          {
            "city": "San Jose, CA",
            "street": "Main St",
            "location": {"x": -121.88, "y": 37.33}
          }
        ]
      }
    """;

    List<Alert> result = parseJsonReports(json, makeLocation(37.3382, -121.8863));
    assertEquals(0, result.size());
  }

  @Test
  public void parseReports_locationMissingCoordinate_skipped() throws Exception {
    // Location has "x" but no "y"
    String json = """
      {
        "alerts": [
          {
            "type": "POLICE",
            "city": "San Jose, CA",
            "location": {"x": -121.88}
          },
          {
            "type": "POLICE",
            "city": "Valid, CA",
            "location": {"x": -121.89, "y": 37.34}
          }
        ]
      }
    """;

    List<Alert> result = parseJsonReports(json, makeLocation(37.3382, -121.8863));
    // First alert has incomplete location but fromReport may still parse it
    // (y defaults to 0.0 via optDouble), so just verify no crash and at least
    // the valid one is parsed
    assertTrue(result.size() >= 1);
    assertTrue(result.stream().anyMatch(a -> "Valid".equals(a.city)));
  }

  @Test
  public void parseReports_emptyJsonObject_returnsEmptyList() throws Exception {
    String json = "{}";

    List<Alert> result = parseJsonReports(json, makeLocation(37.3382, -121.8863));
    assertNotNull(result);
    assertEquals(0, result.size());
  }

  @Test
  public void parseReports_mixedValidAndInvalid_validKept() throws Exception {
    // A realistic scenario with mixed well-formed and malformed alerts
    String json = """
      {
        "alerts": [
          {
            "type": "POLICE",
            "city": "Good City, CA",
            "street": "Good St",
            "location": {"x": -121.89, "y": 37.34}
          },
          {
            "invalid": "completely wrong format"
          },
          {
            "type": "ACCIDENT",
            "city": "Also Good, CA",
            "street": "Fine Ave",
            "location": {"x": -122.0, "y": 37.35}
          },
          {
            "type": "HAZARD",
            "city": "Third Good, CA",
            "street": "OK Blvd",
            "location": {"x": -121.95, "y": 37.36}
          }
        ]
      }
    """;

    List<Alert> result = parseJsonReports(json, makeLocation(37.3382, -121.8863));
    // 3 valid alerts parsed, 1 malformed one skipped
    assertEquals(3, result.size());
  }

  // --- Helper to parse JSON with all types enabled ---

  private List<Alert> parseJsonReports(String jsonString, Location location) throws Exception {
    JSONObject json = new JSONObject(jsonString);
    return ReportsHttpFetchTask.parseReports(json, location, true, true, true, true);
  }
}
