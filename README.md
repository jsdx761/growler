# Growler

[![Android CI](https://github.com/jsdx761/Growler/actions/workflows/android.yml/badge.svg)](https://github.com/jsdx761/Growler/actions/workflows/android.yml)

A companion Android app for the [Radenso DS1](https://radenso.com/products/radenso-ds1) radar detector, forked from [Radenso Nexus](https://github.com/nolimits-enterprises/RadensoNexus). Growler adds voice announcements, crowd-sourced alerts, and surveillance aircraft detection on top of the original DS1 radar detector alerts.

## Features

- **Unified alert list** -- radar detector alerts, crowd-sourced alerts, and potential surveillance aircraft alerts combined in a single prioritized view
- **Voice announcements** -- clear, natural voice alerts with distance and bearing (e.g. "Speed trap at 11 o'clock 1.5 miles away on I-280")
- **Sound reminders** -- non-intrusive earcon notifications while alerts remain in range
- **Status announcements** -- device, network, and location status changes announced with smart delays to reduce noise
- **Seamless audio** -- plays on the Music audio stream with ducking, so alerts blend with your music over Bluetooth without delays or interruptions

Designed for a hands-off, eyes-on-the-road, no-stress driving experience.

## Alert Sources

### Radar Detector (DS1)

Connects to the DS1 over Bluetooth Low Energy. Supports all DS1 alert types:

| Alert Type | Description |
|------------|-------------|
| X, K, KA | Standard radar bands |
| POP | POP K band |
| MRCD, MRCT | MultaRadar CD/CT photo radar |
| GT3, GT4 | Gatso radar |
| Laser | Lidar |
| Speed Cam | Fixed speed cameras (from DS1 database) |
| Red Light Cam | Fixed red light cameras (from DS1 database) |

Growler trusts the DS1's filtering, lockouts, and muting without adding logic on top. It reports exactly what the DS1 provides. Alerts auto-clear after 10 seconds of no events, matching real DS1 behavior.

### Crowd-sourced Alerts

Fetches live traffic reports from a crowd-sourced alert server within a configurable radius (default 2 miles). Supports four report types, each individually toggleable:

| Type | Display Name | Default |
|------|-------------|---------|
| POLICE | Speed Trap / hidden | On |
| ACCIDENT | Accident | On |
| HAZARD | Construction, Emergency Vehicle, Lane Closed, Stopped Vehicle, Pothole, Road Obstacle, Ice, Oil, Flooding, Fog, Hail, Heavy Rain/Snow, etc. | Off |
| JAM | Standstill/Heavy/Moderate Traffic | Off |

Reports are fetched every 24 seconds with automatic retry. Nearby duplicates within 1/8 mile sharing the same type, description, and road are deduplicated. Reports are prioritized by estimated time-to-arrival when moving, falling back to distance when stopped.

### Aircraft Surveillance Detection

Tracks airborne aircraft via the OpenSky Network API and cross-references them against a local database of surveillance-related aircraft (law enforcement, government agencies). Alerts on matching aircraft within a 5-mile radius.

- **Unauthenticated polling**: every 4 minutes
- **Authenticated polling** (with OpenSky credentials): every 24 seconds
- **Aircraft types**: Identifies airplanes, helicopters, and drones from ICAO type designators
- **Owner display**: Shows the registered owner/operator (e.g. "State Police", "County Sheriff")

## Crowd-sourced Alert Proxy

The crowd-sourced alerts require a self-hosted proxy server that sits between the app and the Waze live map. A proxy is necessary because Waze's georss API is protected by reCAPTCHA Enterprise, which cannot be handled directly from a mobile app. The proxy runs a real Chrome browser (via Playwright) to pass reCAPTCHA transparently, then exposes a simple authenticated `GET /georss` endpoint that the app polls.

The proxy runs in a Podman container with Xvfb (virtual framebuffer) so Chrome operates in headed mode without a physical display. Each API request creates a fresh browser context to avoid reCAPTCHA session degradation that occurs with long-lived sessions.

The app is not coupled to Waze specifically -- any server implementing the same `/georss` JSON response format can be used as a drop-in replacement. See [`waze_proxy/README.md`](waze_proxy/README.md) for the full API spec, deployment instructions, and how to implement a compatible server.

## Voice and Audio

Growler supports two voice engines, selectable at build time:

- **Live TTS** (default) -- Uses Android's built-in Text-to-Speech engine with a local offline British English voice. No setup required.
- **Pre-recorded segments** -- Uses a voice-cloning TTS pipeline (Voxtral or ElevenLabs) to generate WAV segments offline. At runtime the `PreRecordedTtsEngine` concatenates segments with crossfading, producing consistent, natural announcements similar to high-end car navigation systems. See [`voice/README.md`](voice/README.md) for full pipeline setup, voice cloning, and segment generation instructions.

The pre-recorded engine requires running the voice generation pipeline before building (see [Voice Segments](#voice-segments-optional) below). Without the generated assets, the app falls back to live TTS.

Announcements are formatted for clarity while driving:

- **Radar**: "K A band 34.7 Radar" with band-specific earcon tones
- **Reports**: "Speed trap at 3 o'clock 0.8 mile away on US-101 S" or "Accident now at 11 o'clock 1.2 miles away on University Ave"
- **Aircraft**: "State Police Cessna Airplane at 2 o'clock 3.1 miles away"
- **All clear**: "Report alerts are all clear now" / "Aircraft alerts are all clear now"

Each alert class has a distinct earcon sound. Higher-priority alerts interrupt lower-priority announcements in progress.

### Alert Priority

Alerts are announced in priority order: Laser > Cameras > KA > K > Other Radar > Reports > Aircraft.

### Alert Reminders

- **Radar**: earcon reminders play every second while radar alerts are active
- **Reports/Aircraft**: re-announced every 60 seconds when reminders are enabled, or when closing by 1/4 mile (reports) / 1 mile (aircraft), or when bearing changes by 3+ clock hours

### Audio Setup

Growler plays announcements on the **Music** audio stream using `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` so that music automatically ducks during announcements. Select your phone's Bluetooth as the audio source in your vehicle. TTS pitch is set to 0.95 (slightly lower to cut through road and engine noise) and rate to 1.1x (slightly faster to keep alerts brief while music is ducked). The voice is a local offline British English TTS voice chosen to be consistent with the built-in navigation system in a Jaguar F-Pace.

## Main Screen

The alert list shows all active alerts in a RecyclerView with:

- Alert type and band/owner
- Signal strength or proximity progress bar
- Distance (in miles) and frequency (in GHz, for radar)
- Clock-position bearing relative to vehicle heading

Five status icons at the top indicate connectivity: Radar (DS1), Reports, Aircraft, Network, and Location.

## Getting Started

### Prerequisites

- Android 13+ device (tested on Pixel 7)
- Android SDK Platform Tools v34+, SDK v35+
- Java OpenJDK 17+

### Android SDK Setup (Command Line)

> **Note:** The following is for background/context only and may vary depending on your system environment, OS, and shell. Refer to the official [Android command line tools](https://developer.android.com/studio#command-line-tools-only) documentation for the latest instructions.

1. Install Java OpenJDK 17+:

```sh
# Fedora/RHEL
sudo dnf install java-17-openjdk-devel

# Ubuntu/Debian
sudo apt install openjdk-17-jdk

# macOS (Homebrew)
brew install openjdk@17
```

2. Download the [Android command line tools](https://developer.android.com/studio#command-line-tools-only) and set up the SDK directory:

```sh
mkdir -p ~/Android/Sdk/cmdline-tools
unzip commandlinetools-linux-*-latest.zip -d ~/Android/Sdk/cmdline-tools
mv ~/Android/Sdk/cmdline-tools/cmdline-tools ~/Android/Sdk/cmdline-tools/latest
```

3. Set environment variables (add to your shell profile, e.g. `~/.bashrc` or `~/.zshrc`):

```sh
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

4. Accept licenses and install required SDK packages:

```sh
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

5. Verify the installation:

```sh
sdkmanager --list_installed
adb --version
```

6. Update `local.properties` in the project root to point to your SDK:

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
```

### Voice Segments (Optional)

The pre-recorded voice segment assets are gitignored and must be
generated before building if you want the `PreRecordedTtsEngine`. Without
them, the app falls back to Android's live TTS engine.

See [`voice/README.md`](voice/README.md) for full setup instructions. In
short:

1. Place reference voice recordings in `voice/reference_recordings/`
2. Set API keys in `voice/.env`
3. Run the voice pipeline: `cd voice && python pipeline.py`

### Build

```sh
./gradlew build
```

### Install via ADB

Connect your phone wirelessly:

```sh
adb pair <pairing-address:port> <pairing-code>
adb connect <debugging-address:port>
adb devices
```

Install the APK:

```sh
./gradlew installDebug
```

Or install manually:

```sh
adb install -r ./app/build/outputs/apk/debug/app-debug.apk
```

Growler installs alongside the original Radenso Nexus app -- they have separate package names.

### Download a Pre-built APK

CI builds are available in the artifacts section of the [Android CI workflow](https://github.com/jsdx761/Growler/actions/workflows/android.yml).

## Files Not in Git

Several files are gitignored and must be provided or generated after a
fresh clone:

| File / Directory | Purpose | How to set up |
|------------------|---------|---------------|
| `local.properties` | Android SDK path | `echo "sdk.dir=$HOME/Android/Sdk" > local.properties` |
| `voice/.env` | Mistral / ElevenLabs API keys | Copy from `voice/.env.example` and fill in keys |
| `voice/reference_recordings/*.wav` | Voice samples for cloning | Place WAV clips of the target voice (see [`voice/README.md`](voice/README.md)) |
| `voice/output/` | Generated voice files | Run `cd voice && python pipeline.py` |
| `app/src/main/assets/voice_segments*/` | Packaged voice segments for the app | Generated by voice pipeline stage 3 |
| `voice_tuning/reference/*.wav` | Reference recordings for voice tuning | Place WAV clips (same source as `voice/reference_recordings/`) |
| `aircrafts/data/*.csv`, `aircrafts/data/*.zip` | Source aircraft databases | Download from OpenSky / FAA (see [Aircraft Database](#aircraft-database-optional)) |

## Permissions

On first launch, Growler requests the following permissions in sequence:

| Permission | Purpose |
|------------|---------|
| Fine Location | Crowd-sourced and aircraft alerts near you |
| Background Location | Alerts while the app is backgrounded |
| Bluetooth Scan | Discover DS1 devices |
| Bluetooth Connect | Communicate with the DS1 |

Additional manifest permissions include Internet access, network state monitoring, wake lock, foreground service, notifications, and audio settings modification.

## Settings

Settings are organized into three sections:

### Alert Sources

| Setting | Description |
|---------|-------------|
| **Radar Detector** | Scan for and select your DS1. The app reconnects automatically on future launches. |
| **DS1 Volume** | Control DS1 speaker volume from the app (0-100). Set to 0 if using app-based voice alerts only. |
| **Crowd-sourced Alerts** | Enable/disable, configure server URL and region, toggle individual report types (Police, Accident, Hazard, Jam), and enable/disable reminder announcements. |
| **Aircraft Alerts** | Enable/disable, configure OpenSky API URL, optional username/password for higher rate limits, and enable/disable reminder announcements. |

### Location

| Setting | Description |
|---------|-------------|
| **Update Interval** | GPS location update frequency in milliseconds (default 5000ms). |

### Battery Usage

| Setting | Description |
|---------|-------------|
| **Foreground Service** | Run as a foreground service with a persistent notification to prevent the OS from killing the app. Enabled by default. |
| **Wake Lock** | Keep the CPU active to maintain location tracking and alert fetching. Enabled by default. |

## Aircraft Database (Optional)

To include up-to-date surveillance aircraft data, download public databases and process them with the included tools.

1. Download the [OpenSky aircraft database](https://opensky-network.org/datasets/metadata/) CSV and save as `aircrafts/data/aircraft-database-complete-<date>.csv` (e.g. `aircraft-database-complete-2025-08.csv`)

2. Download the [FAA aircraft registry](https://registry.faa.gov/database/ReleasableAircraft.zip) and save as `aircrafts/data/ReleasableAircraft.zip`

3. Run the filter and merge tools:

```sh
cd aircrafts
python ./filter-opensky-aircrafts.py
python ./filter-faa-aircrafts.py
python ./merge-interesting-aircrafts.py
cp ./data/interesting_aircrafts.csv ../app/src/main/assets/interesting_aircrafts.csv
```

4. Run the pipeline tests to verify the output:

```sh
python -m unittest test-filter-aircrafts -v
```

The OpenSky filter matches aircraft with ownership containing keywords like "patrol", "police", "sheriff", "state of", "highway", and "law enforce", excluding civil air patrol and commercial entities. The FAA filter selects government aircraft (registrant type 5) and civilian aircraft matching law enforcement keywords. The merge script combines both sources by ICAO24 transponder code, deduplicating entries.

## Simulating Radar Events

Radar detection can be simulated at runtime via ADB broadcasts, without needing a DS1 device connected. This is useful for testing alert sounds, voice announcements, and the overall user experience.

**Simulate a radar detection:**

```sh
# KA band at 34.7 GHz
adb shell am broadcast -a com.jsd.x761.growler.SIMULATE_RADAR --es band KA --ef freq 34.7

# K band at 24.1 GHz
adb shell am broadcast -a com.jsd.x761.growler.SIMULATE_RADAR --es band K --ef freq 24.1

# Laser
adb shell am broadcast -a com.jsd.x761.growler.SIMULATE_RADAR --es band Laser

# With custom signal intensity (default 50)
adb shell am broadcast -a com.jsd.x761.growler.SIMULATE_RADAR --es band KA --ef freq 34.7 --ef intensity 80
```

Supported bands: `X`, `K`, `KA`, `POP`, `MRCD`, `MRCT`, `GT3`, `GT4`, `Laser`.

Multiple bands can be active simultaneously -- each broadcast adds to the set of active alerts. Alerts auto-clear after 10 seconds of no events, just like real DS1 behavior.

**Simulate rapid-fire events:**

```sh
for i in $(seq 1 10); do adb shell am broadcast -a com.jsd.x761.growler.SIMULATE_RADAR --es band KA --ef freq 34.7; sleep 0.3; done
```

**Clear all simulated radar alerts immediately:**

```sh
adb shell am broadcast -a com.jsd.x761.growler.SIMULATE_RADAR_CLEAR
```

### Simulating Crowd-sourced Reports

Crowd-sourced report alerts can be simulated via ADB broadcasts. The app must have a location (real or mock) for report simulation to work.

**Simulate a report:**

```sh
# Police visible on US-101 S
adb shell am broadcast -a com.jsd.x761.growler.SIMULATE_REPORT \
  --es type POLICE --es subtype POLICE_VISIBLE \
  --es city "San Mateo, CA" --es street "US-101 S" \
  --ed lat 37.581049 --ed lng -122.325196

# Accident on University Ave
adb shell am broadcast -a com.jsd.x761.growler.SIMULATE_REPORT \
  --es type ACCIDENT --es subtype HAZARD_ON_ROAD_CONSTRUCTION \
  --es city "East Palo Alto, CA" --es street "University Ave" \
  --ed lat 37.458524 --ed lng -122.141221
```

Supported types: `POLICE`, `ACCIDENT`, `HAZARD`, `JAM`. Supported subtypes: `POLICE_VISIBLE`, `POLICE_HIDDEN`, `HAZARD_ON_ROAD_CONSTRUCTION`, `HAZARD_ON_ROAD_OBJECT`, etc.

**Clear all simulated reports:**

```sh
adb shell am broadcast -a com.jsd.x761.growler.SIMULATE_REPORT_CLEAR
```

### Simulating Aircraft Alerts

Aircraft alerts can be simulated via ADB broadcasts. The app must have a location (real or mock) for aircraft simulation to work.

**Simulate an aircraft:**

```sh
# Surveillance aircraft nearby
adb shell am broadcast -a com.jsd.x761.growler.SIMULATE_AIRCRAFT \
  --es transponder a6165b --es type "Fixed wing single engine" \
  --es owner "State Police" \
  --es manufacturer "CESSNA" \
  --ed lat 37.498 --ed lng -121.9982 --ef altitude 1676.4

# Another aircraft
adb shell am broadcast -a com.jsd.x761.growler.SIMULATE_AIRCRAFT \
  --es transponder a54f11 --es type "Fixed wing multi engine" \
  --es owner "County Sheriff" --es manufacturer "CESSNA" \
  --ed lat 37.4636 --ed lng -122.0931 --ef altitude 259.08
```

**Clear all simulated aircraft:**

```sh
adb shell am broadcast -a com.jsd.x761.growler.SIMULATE_AIRCRAFT_CLEAR
```

## Configuration

Most tunable parameters are defined as constants in [`Configuration.java`](app/src/main/java/com/jsd/x761/nexus/Configuration.java), including:

- **Timers**: DS1 connect/reconnect delays, alert clear timeout (10s), reminder intervals (1s radar, 60s reports/aircraft), all-clear check (10s)
- **Audio**: speech pitch (0.95), speech rate (1.1x), earcon delay (500ms)
- **Network**: connectivity check interval (15s), connection timeout (3s), retry counts and delays
- **Location**: update frequency (5s), bearing computation threshold, availability check interval (10s)
- **Detection ranges**: reports (2 miles), aircraft (5 miles), report deduplication (1/8 mile)
- **Announcement limits**: max speech announces per alert class (3), max earcon announces per class (1-3)
- **Debug/Demo modes**: test data injection, zero-bearing mode, verbose logging

## Design Decisions

**Radar detection** -- Growler trusts the DS1's filtering, lockouts, and muting without adding logic on top. It reports exactly what the DS1 provides.

**Crowd-sourced alerts** -- Reports the closest alerts within a 2-mile radius regardless of road or route, to catch threats from side roads and avoid missing misplaced reports.

**Aircraft alerts** -- Uses ownership and operator data from OpenSky/FAA databases rather than flight pattern analysis. Reports aircraft within a 5-mile radius.

**Alert reminders** -- Re-announced when crowd-sourced alerts close by 1/4 mile, aircraft close by 1 mile, or bearing changes by 3+ hours. An "all clear" announcement plays when all alerts leave range.

**Audio approach** -- Uses the Android Music stream with `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` instead of Bluetooth SCO/CAI to avoid ~1 second announcement delays. Earcons are played via SoundPool for reliable playback independent of the TTS engine's audio session lifecycle. This requires the phone to be the selected Bluetooth audio source.

**Report prioritization** -- Crowd-sourced reports are sorted by estimated time-to-arrival (distance / speed) when the vehicle is moving, falling back to raw distance when stopped. This surfaces imminent threats first regardless of absolute distance.

## Future Ideas

- **SDR-based aircraft detection** -- Use a Software Defined Radio dongle with a Raspberry Pi running [dump1090](https://github.com/jsdx761/dump1090) / [dump978](https://github.com/jsdx761/dump978) for offline aircraft detection over BLE
- **Radio frequency monitoring** -- Detect activity on public safety frequencies (138-174 MHz, 380-512 MHz, 769-824 MHz, 851-869 MHz) using [rtl-power-fftw](https://github.com/jsdx761/rtl-power-fftw), alerting on power patterns without recording content

## Resources

- [Radenso GitHub Repositories](https://github.com/nolimits-enterprises)
- [Vortex Radar -- DS1 Setup Guide](https://www.vortexradar.com/2021/08/how-to-set-up-configure-your-radenso-ds1/)
- [Vortex Radar -- FAQ](https://www.vortexradar.com/faq/)
- [RD Forum](https://www.rdforum.org/)
- [OpenSky Network REST API](https://openskynetwork.github.io/opensky-api/rest.html)
- [ICAO Aviation Standards](https://www.icao.int/Pages/default.aspx)
- [FAA Aircraft Registry](https://www.faa.gov/licenses_certificates/aircraft_certification/aircraft_registry/releasable_aircraft_download)
- [ADS-B Exchange](https://adsbexchange.com/)
- [Waze for Cities](https://support.google.com/waze/partners/answer/13458165)
- [Geospatial Formulas](https://www.movable-type.co.uk/scripts/latlong.html)

## Disclaimers

This is a personal project, unrelated to any employer. Do not use the app to violate the terms of service of any crowd-sourced alert server. Ensure compliance with local laws regarding radar detector use.

## Acknowledgments

Many thanks to Radenso for open-sourcing the original [Radenso Nexus](https://github.com/nolimits-enterprises/RadensoNexus) codebase.
