import csv
import re
import string
import zipfile

with zipfile.ZipFile('data/ReleasableAircraft.zip', 'r') as archive:
  master = archive.read('MASTER.txt');
  with open('data/faa_aircrafts.csv', 'wb') as f:
    f.write(master)
  ref = archive.read('ACFTREF.txt');
  with open('data/faa_ref.csv', 'wb') as f:
    f.write(ref)

faa_aircrafts = []
with open('data/faa_aircrafts.csv', 'r', encoding='ISO-8859-1') as f:
  reader = csv.reader(f)
  for row in reader:
    faa_aircrafts.append(row)

faa_ref = dict()
with open('data/faa_ref.csv', 'r', encoding='ISO-8859-1') as f:
  reader = csv.reader(f)
  for row in reader:
    faa_ref.update({ row[0] : row })

def interesting_gov(column):
  if not re.match('.*(versity|college|forest|naval|usda|mosquito|air force|usaf|aeronautic|commission|fire|aviation administration|school|nasa|environment|fish|club|institute|army|agriculture|development|laboratory|aviation authority|board|oceanic|museum|science|water|academy|weed|energy|career|consumer|airforce|engineer|ag center|project|arsenal|space|navy|marine|power|joey).*', column, re.IGNORECASE):
    return True
  return False

def interesting_other(column):
  if re.match('.*(patrol|police|policia|sheriff|state of|highway|law enforce).*', column, re.IGNORECASE):
    if not re.match('.*(civil air patrol|forest patrol|bearhawk patrol|patroller|patrol inc|patrols inc).*', column, re.IGNORECASE):
      return True
  return False

interesting_faa_aircrafts = []
for row in faa_aircrafts:
  if len(row) <= 33:
    continue
  if (row[5] == '5' and interesting_gov(row[6])) or (row[5] in ('1', '2', '3', '4', '7', '8', '9') and interesting_other(row[6])):
    ref = faa_ref.get(row[2])
    if ref is None:
      continue
    icao_description = ['?','?', '?']
    if re.match('.*[1].*', ref[5]):
      icao_description[0] = 'L'
    if re.match('.*[2].*', ref[5]):
      icao_description[0] = 'S'
    if re.match('.*[3].*', ref[5]):
      icao_description[0] = 'A'
    if re.match('.*[145].*', ref[3]):
      icao_description[0] = 'L'
    if re.match('.*[6].*', ref[3]):
      icao_description[0] = 'H'
    if re.match('.*[9].*', ref[3]):
      icao_description[0] = 'G'
    if re.match('.*[456].*', ref[4]):
      icao_description[2] = 'J'
    if re.match('.*[23].*', ref[4]):
      icao_description[2] = 'T'
    if re.match('.*[178].*', ref[4]):
      icao_description[2] = 'P'
    if re.match('.*10.*', ref[4]):
      icao_description[2] = 'E'
    try:
      icao_description[1] = '{:1}'.format(int(ref[7]))[-1:]
    except (ValueError, IndexError):
      icao_description[1] = '1'
    interesting_faa_aircrafts.append([row[33].strip().lower(),string.capwords(ref[1].strip()),''.join(icao_description),string.capwords(row[6].strip())])

with open('data/interesting_faa_aircrafts.csv', 'w') as f:
  writer = csv.writer(f)
  for row in interesting_faa_aircrafts:
    writer.writerow(row)

