#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$repo_root"
image_ref=${1:-kronos-keycloak:26.8.1-custom}
cache_dir=${TRIVY_CACHE_DIR:-/tmp/kronos-trivy-keycloak-cache}
report_dir=${TRIVY_REPORT_DIR:-/tmp/kronos-trivy-keycloak-report}
report_name=$(printf '%s' "$image_ref" | tr -c '[:alnum:]_.-' '_')
report_path="$report_dir/${report_name}.json"
mkdir -p "$cache_dir" "$report_dir"

os_release=$(docker run --rm --entrypoint /bin/cat "$image_ref" /etc/os-release)
grep -qx 'ID=debian' <<<"$os_release"
grep -qx 'VERSION_ID="13"' <<<"$os_release"
grep -qx 'VERSION_CODENAME=trixie' <<<"$os_release"

metadata=$(docker run --rm --entrypoint /bin/cat "$image_ref" /usr/share/keycloak-image/BUILD-METADATA.txt)
grep -Fx 'DebianBase=debian:trixie-slim@sha256:f6b08b09a05725f9431502d3b5714f5c4576e82c1a84fa1debe0c24a7e0c3ec4' <<<"$metadata"
grep -Fx 'OpenJDKPackage=openjdk-21-jre-headless=21.0.13+9-1~deb13u2' <<<"$metadata"
grep -Eq '^openjdk-21-jre-headless(:amd64)?=21\.0\.13\+9-1~deb13u2$' <<<"$metadata"
grep -Eq '^ca-certificates=[^[:space:]]+$' <<<"$metadata"
grep -Eq '^libc6(:amd64)?=[^[:space:]]+$' <<<"$metadata"

set +e
docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$cache_dir:/root/.cache" \
  -v "$report_dir:/reports" \
  aquasec/trivy:0.75.0 image \
  --image-src docker \
  --scanners vuln \
  --severity HIGH,CRITICAL \
  --ignore-unfixed=false \
  --exit-code 1 \
  --format json \
  --output "/reports/${report_name}.json" \
  "$image_ref"
scan_status=$?
set -e

jq -e 'any(.Results[]; .Type == "debian" and .Class == "os-pkgs" and (.Target | test("debian 13")))' "$report_path" >/dev/null
echo "OS advisory target verified: Debian 13/Trixie; package inventory and OpenJDK security version verified."
jq -r '[.Results[].Vulnerabilities[]?] | group_by(.Severity) | map("\(.[0].Severity)=\(length)") | if length == 0 then "Vulnerabilities=0" else join(" ") end' "$report_path"
echo "TrivyReport=$report_path"
exit "$scan_status"
