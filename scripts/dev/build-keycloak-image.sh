#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$repo_root"
image_ref=${1:-kronos-keycloak:26.8.1-custom}
platform=${KEYCLOAK_PLATFORM:-linux/amd64}
snapshot=${DEBIAN_SNAPSHOT:-20261028T183000Z}

docker build --pull --platform "$platform" \
  --build-arg "DEBIAN_SNAPSHOT=$snapshot" \
  --file infra/keycloak/Dockerfile \
  --tag "$image_ref" .

printf 'ImageRef=%s\nPlatform=%s\nImageId=' "$image_ref" "$platform"
docker image inspect --format '{{.Id}}' "$image_ref"
docker run --rm --entrypoint java "$image_ref" -version
docker run --rm --entrypoint /usr/bin/dpkg-query "$image_ref" \
  -W '-f=${binary:Package}=${Version}\n' openjdk-21-jre-headless ca-certificates
docker run --rm --entrypoint /bin/cat "$image_ref" /usr/share/keycloak-image/BUILD-METADATA.txt
