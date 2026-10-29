#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
cd "$repo_root"
image_ref=${1:-kronos-keycloak:26.8.1-custom}
platform=${KEYCLOAK_PLATFORM:-linux/amd64}

if [[ "$platform" != linux/amd64 ]]; then
  echo "The pinned Temurin JRE artifact is x64 only; unsupported platform: $platform" >&2
  exit 2
fi

docker build --pull --platform "$platform" \
  --file infra/keycloak/Dockerfile \
  --tag "$image_ref" .

printf 'ImageRef=%s\nPlatform=%s\nImageId=' "$image_ref" "$platform"
docker image inspect --format '{{.Id}}' "$image_ref"
docker run --rm --entrypoint java "$image_ref" -version
docker run --rm --entrypoint /bin/bash "$image_ref" -lc \
  'test "$(id -u)" = 1000 && test "$(id -g)" = 0 && dpkg-query -W ca-certificates libacl1 util-linux libmount1 mount'
docker run --rm --entrypoint /bin/cat "$image_ref" /usr/share/keycloak-image/BUILD-METADATA.txt
