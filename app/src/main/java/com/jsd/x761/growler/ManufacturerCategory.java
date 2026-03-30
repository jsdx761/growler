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

/**
 * Maps aircraft manufacturer names from the interesting_aircrafts.csv database
 * to pre-recorded segment IDs. Only the top ~20 manufacturers have dedicated
 * segments; others are omitted from the announcement.
 */
public class ManufacturerCategory {

  /**
   * Return the segment ID for the manufacturer, or null if the manufacturer
   * doesn't have a pre-recorded segment (in which case it should be omitted
   * from the announcement).
   */
  public static String categorize(String manufacturer) {
    if(manufacturer == null || manufacturer.isEmpty()) {
      return null;
    }
    String lower = manufacturer.toLowerCase();

    if(lower.contains("bell")) return "mfg_bell";
    if(lower.contains("cessna")) return "mfg_cessna";
    if(lower.equals("dji") || lower.contains("dji")) return "mfg_dji";
    if(lower.contains("airbus")) return "mfg_airbus";
    if(lower.contains("eurocopter")) return "mfg_eurocopter";
    if(lower.contains("piper")) return "mfg_piper";
    if(lower.contains("beech") || lower.contains("hawker beechcraft")
      || lower.contains("raytheon aircraft")) return "mfg_beech";
    if(lower.contains("sikorsky")) return "mfg_sikorsky";
    if(lower.contains("md helicopter")) return "mfg_md_helicopters";
    if(lower.contains("boeing")) return "mfg_boeing";
    if(lower.contains("bombardier")) return "mfg_bombardier";
    if(lower.contains("hughes")) return "mfg_hughes";
    if(lower.contains("robinson")) return "mfg_robinson";
    if(lower.contains("pilatus")) return "mfg_pilatus";
    if(lower.contains("textron")) return "mfg_textron";
    if(lower.contains("lockheed")) return "mfg_lockheed";
    if(lower.contains("dehavilland") || lower.contains("de havilland")) return "mfg_dehavilland";
    if(lower.contains("general atomics")) return "mfg_general_atomics";
    if(lower.contains("agusta")) return "mfg_agusta";
    if(lower.contains("mcdonnell")) return "mfg_mcdonnell_douglas";
    if(lower.contains("yuneec")) return "mfg_dji"; // similar drone maker
    if(lower.contains("skydio") || lower.contains("autel")) return "mfg_dji";

    return null;
  }
}
