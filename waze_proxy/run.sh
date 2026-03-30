#!/usr/bin/env bash
set -e

API_KEY="${1:?Usage: ./run.sh API_KEY [PORT]}"
PORT="${2:-8080}"

podman stop waze-proxy 2>/dev/null || true
podman rm waze-proxy 2>/dev/null || true

echo "Starting waze-proxy on port $PORT"
podman run --rm --name waze-proxy --network=host waze-proxy --api-key "$API_KEY" --port "$PORT"
