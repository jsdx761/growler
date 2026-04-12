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

import android.location.Location;

/**
 * Various geospatial computation functions, to help compute a destination
 * from a distance and a bearing, the distance and bearing between two points,
 * relative bearings, convert between miles and meters, and between degrees
 * and hours on a clock.
 */
public class Geospatial {

  public static Location getDestination(
    Location from, float distance, float bearing) {
    double radius = 6371000;
    double fromLat = from.getLatitude();
    double fromLng = from.getLongitude();

    double delta = distance / radius;
    double theta = Math.toRadians(bearing);

    double phi1 = Math.toRadians(fromLat);
    double lambda1 = Math.toRadians(fromLng);

    double phi2 = Math.asin(Math.sin(phi1) * Math.cos(delta) + Math.cos(phi1) * Math.sin(delta) * Math.cos(theta));
    double lambda2 = lambda1 + Math.atan2(Math.sin(theta) * Math.sin(delta) * Math.cos(phi1), Math.cos(delta) - Math.sin(phi1) * Math.sin(phi2));

    double toLat = Math.toDegrees(phi2);
    double toLng = Math.toDegrees(lambda2);
    if(toLng < -180 || toLng > 180) {
      lambda2 = ((lambda2 + 3 * Math.PI) % (2 * Math.PI)) - Math.PI;
      toLng = Math.toDegrees(lambda2);
    }

    Location to = new Location(from);
    to.setLatitude(toLat);
    to.setLongitude(toLng);
    return to;
  }

  public static float getDistance(Location from, Location to) {
    double radius = 6371000;
    double fromLat = from.getLatitude();
    double fromLng = from.getLongitude();
    double toLat = to.getLatitude();
    double toLng = to.getLongitude();

    double d =
      Math.sin(Math.toRadians(toLat)) * Math.sin(Math.toRadians(fromLat)) + Math.cos(Math.toRadians(toLat)) * Math.cos(Math.toRadians(fromLat)) * Math.cos(
        Math.toRadians(fromLng) - Math.toRadians(toLng));

    return (float)(Math.acos(Math.min(Math.max(d, -1), 1)) * radius);
  }

  public static float getBearing(Location from, Location to) {
    double destLat = to.getLatitude();
    double detLon = to.getLongitude();
    double originLat = from.getLatitude();
    double originLon = from.getLongitude();

    double bearing = (Math.toDegrees(Math.atan2(
      Math.sin(Math.toRadians(detLon) - Math.toRadians(originLon)) * Math.cos(Math.toRadians(destLat)),
      Math.cos(Math.toRadians(originLat)) * Math.sin(Math.toRadians(destLat)) -
        Math.sin(Math.toRadians(originLat)) * Math.cos(Math.toRadians(destLat)) * Math.cos(Math.toRadians(detLon) - Math.toRadians(originLon)))) + 360) % 360;

    return (float)bearing;
  }

  public static float getRelativeBearing(
    float bearing, float bearingToTarget) {
    float relativeBearing = bearingToTarget - bearing;
    if(relativeBearing < 0.0f) {
      relativeBearing += 360.0f;
    }
    return relativeBearing;
  }

  public static int getRelativeBearing(
    int bearing, int bearingToTarget) {
    int relativeBearing = bearingToTarget - bearing;
    if(relativeBearing < 0) {
      relativeBearing += 12;
    }
    return relativeBearing;
  }

  public static int toHour(float degrees) {
    int hour = Math.round(degrees / 30.0f);
    if(hour == 0) {
      hour = 12;
    }
    return hour;
  }

  public static float toMiles(float meters) {
    return meters / 1609.34f;
  }

  public static float toMeters(float miles) {
    return miles * 1609.34f;
  }

  public static float getStrength(float distance, float range) {
    return Math.max(Math.min((range - distance) / range, 1.0f), 0.0f);
  }
}
