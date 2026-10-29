#!/usr/bin/env bash
set -euo pipefail

image_ref=${1:-kronos-keycloak:26.8.1-custom}
cache_dir=${TRIVY_CACHE_DIR:-/tmp/kronos-trivy-keycloak-cache}
mkdir -p "$cache_dir"

docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$cache_dir:/root/.cache" \
  aquasec/trivy:0.75.0 image \
  --image-src docker \
  --scanners vuln \
  --severity HIGH,CRITICAL \
  --ignore-unfixed=false \
  --exit-code 1 \
  --format table \
  "$image_ref"
