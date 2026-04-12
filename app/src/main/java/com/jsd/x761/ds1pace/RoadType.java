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
 * Classifies Waze street/location strings into generic road type segment IDs
 * for the pre-recorded concatenative speech engine.
 *
 * Instead of trying to pronounce arbitrary street names (which requires
 * full TTS), we classify the street into a category like "highway",
 * "freeway", "interstate", "road", "street", etc.
 */
public class RoadType {

  /**
   * Return the segment ID for the road type that best matches the given
   * street name. If hidden is true, returns the "hidden on" variant for
   * POLICE_HIDING alerts.
   */
  public static String classify(String street, boolean hidden) {
    if(street == null || street.isEmpty()) {
      return null;
    }
    String lower = street.toLowerCase();

    String type;

    // Interstate patterns: "I-5", "I-95", "Interstate", "US-101"
    if(lower.matches(".*\\bi-\\d+.*") || lower.contains("interstate")) {
      type = "interstate";
    }
    // Freeway patterns: "Fwy", "Freeway"
    else if(lower.contains("fwy") || lower.contains("freeway")) {
      type = "freeway";
    }
    // Highway patterns: "Hwy", "Highway", "US-", "SR-", "State Route",
    // "CA-", "TX-" (state route abbreviations), "Route"
    else if(lower.contains("hwy") || lower.contains("highway")
      || lower.matches(".*\\bus-\\d+.*") || lower.matches(".*\\bsr-\\d+.*")
      || lower.contains("state route") || lower.contains("route")
      || lower.matches(".*\\b[a-z]{2}-\\d+.*")) {
      type = "highway";
    }
    // Boulevard patterns
    else if(lower.contains("blvd") || lower.contains("boulevard")) {
      type = "boulevard";
    }
    // Avenue patterns
    else if(lower.contains("ave ") || lower.contains("avenue")
      || lower.endsWith(" ave")) {
      type = "avenue";
    }
    // Drive patterns
    else if(lower.contains("dr ") || lower.contains("drive")
      || lower.endsWith(" dr")) {
      type = "drive";
    }
    // Lane patterns
    else if(lower.contains("ln ") || lower.contains("lane")
      || lower.endsWith(" ln")) {
      type = "lane";
    }
    // Street patterns (explicit "St" or "Street")
    else if(lower.contains("street") || lower.matches(".*\\bst\\b.*")) {
      type = "street";
    }
    // Road patterns
    else if(lower.contains("rd ") || lower.contains("road")
      || lower.endsWith(" rd")) {
      type = "road";
    }
    // Default: "road" as generic fallback
    else {
      type = "road";
    }

    if(hidden) {
      return "road_hidden_" + type;
    }
    return "road_" + type;
  }
}
