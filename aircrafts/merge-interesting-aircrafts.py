import csv
import re

interesting_opensky_aircrafts = dict()
with open('data/interesting_opensky_aircrafts.csv', 'r', encoding='ISO-8859-1') as f:
  reader = csv.reader(f)
  for row in reader:
    interesting_opensky_aircrafts.update({ row[0] : row })

interesting_faa_aircrafts = dict()
with open('data/interesting_faa_aircrafts.csv', 'r', encoding='ISO-8859-1') as f:
  reader = csv.reader(f)
  for row in reader:
    interesting_faa_aircrafts.update({ row[0] : row })

with open('data/test_common_interesting_opensky_aircrafts.csv', 'w') as f1:
  writer1 = csv.writer(f1)
  with open('data/test_common_interesting_faa_aircrafts.csv', 'w') as f2:
    writer2 = csv.writer(f2)
    for icao, row in interesting_opensky_aircrafts.items():
      if icao in interesting_faa_aircrafts:
        writer1.writerow(row)
        writer2.writerow(interesting_faa_aircrafts[icao])

with open('data/test_only_in_interesting_opensky_aircrafts.csv', 'w') as f:
  writer = csv.writer(f)
  for icao, row in interesting_opensky_aircrafts.items():
    if icao not in interesting_faa_aircrafts:
      writer.writerow(row)

with open('data/test_only_in_interesting_faa_aircrafts.csv', 'w') as f:
  writer = csv.writer(f)
  for icao, row in interesting_faa_aircrafts.items():
    if icao not in interesting_opensky_aircrafts:
      writer.writerow(row)

interesting_aircrafts = dict()
interesting_aircrafts.update(interesting_opensky_aircrafts)
interesting_aircrafts.update(interesting_faa_aircrafts)
with open('data/interesting_aircrafts.csv', 'w') as f:
  writer = csv.writer(f)
  for icao, row in interesting_aircrafts.items():
    writer.writerow(row)

