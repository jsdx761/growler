# Crowd-sourced Alert Proxy Server

A proxy server that fetches crowd-sourced traffic alert data (police,
accidents, hazards, traffic jams) from the Waze live map and exposes
it as a simple HTTP API. It uses a real Google Chrome browser to handle
Waze's reCAPTCHA Enterprise authentication transparently.

## Quick Start with Podman

Build and run in one step:

```bash
cd waze_proxy
podman build -t waze-proxy .
podman run --network=host waze-proxy --api-key YOUR_SECRET --port 8080
```

Test it:
```bash
curl -H "X-API-Key: YOUR_SECRET" \
  "http://localhost:8080/georss?bottom=37.30&left=-121.88&top=37.36&right=-121.81&env=na&types=alerts"
```

## Quick Start without Podman

Requires Python 3.10+ and Google Chrome (`google-chrome-stable`).

```bash
cd waze_proxy
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
playwright install chromium
python server.py --port 8080 --api-key YOUR_SECRET
```

## Deploying to a Remote Server

Export the container image, transfer it via scp, and run it with podman
on the remote machine.

Build and export:
```bash
cd waze_proxy
./build.sh
podman save -o waze-proxy.tar localhost/waze-proxy
```

Transfer and load:
```bash
scp waze-proxy.tar REMOTE_HOST:
ssh REMOTE_HOST 'podman load -i waze-proxy.tar'
```

Run on the remote machine:
```bash
ssh REMOTE_HOST 'podman run --rm --name waze-proxy --network=host localhost/waze-proxy --api-key YOUR_SECRET --port 8080'
```

Then point the Android app at the remote server's IP or hostname
instead of localhost.

## Connecting the Android App

In the Growler app, go to Settings > Crowd-sourced Alerts and set:

- **URL**: `http://YOUR_SERVER_IP:8080` (e.g. `http://192.168.1.50:8080`)
- **API key**: the same key you passed to `--api-key`
- **Region**: `na` (North America), `row` (rest of world), or `il` (Israel)

The app polls the proxy every 24 seconds for alerts near your location.

## API Reference

### `GET /georss`

Returns crowd-sourced traffic alerts within a geographic bounding box.

**Query parameters:**

| Parameter | Type   | Required | Description |
|-----------|--------|----------|-------------|
| `bottom`  | float  | yes      | Southern latitude of bounding box |
| `left`    | float  | yes      | Western longitude of bounding box |
| `top`     | float  | yes      | Northern latitude of bounding box |
| `right`   | float  | yes      | Eastern longitude of bounding box |
| `env`     | string | yes      | Region: `na`, `row`, or `il` |
| `types`   | string | yes      | Comma-separated: `alerts`, `traffic`, `users` |

**Headers:**

| Header      | Required       | Description |
|-------------|----------------|-------------|
| `X-API-Key` | if configured  | API key for authentication |

**Success response (200):**
```json
{
  "alerts": [
    {
      "type": "POLICE",
      "subtype": "POLICE_VISIBLE",
      "street": "US-101 S",
      "city": "San Mateo, CA",
      "location": { "x": -122.325196, "y": 37.581049 },
      "nThumbsUp": 4,
      "reliability": 9
    }
  ]
}
```

**Alert types:** `POLICE`, `ACCIDENT`, `HAZARD`, `JAM`

**Error responses:**

| Status | Meaning |
|--------|---------|
| 400    | Missing query parameters |
| 401    | Invalid API key |
| 502    | Upstream API error |
| 503    | Browser not ready yet |
| 504    | Request timed out |

### `GET /health`

Health check (no authentication required).

```json
{ "status": "ok", "browser_ready": true }
```

## How It Works

Each API request creates a fresh Chrome browser context, loads the
Waze live map page, waits for the reCAPTCHA session to initialize,
makes the georss API call, and discards the context. This prevents
reCAPTCHA session degradation that occurs with long-lived sessions
in virtual display environments.

The container uses Xvfb (virtual framebuffer) and openbox (window
manager) so Chrome runs in headed mode without a physical display.
Headed mode is required because reCAPTCHA Enterprise detects and blocks
headless browsers.

## Implementing Your Own Compatible Server

Expose a `GET /georss` endpoint that returns a JSON object with an
`alerts` array. Each alert needs at minimum:

- `type` (string): `POLICE`, `ACCIDENT`, `HAZARD`, or `JAM`
- `subtype` (string): detailed subtype (can be empty)
- `street` (string): road/street name
- `city` (string): city name (e.g. `"San Mateo, CA"`)
- `location` (object): `{"x": longitude, "y": latitude}`
- `nThumbsUp` (integer): endorsement count (0 if unknown)

Optional: `reliability`, `reportRating`, `confidence`, `country`,
`additionalInfo`, `reportDescription`.

If using API key auth, check the `X-API-Key` header and return 401
for invalid keys.
