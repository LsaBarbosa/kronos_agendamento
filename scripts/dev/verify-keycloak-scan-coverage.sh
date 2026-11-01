#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$repo_root"
final_image=${1:-kronos-keycloak:26.8.1-custom}
platform=${KEYCLOAK_PLATFORM:-linux/amd64}
cache_dir=${TRIVY_CACHE_DIR:-/tmp/kronos-trivy-keycloak-cache}
report_dir=${TRIVY_REPORT_DIR:-/tmp/kronos-trivy-keycloak-report}
control_image=${TRIVY_CONTROL_IMAGE:-kronos-keycloak-trivy-control:noble-$$}
mkdir -p "$cache_dir" "$report_dir"
control_report="$report_dir/ubuntu-noble-brotli-control.json"
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
grep -qx 'ID=ubuntu' <<<"$os_release"
grep -qx 'VERSION_ID="24.04"' <<<"$os_release"
grep -qx 'VERSION_CODENAME=noble' <<<"$os_release"
metadata=$(docker run --rm --entrypoint /bin/cat "$final_image" /usr/share/keycloak-image/BUILD-METADATA.txt)
grep -Fx 'UbuntuBase=ubuntu:24.04@sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852' <<<"$metadata"
grep -Fx 'TemurinArtifact=org.eclipse.temurin:jre:21.0.13+9' <<<"$metadata"
grep -Fx 'TemurinSignerFingerprint=3B04D753C9050D9A5D343F39843C48A565F8F04B' <<<"$metadata"

docker build --pull --platform "$platform" \
  --file infra/keycloak/Dockerfile.scan-coverage-control \
  --tag "$control_image" .
control_built=true

control_os=$(docker run --rm --entrypoint /bin/cat "$control_image" /etc/os-release)
grep -qx 'ID=ubuntu' <<<"$control_os"
grep -qx 'VERSION_ID="24.04"' <<<"$control_os"
grep -qx 'VERSION_CODENAME=noble' <<<"$control_os"
control_package=$(docker run --rm --entrypoint /usr/bin/dpkg-query "$control_image" \
  -W '-f=${binary:Package}=${Version}' libbrotli1)
grep -Eq '^libbrotli1(:amd64)?=1\.1\.0-2build2$' <<<"$control_package"

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
  --output /reports/ubuntu-noble-brotli-control.json \
  "$control_image"

jq -e '
  any(.Results[]; .Type == "ubuntu" and .Class == "os-pkgs" and (.Target | test("ubuntu 24\\.04"))) and
  any(.Results[]; .Type == "ubuntu" and any(.Vulnerabilities[]?;
    .VulnerabilityID == "CVE-2026-111497" and
    .PkgName == "libbrotli1" and
    .InstalledVersion == "1.1.0-2build2" and
    (.FixedVersion | contains("1.1.0-2ubuntu0.1")) and
    .Severity == "HIGH"))
' "$control_report" >/dev/null
echo "Coverage proven: Trivy 0.75.0 maps Ubuntu 24.04/Noble OS packages to Ubuntu advisories; the isolated vulnerable libbrotli1 control reported CVE-2026-111497 at HIGH with Noble fix 1.1.0-2ubuntu0.1."
echo "Control report=$control_report"
