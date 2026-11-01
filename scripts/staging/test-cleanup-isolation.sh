#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
project="agd003-sentinel-$$-$(od -An -N3 -tx1 /dev/urandom | tr -d ' \n')"
tmp=$(mktemp -d "/tmp/${project}-XXXXXX")
env_file="$tmp/sentinel.env"
compose_file="$tmp/sentinel.compose.yaml"
container_name="${project}-sentinel-1"
network_name="${project}_sentinel"
volume_name="${project}_sentinel_data"

cleanup_sentinel() {
  docker compose --project-name "$project" --project-directory "$tmp" \
    --file "$compose_file" --env-file "$env_file" down --volumes --remove-orphans >/dev/null 2>&1 || true
  rm -rf -- "$tmp"
}
trap cleanup_sentinel EXIT

cat >"$env_file" <<'EOF'
STAGING_POSTGRES_IMAGE=postgres:16.13-alpine@sha256:4e6e670bb069649261c9c18031f0aded7bb249a5b6664ddec29c013a89310d50
EOF
chmod 0600 "$env_file"
cat >"$compose_file" <<'EOF'
services:
  sentinel:
    image: ${STAGING_POSTGRES_IMAGE:?}
    command: ["sh", "-c", "sleep 900"]
    volumes:
      - sentinel_data:/sentinel
    networks: [sentinel]
networks:
  sentinel: {}
volumes:
  sentinel_data: {}
EOF

docker compose --project-name "$project" --project-directory "$tmp" \
  --file "$compose_file" --env-file "$env_file" up -d
docker compose --project-name "$project" --project-directory "$tmp" \
  --file "$compose_file" --env-file "$env_file" stop sentinel

[[ "$(docker inspect -f '{{.State.Status}}' "$container_name")" == "exited" ]]
[[ "$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$container_name")" == "$project" ]]
docker volume inspect "$volume_name" >/dev/null
docker network inspect "$network_name" >/dev/null
echo "Sentinel prepared: stopped container, named volume and network are owned by a different synthetic project."

runner_log="$tmp/runner.log"
if ! "$repo_root/scripts/staging/run-disposable.sh" >"$runner_log" 2>&1; then
  cat "$runner_log"
  exit 1
fi
cat "$runner_log"
run_project=$(sed -n 's/^Disposable project: \([^;]*\);.*/\1/p' "$runner_log" | head -1)
[[ -n "$run_project" ]]
[[ -z "$(docker ps -aq --filter "label=com.docker.compose.project=$run_project")" ]]
[[ -z "$(docker volume ls -q --filter "label=com.docker.compose.project=$run_project")" ]]
[[ -z "$(docker network ls -q --filter "label=com.docker.compose.project=$run_project")" ]]
if docker image ls --format '{{.Repository}}:{{.Tag}}' | grep -q "^kronos-staging-${run_project}-"; then
  echo "Runner left a project-tagged local image behind." >&2
  exit 1
fi

[[ "$(docker inspect -f '{{.State.Status}}' "$container_name")" == "exited" ]]
docker volume inspect "$volume_name" >/dev/null
docker network inspect "$network_name" >/dev/null
echo "PASS: staging cleanup removed its own labeled resources and images, and left the foreign project's stopped container, volume and network intact."
