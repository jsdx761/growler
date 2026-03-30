package com.jsd.x761.growler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.location.Location;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class GeospatialTest {

  private Location makeLocation(double lat, double lng) {
    Location loc = new Location("test");
    loc.setLatitude(lat);
    loc.setLongitude(lng);
    return loc;
  }

  private Location makeLocation(double lat, double lng, float bearing) {
    Location loc = makeLocation(lat, lng);
    loc.setBearing(bearing);
    return loc;
  }

  // --- toMiles / toMeters ---

  @Test
  public void toMiles_convertsCorrectly() {
    assertEquals(1.0f, Geospatial.toMiles(1609.34f), 0.01f);
    assertEquals(0.0f, Geospatial.toMiles(0f), 0.001f);
    assertEquals(0.621f, Geospatial.toMiles(1000f), 0.01f);
  }

  @Test
  public void toMeters_convertsCorrectly() {
    assertEquals(1609.34f, Geospatial.toMeters(1.0f), 0.01f);
    assertEquals(0.0f, Geospatial.toMeters(0f), 0.001f);
    assertEquals(3218.68f, Geospatial.toMeters(2.0f), 0.01f);
  }

  @Test
  public void toMiles_toMeters_roundTrip() {
    float miles = 2.5f;
    float result = Geospatial.toMiles(Geospatial.toMeters(miles));
    assertEquals(miles, result, 0.001f);
  }

  // --- toHour ---

  @Test
  public void toHour_straightAhead_returns12() {
    assertEquals(12, Geospatial.toHour(0f));
    assertEquals(12, Geospatial.toHour(355f));
    assertEquals(12, Geospatial.toHour(360f));
  }

  @Test
  public void toHour_rightSide_returns3() {
    assertEquals(3, Geospatial.toHour(90f));
  }

  @Test
  public void toHour_behind_returns6() {
    assertEquals(6, Geospatial.toHour(180f));
  }

  @Test
  public void toHour_leftSide_returns9() {
    assertEquals(9, Geospatial.toHour(270f));
  }

  @Test
  public void toHour_allPositions() {
    assertEquals(1, Geospatial.toHour(30f));
    assertEquals(2, Geospatial.toHour(60f));
    assertEquals(4, Geospatial.toHour(120f));
    assertEquals(5, Geospatial.toHour(150f));
    assertEquals(7, Geospatial.toHour(210f));
    assertEquals(8, Geospatial.toHour(240f));
    assertEquals(10, Geospatial.toHour(300f));
    assertEquals(11, Geospatial.toHour(330f));
  }

  // --- getStrength ---

  @Test
  public void getStrength_atZeroDistance_returns1() {
    assertEquals(1.0f, Geospatial.getStrength(0f, 2.0f), 0.001f);
  }

  @Test
  public void getStrength_atMaxRange_returns0() {
    assertEquals(0.0f, Geospatial.getStrength(2.0f, 2.0f), 0.001f);
  }

  @Test
  public void getStrength_halfwayThrough_returns0_5() {
    assertEquals(0.5f, Geospatial.getStrength(1.0f, 2.0f), 0.001f);
  }

  @Test
  public void getStrength_beyondRange_clampedTo0() {
    assertEquals(0.0f, Geospatial.getStrength(5.0f, 2.0f), 0.001f);
  }

  @Test
  public void getStrength_negativeDistance_clampedTo1() {
    assertEquals(1.0f, Geospatial.getStrength(-1.0f, 2.0f), 0.001f);
  }

  // --- getRelativeBearing (float) ---

  @Test
  public void getRelativeBearing_float_sameDirection() {
    assertEquals(0f, Geospatial.getRelativeBearing(90f, 90f), 0.001f);
  }

  @Test
  public void getRelativeBearing_float_targetToRight() {
    assertEquals(90f, Geospatial.getRelativeBearing(0f, 90f), 0.001f);
  }

  @Test
  public void getRelativeBearing_float_targetBehind() {
    assertEquals(180f, Geospatial.getRelativeBearing(0f, 180f), 0.001f);
  }

  @Test
  public void getRelativeBearing_float_targetToLeft() {
    assertEquals(270f, Geospatial.getRelativeBearing(0f, 270f), 0.001f);
  }

  @Test
  public void getRelativeBearing_float_wrapsAround() {
    // Vehicle heading 350, target at 10 -> 20 degrees to the right
    assertEquals(20f, Geospatial.getRelativeBearing(350f, 10f), 0.001f);
  }

  @Test
  public void getRelativeBearing_float_wrapsAroundLeft() {
    // Vehicle heading 10, target at 350 -> 340 degrees (to the left)
    assertEquals(340f, Geospatial.getRelativeBearing(10f, 350f), 0.001f);
  }

  // --- getRelativeBearing (int, clock hours) ---

  @Test
  public void getRelativeBearing_int_sameHour() {
    assertEquals(0, Geospatial.getRelativeBearing(3, 3));
  }

  @Test
  public void getRelativeBearing_int_clockwise() {
    assertEquals(3, Geospatial.getRelativeBearing(12, 3));
  }

  @Test
  public void getRelativeBearing_int_counterClockwise() {
    assertEquals(9, Geospatial.getRelativeBearing(3, 12));
  }

  @Test
  public void getRelativeBearing_int_wrapsAround() {
    // From 11 to 1 = 2 hours clockwise
    assertEquals(2, Geospatial.getRelativeBearing(11, 1));
  }

  // --- getDistance ---

  @Test
  public void getDistance_samePoint_returnsZero() {
    Location a = makeLocation(37.3382, -121.8863);
    assertEquals(0f, Geospatial.getDistance(a, a), 1f);
  }

  @Test
  public void getDistance_knownDistance() {
    // San Jose to San Francisco ~42 miles
    Location sanJose = makeLocation(37.3382, -121.8863);
    Location sanFrancisco = makeLocation(37.7749, -122.4194);
    float distance = Geospatial.getDistance(sanJose, sanFrancisco);
    float miles = Geospatial.toMiles(distance);
    assertEquals(42f, miles, 3f);
  }

  @Test
  public void getDistance_oneMile() {
    // Approx 1 mile north from a point
    Location from = makeLocation(37.0, -122.0);
    Location to = makeLocation(37.01449, -122.0); // ~1 mile north
    float miles = Geospatial.toMiles(Geospatial.getDistance(from, to));
    assertEquals(1.0f, miles, 0.05f);
  }

  // --- getBearing ---

  @Test
  public void getBearing_dueNorth() {
    Location from = makeLocation(37.0, -122.0);
    Location to = makeLocation(38.0, -122.0);
    float bearing = Geospatial.getBearing(from, to);
    assertEquals(0f, bearing, 1f);
  }

  @Test
  public void getBearing_dueEast() {
    Location from = makeLocation(37.0, -122.0);
    Location to = makeLocation(37.0, -121.0);
    float bearing = Geospatial.getBearing(from, to);
    assertEquals(90f, bearing, 1f);
  }

  @Test
  public void getBearing_dueSouth() {
    Location from = makeLocation(37.0, -122.0);
    Location to = makeLocation(36.0, -122.0);
    float bearing = Geospatial.getBearing(from, to);
    assertEquals(180f, bearing, 1f);
  }

  @Test
  public void getBearing_dueWest() {
    Location from = makeLocation(37.0, -122.0);
    Location to = makeLocation(37.0, -123.0);
    float bearing = Geospatial.getBearing(from, to);
    assertEquals(270f, bearing, 1f);
  }

  // --- getDestination ---

  @Test
  public void getDestination_north_increasesLatitude() {
    Location from = makeLocation(37.0, -122.0);
    Location dest = Geospatial.getDestination(from, 1000f, 0f);
    assertTrue(dest.getLatitude() > from.getLatitude());
    assertEquals(from.getLongitude(), dest.getLongitude(), 0.001);
  }

  @Test
  public void getDestination_east_increasesLongitude() {
    Location from = makeLocation(37.0, -122.0);
    Location dest = Geospatial.getDestination(from, 1000f, 90f);
    assertTrue(dest.getLongitude() > from.getLongitude());
    assertEquals(from.getLatitude(), dest.getLatitude(), 0.001);
  }

  @Test
  public void getDestination_south_decreasesLatitude() {
    Location from = makeLocation(37.0, -122.0);
    Location dest = Geospatial.getDestination(from, 1000f, 180f);
    assertTrue(dest.getLatitude() < from.getLatitude());
  }

  @Test
  public void getDestination_west_decreasesLongitude() {
    Location from = makeLocation(37.0, -122.0);
    Location dest = Geospatial.getDestination(from, 1000f, 270f);
    assertTrue(dest.getLongitude() < from.getLongitude());
  }

  @Test
  public void getDestination_roundTrip_returnsToOrigin() {
    Location from = makeLocation(37.0, -122.0);
    float distanceMeters = 5000f;
    Location dest = Geospatial.getDestination(from, distanceMeters, 45f);
    float actualDistance = Geospatial.getDistance(from, dest);
    assertEquals(distanceMeters, actualDistance, 10f);
  }

  @Test
  public void getDestination_twoMiles_matchesConfigDistance() {
    // This is the exact pattern used in ReportsFetchTask for bounding box
    Location from = makeLocation(37.3382, -121.8863);
    float distance = Geospatial.toMeters(2.0f); // REPORTS_MAX_DISTANCE
    Location top = Geospatial.getDestination(from, distance, 0f);
    Location bottom = Geospatial.getDestination(from, distance, 180f);
    Location left = Geospatial.getDestination(from, distance, 270f);
    Location right = Geospatial.getDestination(from, distance, 90f);

    float topDist = Geospatial.toMiles(Geospatial.getDistance(from, top));
    float bottomDist = Geospatial.toMiles(Geospatial.getDistance(from, bottom));
    float leftDist = Geospatial.toMiles(Geospatial.getDistance(from, left));
    float rightDist = Geospatial.toMiles(Geospatial.getDistance(from, right));

    assertEquals(2.0f, topDist, 0.05f);
    assertEquals(2.0f, bottomDist, 0.05f);
    assertEquals(2.0f, leftDist, 0.05f);
    assertEquals(2.0f, rightDist, 0.05f);
  }
}
