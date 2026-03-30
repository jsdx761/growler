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
 * A pre-recorded speech segment with its preceding gap duration for
 * natural-sounding concatenation. Gap durations vary by linguistic
 * context to produce natural prosody when segments are joined.
 */
public class Segment {
  /** No gap (first segment or complete sentence). */
  public static final int GAP_NONE = 0;

  /** Tight join within a phrase (e.g., band + frequency, org + type). */
  public static final int GAP_TIGHT = 60;

  /** Between modifier and head (e.g., alert type + road name). */
  public static final int GAP_PHRASE = 120;

  /** Between clauses or info categories (e.g., bearing, distance). */
  public static final int GAP_CLAUSE = 350;

  /** Segment ID matching a loaded voice segment asset. */
  public final String id;

  /** Silence gap in milliseconds to insert before this segment. */
  public final int gapMs;

  public Segment(String id, int gapMs) {
    this.id = id;
    this.gapMs = gapMs;
  }

  public Segment(String id) {
    this(id, GAP_NONE);
  }

  @Override
  public String toString() {
    return id;
  }
}
