"""
Tests for the aircraft data filter and merge pipeline.

Verifies that filter-opensky-aircrafts.py, filter-faa-aircrafts.py, and
merge-interesting-aircrafts.py produce correct output from the source databases.

Run from the scripts/ directory:
  python test-filter-aircrafts.py
"""

import csv
import os
import sys
import unittest

class TestOpenSkyFilter(unittest.TestCase):
  """Tests for the OpenSky aircraft filter output."""

  @classmethod
  def setUpClass(cls):
    cls.aircrafts = {}
    with open('data/interesting_opensky_aircrafts.csv', 'r') as f:
      for row in csv.reader(f):
        cls.aircrafts[row[0]] = row

  def test_output_exists(self):
    self.assertTrue(os.path.exists('data/interesting_opensky_aircrafts.csv'))

  def test_output_not_empty(self):
    self.assertGreater(len(self.aircrafts), 0)

  def test_row_format_has_four_columns(self):
    for icao, row in self.aircrafts.items():
      self.assertEqual(len(row), 4, f'Row {icao} has {len(row)} columns, expected 4')

  def test_icao24_starts_with_a(self):
    for icao in self.aircrafts:
      self.assertTrue(icao.startswith('a'), f'ICAO24 {icao} does not start with "a"')

  def test_known_sheriff_helicopter(self):
    row = self.aircrafts.get('a00196')
    self.assertIsNotNone(row, 'Missing known aircraft a00196 (Pinellas County Sheriff)')
    self.assertEqual(row[1], 'Airbus Helicopters Inc')
    self.assertEqual(row[2], 'H1T')
    self.assertEqual(row[3], 'Pinellas County Sheriffs Office')

  def test_known_state_aircraft(self):
    row = self.aircrafts.get('a00137')
    self.assertIsNotNone(row, 'Missing known aircraft a00137 (State Of New Jersey)')
    self.assertEqual(row[1], 'Agusta Aerospace Corp')
    self.assertEqual(row[2], 'H2T')
    self.assertEqual(row[3], 'State Of New Jersey')

  def test_known_state_jet(self):
    row = self.aircrafts.get('a001c1')
    self.assertIsNotNone(row, 'Missing known aircraft a001c1 (State Of Texas)')
    self.assertEqual(row[2], 'L2J')
    self.assertEqual(row[3], 'State Of Texas')

  def test_known_police_drone(self):
    row = self.aircrafts.get('a00ff1')
    self.assertIsNotNone(row, 'Missing known aircraft a00ff1 (Salt River Police Dept)')
    self.assertEqual(row[1], 'Yuneec')
    self.assertEqual(row[3], 'Salt River Police Dept')

  def test_known_county_sheriff(self):
    row = self.aircrafts.get('a002a3')
    self.assertIsNotNone(row, 'Missing known aircraft a002a3 (Alameda County Sheriff)')
    self.assertEqual(row[1], 'Cessna')
    self.assertEqual(row[2], 'L1P')
    self.assertEqual(row[3], 'Alameda County Sheriffs Office')

  def test_operator_field_match(self):
    # a64e26 is owned by Southern Utah University but operated by Iron County Sheriff
    row = self.aircrafts.get('a64e26')
    self.assertIsNotNone(row, 'Missing aircraft a64e26 matched via operator field')

