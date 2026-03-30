package com.jsd.x761.growler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.location.Location;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Tests for the report data processing pipeline in AlertsActivity:
 * filtering by distance, deduplication, tracking updates, and sorting.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class AlertsDataProcessingTest {

  private Location makeLocation(double lat, double lng) {
    Location loc = new Location("test");
    loc.setLatitude(lat);
    loc.setLongitude(lng);
    loc.setBearing(0f);
    return loc;
  }

  private Alert makeReport(String type, String city, String street,
                            double lat, double lng, float distance) {
    Alert a = new Alert();
    a.alertClass = Alert.ALERT_CLASS_REPORT;
    a.type = type;
    a.city = city;
    a.street = street;
    a.latitude = lat;
    a.longitude = lng;
    a.distance = distance;
    a.priority = Math.round(Geospatial.toMeters(distance));
    return a;
  }

  // --- Distance filtering (onReportsData pattern) ---

  @Test
  public void filterByDistance_removesOutOfRange() {
    List<Alert> reports = new ArrayList<>();
    reports.add(makeReport("POLICE", "Near", "St 1", 37.34, -121.89, 1.0f));
    reports.add(makeReport("POLICE", "Far", "St 2", 37.50, -121.90, 5.0f));
    reports.add(makeReport("POLICE", "Edge", "St 3", 37.36, -121.88, 2.0f));
    reports.add(makeReport("ACCIDENT", "TooFar", "St 4", 38.0, -122.0, 50.0f));

    List<Alert> inRange = new ArrayList<>();
    for(Alert report : reports) {
      if(report.distance <= Configuration.REPORTS_MAX_DISTANCE) {
        inRange.add(report);
      }
    }

    assertEquals(2, inRange.size());
    assertEquals("Near", inRange.get(0).city);
    assertEquals("Edge", inRange.get(1).city);
  }

  // --- Deduplication ---

  @Test
  public void deduplication_removesNearbyDuplicates() {
    List<Alert> reports = new ArrayList<>();
    // Two POLICE reports at nearly the same location
    reports.add(makeReport("POLICE", "San Jose", "Main St", 37.3382, -121.8863, 0.5f));
    reports.add(makeReport("POLICE", "San Jose", "Main St", 37.3383, -121.8864, 0.5f));
    // A third POLICE report far away
    reports.add(makeReport("POLICE", "Sunnyvale", "Other St", 37.36, -122.02, 1.0f));

    List<Alert> unique = new ArrayList<>();
    for(Alert report : reports) {
      boolean duplicate = false;
      for(Alert uniqueReport : unique) {
        if(report.isDuplicateReport(uniqueReport)) {
          duplicate = true;
          break;
        }
      }
      if(!duplicate) {
        unique.add(report);
      }
    }

    assertEquals(2, unique.size());
    assertEquals("San Jose", unique.get(0).city);
    assertEquals("Sunnyvale", unique.get(1).city);
  }

  @Test
  public void deduplication_differentTypes_notDeduplicated() {
    List<Alert> reports = new ArrayList<>();
    // POLICE and ACCIDENT at same location should NOT be deduplicated
    reports.add(makeReport("POLICE", "San Jose", "Main St", 37.3382, -121.8863, 0.5f));
    reports.add(makeReport("ACCIDENT", "San Jose", "Main St", 37.3382, -121.8863, 0.5f));

    List<Alert> unique = new ArrayList<>();
    for(Alert report : reports) {
      boolean duplicate = false;
      for(Alert uniqueReport : unique) {
        if(report.isDuplicateReport(uniqueReport)) {
          duplicate = true;
          break;
        }
      }
      if(!duplicate) {
        unique.add(report);
      }
    }

    assertEquals(2, unique.size());
  }

  // --- Report tracking (isSameReport) ---

  @Test
  public void trackingExistingReports_updatesDistanceAndBearing() {
    // Simulate existing reports
    List<Alert> existingReports = new ArrayList<>();
    Alert existing = makeReport("POLICE", "San Jose", "Main St", 37.3382, -121.8863, 1.0f);
    existing.announced = 2;
    existing.announceDistance = 1.5f;
    existing.announceBearing = 12;
    existingReports.add(existing);

    // New fetch returns same report with updated distance
    List<Alert> newFetch = new ArrayList<>();
    Alert updated = makeReport("POLICE", "San Jose", "Main St", 37.3382, -121.8863, 0.5f);
    updated.bearing = 3;
    newFetch.add(updated);

    // Apply tracking logic from AlertsActivity.onReportsData
    List<Alert> result = new ArrayList<>();
    for(Alert report : newFetch) {
      for(Alert mReport : existingReports) {
        if(report.isSameReport(mReport)) {
          mReport.distance = report.distance;
          mReport.bearing = report.bearing;
          report = mReport;
          break;
        }
      }
      result.add(report);
    }

    assertEquals(1, result.size());
    Alert tracked = result.get(0);
    assertEquals(0.5f, tracked.distance, 0.01f);
    assertEquals(3, tracked.bearing);
    // Should preserve announcement state from existing
    assertEquals(2, tracked.announced);
    assertEquals(1.5f, tracked.announceDistance, 0.01f);
  }

  // --- Priority sorting ---

  @Test
  public void sorting_byPriority_closestFirst() {
    List<Alert> reports = new ArrayList<>();
    reports.add(makeReport("POLICE", "Far", "St 1", 37.36, -121.89, 1.5f));
    reports.add(makeReport("POLICE", "Near", "St 2", 37.34, -121.89, 0.3f));
    reports.add(makeReport("POLICE", "Mid", "St 3", 37.35, -121.89, 0.8f));

    reports.sort(Comparator.comparingInt(o -> o.priority));

    assertEquals("Near", reports.get(0).city);
    assertEquals("Mid", reports.get(1).city);
    assertEquals("Far", reports.get(2).city);
  }

  // --- Full pipeline ---

  @Test
  public void fullPipeline_filterDedupeSortTrack() throws Exception {
    Location vehicle = makeLocation(37.3382, -121.8863);

    // Simulate a fetch with various alerts
    List<Alert> fetched = new ArrayList<>();
    // Close POLICE alert
    fetched.add(makeReport("POLICE", "San Jose", "Main St", 37.339, -121.887, 0.1f));
    // Duplicate POLICE alert (same location)
    fetched.add(makeReport("POLICE", "San Jose", "Main St", 37.339, -121.887, 0.1f));
    // Far POLICE alert (out of range)
    fetched.add(makeReport("POLICE", "Remote", "Hwy 1", 38.0, -122.5, 60.0f));
    // Close ACCIDENT
    fetched.add(makeReport("ACCIDENT", "San Jose", "1st St", 37.34, -121.89, 0.3f));
    // Mid-range POLICE
    fetched.add(makeReport("POLICE", "Sunnyvale", "El Camino", 37.36, -122.03, 1.8f));

    // Step 1: Filter by distance
    List<Alert> inRange = new ArrayList<>();
    for(Alert report : fetched) {
      if(report.distance <= Configuration.REPORTS_MAX_DISTANCE) {
        inRange.add(report);
      }
    }
    assertEquals(4, inRange.size());

    // Step 2: Deduplicate
    List<Alert> unique = new ArrayList<>();
    for(Alert report : inRange) {
      boolean duplicate = false;
      for(Alert u : unique) {
        if(report.isDuplicateReport(u)) {
          duplicate = true;
          break;
        }
      }
      if(!duplicate) {
        unique.add(report);
      }
    }
    assertEquals(3, unique.size());

    // Step 3: Sort by priority
    unique.sort(Comparator.comparingInt(o -> o.priority));

    // Closest should be first
    assertEquals("San Jose", unique.get(0).city);
    assertEquals("Main St", unique.get(0).street);
    // Farthest (but in range) should be last
    assertEquals("Sunnyvale", unique.get(2).city);
  }

  // --- Announcement decision with distance changes ---

  @Test
  public void announcementDecision_fullScenario() {
    // First sighting: should announce
    Alert alert = new Alert();
    alert.announced = 0;
    alert.distance = 2.0f;
    alert.bearing = 12;
    assertTrue("Should announce on first sighting", alert.shouldAnnounceReport());

    // Mark as announced
    alert.announced = 1;
    alert.announceDistance = 2.0f;
    alert.announceBearing = 12;

    // No significant change: should NOT announce
    alert.distance = 1.9f;
    alert.bearing = 12;
    assertTrue("Should not re-announce for small change", !alert.shouldAnnounceReport());

    // Closed by enough distance: should announce
    alert.distance = 1.5f;
    assertTrue("Should re-announce when closing by 0.5 miles", alert.shouldAnnounceReport());

    // Reset announce distance
    alert.announced = 2;
    alert.announceDistance = 1.5f;
    alert.announceBearing = 12;

    // Bearing changed significantly: should announce
    alert.distance = 1.5f;
    alert.bearing = 9; // 3 hours change
    assertTrue("Should re-announce when bearing changes by 3+", alert.shouldAnnounceReport());
  }
}
