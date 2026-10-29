#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$repo_root"
final_image=${1:-kronos-keycloak:26.8.1-custom}
platform=${KEYCLOAK_PLATFORM:-linux/amd64}
snapshot=${DEBIAN_SNAPSHOT:-20261028T183000Z}
cache_dir=${TRIVY_CACHE_DIR:-/tmp/kronos-trivy-keycloak-cache}
report_dir=${TRIVY_REPORT_DIR:-/tmp/kronos-trivy-keycloak-report}
control_image=${TRIVY_CONTROL_IMAGE:-kronos-keycloak-trivy-control:debian13-$$}
mkdir -p "$cache_dir" "$report_dir"
control_report="$report_dir/debian13-vulnerable-control.json"
control_built=false
cleanup() {
  if [[ "$control_built" == true ]]; then
    docker image rm "$control_image" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if docker image inspect "$control_image" >/dev/null 2>&1; then
  echo "Refusing to overwrite a pre-existing control image: $control_image" >&2
  exit 1
fi

os_release=$(docker run --rm --entrypoint /bin/cat "$final_image" /etc/os-release)
grep -qx 'ID=debian' <<<"$os_release"
grep -qx 'VERSION_ID="13"' <<<"$os_release"
grep -qx 'VERSION_CODENAME=trixie' <<<"$os_release"
metadata=$(docker run --rm --entrypoint /bin/cat "$final_image" /usr/share/keycloak-image/BUILD-METADATA.txt)
grep -Fx 'OpenJDKPackage=openjdk-21-jre-headless=21.0.13+9-1~deb13u2' <<<"$metadata"
grep -Eq '^openjdk-21-jre-headless(:amd64)?=21\.0\.13\+9-1~deb13u2$' <<<"$metadata"
grep -Eq '^ca-certificates=[^[:space:]]+$' <<<"$metadata"
grep -Eq '^libc6(:amd64)?=[^[:space:]]+$' <<<"$metadata"

docker build --pull --platform "$platform" \
  --build-arg "DEBIAN_SNAPSHOT=$snapshot" \
  --file infra/keycloak/Dockerfile.scan-coverage-control \
  --tag "$control_image" .
control_built=true

control_os=$(docker run --rm --entrypoint /bin/cat "$control_image" /etc/os-release)
grep -qx 'ID=debian' <<<"$control_os"
grep -qx 'VERSION_ID="13"' <<<"$control_os"
grep -qx 'VERSION_CODENAME=trixie' <<<"$control_os"
control_package=$(docker run --rm --entrypoint /usr/bin/dpkg-query "$control_image" \
  -W '-f=${binary:Package}=${Version}' openjdk-21-jre-headless)
grep -Eq '^openjdk-21-jre-headless(:amd64)?=21\.0\.12\.1\+1-1~deb13u1$' <<<"$control_package"

docker run --rm \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v "$cache_dir:/root/.cache" \
  -v "$report_dir:/reports" \
  aquasec/trivy:0.75.0 image \
  --image-src docker \
  --scanners vuln \
  --ignore-unfixed=false \
  --severity HIGH,CRITICAL \
  --format json \
  --output /reports/debian13-vulnerable-control.json \
  "$control_image"

jq -e '
  any(.Results[]; .Type == "debian" and .Class == "os-pkgs" and (.Target | test("debian 13"))) and
  any(.Results[]; .Type == "debian" and any(.Vulnerabilities[]?;
    .VulnerabilityID == "CVE-2026-91415" and
    (.PkgName | contains("openjdk-21-jre-headless")) and
    .InstalledVersion == "21.0.12.1+1-1~deb13u1"))
' "$control_report" >/dev/null
echo "Coverage proven: Trivy 0.75.0 maps Debian 13 OS packages to Debian advisories; the isolated vulnerable OpenJDK control reported CVE-2026-91415."
echo "Control report=$control_report"