class TestFaaFilter(unittest.TestCase):
  """Tests for the FAA aircraft filter output."""

  @classmethod
  def setUpClass(cls):
    cls.aircrafts = {}
    with open('data/interesting_faa_aircrafts.csv', 'r') as f:
      for row in csv.reader(f):
        cls.aircrafts[row[0]] = row

  def test_output_exists(self):
    self.assertTrue(os.path.exists('data/interesting_faa_aircrafts.csv'))

  def test_output_not_empty(self):
    self.assertGreater(len(self.aircrafts), 0)

  def test_row_format_has_four_columns(self):
    for icao, row in self.aircrafts.items():
      self.assertEqual(len(row), 4, f'Row {icao} has {len(row)} columns, expected 4')

  def test_icao24_is_lowercase_hex(self):
    import re
    for icao in self.aircrafts:
      self.assertRegex(icao, r'^[0-9a-f]+$', f'ICAO24 {icao} is not lowercase hex')

  def test_government_aircraft_included(self):
    # At least some government registrant (type 5) aircraft should be present
    self.assertGreater(len(self.aircrafts), 100)

  def test_excludes_universities(self):
    for icao, row in self.aircrafts.items():
      owner = row[3].lower()
      if 'university' in owner or 'college' in owner:
        # Only acceptable if it matches positive law enforcement keywords
        self.assertTrue(
          any(kw in owner for kw in ['police', 'sheriff', 'patrol', 'highway', 'law enforce']),
          f'University/college aircraft {icao} ({row[3]}) should be excluded')

  def test_excludes_military(self):
    for icao, row in self.aircrafts.items():
      owner = row[3].lower()
      self.assertNotIn('air force', owner, f'Military aircraft {icao} ({row[3]}) should be excluded')
      self.assertNotIn('navy', owner, f'Military aircraft {icao} ({row[3]}) should be excluded')

  def test_icao_type_descriptor_format(self):
    import re
    for icao, row in self.aircrafts.items():
      desc = row[2]
      if desc and desc != '???':
        self.assertRegex(desc, r'^[LSAHG?][0-9?][JTPE?]$',
          f'Invalid ICAO type descriptor "{desc}" for {icao}')

  def test_non_government_registrants_included(self):
    # With expanded registrant types, we should have some non-government entries
    # These should match law enforcement keywords
    has_non_gov = False
    for icao, row in self.aircrafts.items():
      owner = row[3].lower()
      if any(kw in owner for kw in ['llc', 'inc', 'corp']):
        has_non_gov = True
        break
    self.assertTrue(has_non_gov, 'Expected some non-government registrant aircraft')

class TestMergedOutput(unittest.TestCase):
  """Tests for the merged interesting_aircrafts.csv."""

  @classmethod
  def setUpClass(cls):
    cls.aircrafts = {}
    with open('data/interesting_aircrafts.csv', 'r') as f:
      for row in csv.reader(f):
        cls.aircrafts[row[0]] = row

    cls.opensky = {}
    with open('data/interesting_opensky_aircrafts.csv', 'r') as f:
      for row in csv.reader(f):
        cls.opensky[row[0]] = row

    cls.faa = {}
    with open('data/interesting_faa_aircrafts.csv', 'r') as f:
      for row in csv.reader(f):
        cls.faa[row[0]] = row

  def test_output_exists(self):
    self.assertTrue(os.path.exists('data/interesting_aircrafts.csv'))

  def test_merged_contains_all_opensky(self):
    for icao in self.opensky:
      self.assertIn(icao, self.aircrafts, f'OpenSky aircraft {icao} missing from merged output')

  def test_merged_contains_all_faa(self):
    for icao in self.faa:
      self.assertIn(icao, self.aircrafts, f'FAA aircraft {icao} missing from merged output')

  def test_merged_count_is_union(self):
    expected = set(self.opensky.keys()) | set(self.faa.keys())
    self.assertEqual(len(self.aircrafts), len(expected))

  def test_row_format_has_four_columns(self):
    for icao, row in self.aircrafts.items():
      self.assertEqual(len(row), 4, f'Row {icao} has {len(row)} columns, expected 4')

  def test_known_aircraft_retained(self):
    expected = {
      'a00137': ['a00137', 'Agusta Aerospace Corp', 'H2T', 'State Of New Jersey'],
      'a00196': ['a00196', 'Airbus Helicopters Inc', 'H1T', 'Pinellas County Sheriffs Office'],
      'a001c1': ['a001c1', 'Embraer Executive Aircraft Inc', 'L2J', 'State Of Texas'],
      'a002a3': ['a002a3', 'Cessna', 'L1P', 'Alameda County Sheriffs Office'],
      'a00ff1': ['a00ff1', 'Yuneec', 'H4E', 'Salt River Police Dept'],
    }
    for icao, exp_row in expected.items():
      self.assertIn(icao, self.aircrafts, f'Known aircraft {icao} missing')
      self.assertEqual(self.aircrafts[icao], exp_row,
        f'Known aircraft {icao} data mismatch: {self.aircrafts[icao]} != {exp_row}')

class TestAssetFile(unittest.TestCase):
  """Tests that the app asset file matches the merged output."""

  def test_asset_matches_merged(self):
    asset_path = '../app/src/main/assets/interesting_aircrafts.csv'
    merged_path = 'data/interesting_aircrafts.csv'

    with open(asset_path, 'r') as f:
      asset_lines = f.readlines()
    with open(merged_path, 'r') as f:
      merged_lines = f.readlines()

    self.assertEqual(len(asset_lines), len(merged_lines),
      f'Asset has {len(asset_lines)} rows, merged has {len(merged_lines)} rows')
    self.assertEqual(asset_lines, merged_lines, 'Asset file content differs from merged output')

if __name__ == '__main__':
  unittest.main()
