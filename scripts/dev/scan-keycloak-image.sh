#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$repo_root"
image_ref=${1:-kronos-keycloak:26.8.1-custom}
temurin_dir=infra/temurin/21.0.13+9
# shellcheck source=../../infra/temurin/21.0.13+9/release.env
source "${temurin_dir}/release.env"
temurin_sha256=$(awk 'NR == 1 { print $1 }' "${temurin_dir}/${TEMURIN_ARCHIVE}.sha256.txt")
cache_dir=${TRIVY_CACHE_DIR:-/tmp/kronos-trivy-keycloak-cache}
report_dir=${TRIVY_REPORT_DIR:-/tmp/kronos-trivy-keycloak-report}
report_name=$(printf '%s' "$image_ref" | tr -c '[:alnum:]_.-' '_')
report_path="$report_dir/${report_name}.json"
mkdir -p "$cache_dir" "$report_dir"

os_release=$(docker run --rm --entrypoint /bin/cat "$image_ref" /etc/os-release)
grep -qx 'ID=ubuntu' <<<"$os_release"
grep -qx 'VERSION_CODENAME=noble' <<<"$os_release"
grep -qx 'VERSION_ID="24.04"' <<<"$os_release"

metadata=$(docker run --rm --entrypoint /bin/cat "$image_ref" /usr/share/keycloak-image/BUILD-METADATA.txt)
grep -Fx 'UbuntuBase=ubuntu:24.04@sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852' <<<"$metadata"
grep -Fx 'KeycloakSource=quay.io/keycloak/keycloak@sha256:a78d6b79572168cb25886b80a8b5b8d4ed4390743e3eecb810538683e209dd6b' <<<"$metadata"
grep -Fx "TemurinArtifact=org.eclipse.temurin:jre:${TEMURIN_VERSION}" <<<"$metadata"
grep -Fx "TemurinSHA256=${temurin_sha256}" <<<"$metadata"
grep -Fx "TemurinSignerFingerprint=${TEMURIN_SIGNER_FINGERPRINT}" <<<"$metadata"
grep -Fx 'GuavaArtifact=com.google.guava:guava:33.7.2-jre' <<<"$metadata"
grep -Fx 'GuavaSHA256=b530942257fb935f8b2cfaa5f8eb5bd59c555fd8e8d01b8ce98912e077ea606c' <<<"$metadata"
grep -Fx 'ca-certificates=20260816ubuntu0.24.04.1' <<<"$metadata"
grep -Eq '^libc6(:amd64)?=[^[:space:]]+$' <<<"$metadata"
package_count=$(docker run --rm --entrypoint /bin/bash "$image_ref" -lc \
  'dpkg-query -W | wc -l | tr -d " "')
test "$package_count" -ge 90

java_version=$(docker run --rm --entrypoint java "$image_ref" -version 2>&1)
grep -Fq "Temurin-${TEMURIN_VERSION} (build ${TEMURIN_VERSION}-LTS)" <<<"$java_version"
test "$(docker run --rm --entrypoint /bin/bash "$image_ref" -lc 'id -u')" = 1000

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

jq -e 'any(.Results[]; .Type == "ubuntu" and .Class == "os-pkgs" and (.Target | test("ubuntu 24\\.04")))' "$report_path" >/dev/null
echo "OS advisory target verified: Ubuntu 24.04/Noble; $package_count dpkg packages, Keycloak digest, Temurin release/checksum/fingerprint and Guava checksum verified."
jq -r '[.Results[].Vulnerabilities[]?] | group_by(.Severity) | map("\(.[0].Severity)=\(length)") | if length == 0 then "Vulnerabilities=0" else join(" ") end' "$report_path"
echo "TrivyReport=$report_path"
exit "$scan_status"
