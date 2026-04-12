import csv
import re

opensky_aircrafts = []
with open('data/aircraft-database-complete-2025-08.csv', 'r', encoding='ISO-8859-1') as f:
  reader = csv.reader(f, quotechar="'")
  next(reader)  # skip header
  for row in reader:
    opensky_aircrafts.append(row)

def interesting(column):
  if re.match('.*(patrol|police|policia|sheriff|state of|highway|law enforce).*', column, re.IGNORECASE):
    if not re.match('.*(PATROL).*', column):
      if not re.match('.*(civil air patrol|forest patrol ltd|bearhawk patrol|llc|patroller|patrol inc|patrols inc).*', column, re.IGNORECASE):
        return True
  return False

interesting_opensky_aircrafts = []
for row in opensky_aircrafts:
  if len(row) > 22 and row[0][:1] == 'a' and (interesting(row[18]) or interesting(row[22])):
    interesting_opensky_aircrafts.append([row[0],row[13],row[10],row[22]])

with open('data/interesting_opensky_aircrafts.csv', 'w') as f:
  writer = csv.writer(f)
  for row in interesting_opensky_aircrafts:
    writer.writerow(row)
