#!/usr/bin/env bash
set -euo pipefail

cleanup() {
    echo "Shutting down..."
    kill "$SERVER_PID" 2>/dev/null || true
    kill "$OPENBOX_PID" 2>/dev/null || true
    kill "$XVFB_PID" 2>/dev/null || true
    wait 2>/dev/null || true
}
trap cleanup SIGTERM SIGINT EXIT

# Start Xvfb
Xvfb :99 -screen 0 1280x800x24 -ac -nolisten tcp &
XVFB_PID=$!
export DISPLAY=:99
sleep 1

# Start a window manager (Chrome needs proper window geometry)
eval "$(dbus-launch --sh-syntax)" 2>/dev/null || true
openbox &
OPENBOX_PID=$!
sleep 1

echo "Starting Waze proxy server"
python3 /app/server.py "$@" &
SERVER_PID=$!

wait "$SERVER_PID"
