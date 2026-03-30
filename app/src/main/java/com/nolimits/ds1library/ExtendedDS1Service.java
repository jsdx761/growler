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

package com.nolimits.ds1library;

import java.util.ArrayList;
import java.util.List;

public class ExtendedDS1Service extends DS1Service {

  /**
   * Camera alert data extracted from the DS1's AlertEntry list.
   */
  public static class CameraAlert {
    public int alertClass;
    public int distance;
    public int dir;
    public String threatName;
    public float intensity;

    public CameraAlert(int alertClass, int distance, int dir, String threatName, float intensity) {
      this.alertClass = alertClass;
      this.distance = distance;
      this.dir = dir;
      this.threatName = threatName;
      this.intensity = intensity;
    }
  }

  @Override
  public void onCreate() {
    super.onCreate();

    // Work around a NullPointerException in DS1Service in test for
    // priorAddress after a restart of the service
    priorAddress = "";
  }

  /**
   * Return camera alerts from the DS1's AlertEntry list. AlertEntry fields
   * are package-private so this method bridges between the library and the
   * app.
   */
  public List<CameraAlert> getCameraAlerts() {
    List<CameraAlert> cameras = new ArrayList<>();
    List<AlertEntry> entries = getAlertList();
    if(entries != null) {
      for(AlertEntry entry : entries) {
        if(entry.alert_class == 2 || entry.alert_class == 3 || entry.alert_class == 4 || entry.alert_class == 5) {
          cameras.add(new CameraAlert(entry.alert_class, entry.distance, entry.dir, entry.threat_name, entry.intensity));
        }
      }
    }
    return cameras;
  }
}
