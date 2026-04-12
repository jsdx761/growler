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
 * Categorizes aircraft organization names from the interesting_aircrafts.csv
 * database into a small set of pre-recorded segment IDs for the concatenative
 * speech engine.
 *
 * The heuristics match patterns in the 1091 unique organization names to
 * assign one of ~25 categories. Order matters: more specific patterns are
 * tested first (e.g. "highway patrol" before generic "police").
 */
public class OrganizationCategory {

  /**
   * Return the segment ID for the pre-recorded organization category that
   * best matches the given owner string from the aircraft database.
   */
  public static String categorize(String owner) {
    if(owner == null || owner.isEmpty()) {
      return "org_unidentified";
    }
    String lower = owner.toLowerCase();

    // ── Specific federal agencies ─────────────────────────────────
    if(lower.contains("homeland security") || lower.contains("dept of homeland")
      || lower.contains("department of homeland")) {
      return "org_homeland_security";
    }
    if(lower.contains("border protection") || lower.contains("border patrol")) {
      return "org_border_patrol";
    }
    if(lower.contains("customs")) {
      return "org_customs";
    }
    if(lower.contains("special operations") || lower.contains("special ops")) {
      return "org_special_operations";
    }
    if(lower.contains("national guard")) {
      return "org_national_guard";
    }
    if(lower.contains("nuclear security") || lower.contains("nuclear admin")
      || lower.contains("nnsa")) {
      return "org_nuclear_security";
    }
    if(lower.contains("tennessee valley authority") || lower.contains(" tva")
      || lower.contains("energy") && lower.contains("authority")) {
      return "org_energy";
    }
    if(lower.contains("park service") || lower.contains("parks")
      || lower.contains("park ranger")) {
      return "org_park_service";
    }
    if(lower.contains("fish and wildlife") || lower.contains("fish & wildlife")
      || lower.contains("wildlife")) {
      return "org_fish_and_wildlife";
    }
    if(lower.contains("natural resources") || lower.contains("dept of natural")
      || lower.contains("department of natural") || lower.contains("forestry")
      || lower.contains("conservation")) {
      return "org_natural_resources";
    }

    // ── Military ──────────────────────────────────────────────────
    if(lower.contains("military") || lower.contains("air force")
      || lower.contains("army") || lower.contains("navy")
      || lower.contains("marine") || lower.contains("coast guard")
      || lower.contains("department of defense") || lower.contains("dept of defense")) {
      return "org_military";
    }

    // ── Law enforcement (specific before generic) ─────────────────
    if(lower.contains("highway patrol") || lower.equals("chp")
      || lower.contains("california highway")) {
      return "org_highway_patrol";
    }
    if(lower.contains("state police")) {
      return "org_state_police";
    }
    if(lower.contains("state trooper") || lower.contains("troopers")) {
      return "org_state_trooper";
    }
    if(lower.contains("sheriff")) {
      return "org_sheriff";
    }

    // ── Fire / Emergency ──────────────────────────────────────────
    if(lower.contains("fire department") || lower.contains("fire dept")
      || lower.contains("fire rescue") || lower.contains("fire district")) {
      return "org_fire_department";
    }
    if(lower.contains("emergency") || lower.contains("ems")
      || lower.contains("rescue") || lower.contains("ambulance")
      || lower.contains("medevac") || lower.contains("medical")) {
      return "org_emergency_services";
    }

    // ── Transportation ────────────────────────────────────────────
    if(lower.contains("transportation") || lower.contains("turnpike")
      || lower.contains("transit") || lower.contains("dot ")
      || lower.endsWith(" dot")) {
      return "org_transportation";
    }

    // ── Generic law enforcement ───────────────────────────────────
    if(lower.contains("police") || lower.contains("law enforcement")
      || lower.contains("lapd") || lower.contains("pd ")
      || lower.endsWith(" pd") || lower.contains("public safety")) {
      return "org_police";
    }

    // ── Federal government ────────────────────────────────────────
    if(lower.contains("us ") || lower.contains("u s ")
      || lower.contains("united states") || lower.contains("federal")
      || lower.contains("department of") || lower.contains("dept of")) {
      return "org_federal_government";
    }

    // ── State / Commonwealth government ───────────────────────────
    if(lower.startsWith("state of ") || lower.startsWith("commonwealth of ")
      || lower.contains("state government")) {
      return "org_state_government";
    }

    // ── County government ─────────────────────────────────────────
    if(lower.contains("county")) {
      return "org_county_government";
    }

    // ── City government ───────────────────────────────────────────
    if(lower.startsWith("city of ") || lower.contains("municipal")
      || lower.contains("city government")) {
      return "org_city_government";
    }

    // ── Private contractors (air patrol services) ─────────────────
    if(lower.contains("patrol llc") || lower.contains("patrol inc")
      || lower.contains("aviation llc") || lower.contains("aviation inc")
      || lower.contains("air patrol") || lower.contains("aerial")) {
      return "org_private_contractor";
    }

    // Default: unidentified
    return "org_unidentified";
  }
}
