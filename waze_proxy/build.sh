#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
podman build -t waze-proxy .
