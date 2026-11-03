#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
compose_file="$repo_root/infra/load/compose.yaml"
run_id="$(od -An -N12 -tx1 /dev/urandom | tr -d ' \n')"
if [[ ! "$run_id" =~ ^[a-f0-9]{24}$ ]]; then
  printf 'Não foi possível gerar o identificador aleatório da execução; nada foi iniciado.\n' >&2
  exit 2
fi
compose_project="agd054-load-${run_id}"
app_image="agd054-load-app:${run_id}"
run_label="com.kronos.agd054.run"
project_label="com.docker.compose.project"
lock_dir="/tmp/${compose_project}.lock"
run_dir=""
cleanup_armed=0
lock_owned=0

load_tenants="${LOAD_TENANTS:-4}"
availability_requests="${LOAD_AVAILABILITY_REQUESTS:-100}"
create_requests="${LOAD_CREATE_REQUESTS:-24}"
conflict_rounds="${LOAD_CONFLICT_ROUNDS:-4}"
concurrency="${LOAD_CONCURRENCY:-8}"
node_bin="${NODE_BIN:-node}"

validate_integer() {
  local value="$1" minimum="$2" maximum="$3" name="$4"
  if [[ ! "$value" =~ ^[0-9]+$ ]] || (( value < minimum || value > maximum )); then
    printf 'Parâmetro inválido: %s deve estar entre %s e %s.\n' "$name" "$minimum" "$maximum" >&2
    exit 2
  fi
}

validate_integer "$load_tenants" 2 20 LOAD_TENANTS
validate_integer "$availability_requests" 1 100000 LOAD_AVAILABILITY_REQUESTS
validate_integer "$create_requests" "$load_tenants" 10000 LOAD_CREATE_REQUESTS
validate_integer "$conflict_rounds" 1 1000 LOAD_CONFLICT_ROUNDS
validate_integer "$concurrency" 1 64 LOAD_CONCURRENCY
if [[ "$node_bin" == */* ]]; then
  [[ -x "$node_bin" ]] || { printf 'NODE_BIN não é executável.\n' >&2; exit 2; }
else
  node_bin="$(command -v "$node_bin" || true)"
  [[ -n "$node_bin" ]] || { printf 'Node.js não encontrado; defina NODE_BIN para Node.js 24+.\n' >&2; exit 2; }
fi

if ! mkdir -- "$lock_dir" 2>/dev/null; then
  printf 'Colisão de lock para o projeto descartável; nada foi alterado.\n' >&2
  exit 2
fi
lock_owned=1

docker_project_is_empty() {
  local containers volumes networks images
  if ! containers="$(docker ps -aq --filter "label=${project_label}=${compose_project}")" || \
     ! volumes="$(docker volume ls -q --filter "label=${project_label}=${compose_project}")" || \
     ! networks="$(docker network ls -q --filter "label=${project_label}=${compose_project}")" || \
     ! images="$(docker image ls --filter "reference=${app_image}" --format '{{.Repository}}:{{.Tag}}')"; then
    printf 'Não foi possível verificar colisões no Docker; nada foi iniciado.\n' >&2
    return 1
  fi
  if [[ -n "$containers" || -n "$volumes" || -n "$networks" || "$images" == "$app_image" ]]; then
    printf 'Colisão de projeto/tag detectada; nada foi alterado.\n' >&2
    return 2
  fi
  return 0
}

if docker_project_is_empty; then
  :
else
  preflight_status=$?
  rmdir -- "$lock_dir"
  lock_owned=0
  exit "$preflight_status"
fi

compose() {
  docker compose -p "$compose_project" --env-file "$env_file" -f "$compose_file" --project-directory "$repo_root" "$@"
}

cleanup() {
  local ids resource_id labels image_info image_run_label image_refs cleanup_failed=0
  if [[ "$cleanup_armed" == 1 ]]; then
    if ! ids="$(docker ps -aq --filter "label=${run_label}=${run_id}")"; then
      printf 'Cleanup: não foi possível listar containers da execução.\n' >&2
      cleanup_failed=1
    else
      while IFS= read -r resource_id; do
        [[ -n "$resource_id" ]] || continue
        labels="$(docker inspect --format '{{ index .Config.Labels "com.kronos.agd054.run" }}|{{ index .Config.Labels "com.docker.compose.project" }}' "$resource_id" 2>/dev/null || true)"
        if [[ "$labels" != "${run_id}|${compose_project}" ]]; then
          printf 'Cleanup: container sem os labels de posse esperados; preservado.\n' >&2
          cleanup_failed=1
          continue
        fi
        docker rm -f "$resource_id" >/dev/null || cleanup_failed=1
      done <<< "$ids"
    fi

    if ! ids="$(docker volume ls -q --filter "label=${run_label}=${run_id}")"; then
      printf 'Cleanup: não foi possível listar volumes da execução.\n' >&2
      cleanup_failed=1
    else
      while IFS= read -r resource_id; do
        [[ -n "$resource_id" ]] || continue
        labels="$(docker volume inspect --format '{{ index .Labels "com.kronos.agd054.run" }}|{{ index .Labels "com.docker.compose.project" }}' "$resource_id" 2>/dev/null || true)"
        if [[ "$labels" != "${run_id}|${compose_project}" ]]; then
          printf 'Cleanup: volume sem os labels de posse esperados; preservado.\n' >&2
          cleanup_failed=1
          continue
        fi
        docker volume rm "$resource_id" >/dev/null || cleanup_failed=1
      done <<< "$ids"
    fi

    if ! ids="$(docker network ls -q --filter "label=${run_label}=${run_id}")"; then
      printf 'Cleanup: não foi possível listar redes da execução.\n' >&2
      cleanup_failed=1
    else
      while IFS= read -r resource_id; do
        [[ -n "$resource_id" ]] || continue
        labels="$(docker network inspect --format '{{ index .Labels "com.kronos.agd054.run" }}|{{ index .Labels "com.docker.compose.project" }}' "$resource_id" 2>/dev/null || true)"
        if [[ "$labels" != "${run_id}|${compose_project}" ]]; then
          printf 'Cleanup: rede sem os labels de posse esperados; preservada.\n' >&2
          cleanup_failed=1
          continue
        fi
        docker network rm "$resource_id" >/dev/null || cleanup_failed=1
      done <<< "$ids"
    fi

    if ! image_refs="$(docker image ls --filter "reference=${app_image}" --format '{{.Repository}}:{{.Tag}}')"; then
      printf 'Cleanup: não foi possível verificar a tag da imagem; ela foi preservada.\n' >&2
      cleanup_failed=1
    elif [[ "$image_refs" == "$app_image" ]]; then
      if image_info="$(docker image inspect --format '{{.Id}}|{{ index .Config.Labels "com.kronos.agd054.run" }}' "$app_image" 2>/dev/null)"; then
        image_run_label="${image_info##*|}"
        if [[ "$image_run_label" == "$run_id" ]]; then
          docker image rm "$app_image" >/dev/null || cleanup_failed=1
        else
          printf 'Cleanup: tag da aplicação não pertence a esta execução; preservada.\n' >&2
          cleanup_failed=1
        fi
      else
        printf 'Cleanup: não foi possível confirmar a posse da tag da imagem; ela foi preservada.\n' >&2
        cleanup_failed=1
      fi
    elif [[ -n "$image_refs" ]]; then
      printf 'Cleanup: a consulta de tag retornou uma referência inesperada; imagem preservada.\n' >&2
      cleanup_failed=1
    fi
  fi
  if [[ -n "$run_dir" ]] && ! rm -rf -- "$run_dir"; then
    printf 'Cleanup: não foi possível remover o diretório temporário desta execução.\n' >&2
    cleanup_failed=1
  fi
  if [[ "$lock_owned" == 1 ]]; then
    if rmdir -- "$lock_dir" 2>/dev/null; then
      lock_owned=0
    else
      printf 'Cleanup: não foi possível liberar o lock desta execução.\n' >&2
      cleanup_failed=1
    fi
  fi
  if (( cleanup_failed != 0 )); then
    printf 'Cleanup parcial: alguns recursos foram preservados por falha de verificação/remoção.\n' >&2
    return 1
  fi
  return 0
}

cleanup_armed=1
finish() {
  local original_status=$? cleanup_status=0
  trap - EXIT
  cleanup || cleanup_status=$?
  if (( cleanup_status != 0 )); then
    exit 1
  fi
  exit "$original_status"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'status=$?; printf "baseline_command_failed line=%s status=%s\n" "$LINENO" "$status" >&2' ERR

run_dir="$(mktemp -d "/tmp/${compose_project}.XXXXXX")"
env_file="$run_dir/compose.env"
postgres_password="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
app_db_password="$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')"
umask 077
cat > "$env_file" <<EOF
LOAD_POSTGRES_PASSWORD=$postgres_password
LOAD_APP_DB_PASSWORD=$app_db_password
LOAD_APP_IMAGE=$app_image
LOAD_RUN_ID=$run_id
EOF
chmod 600 "$env_file"

printf 'Projeto descartável: %s\n' "$compose_project"
printf 'Parâmetros: tenants=%s availability_requests=%s create_requests=%s conflict_rounds=%s concurrency=%s\n' \
  "$load_tenants" "$availability_requests" "$create_requests" "$conflict_rounds" "$concurrency"
compose config --quiet
printf 'Compose validado; construindo aplicação.\n'
compose build app
printf 'Imagem da aplicação construída.\n'
compose up --detach

app_container="$(compose ps -q app)"
postgres_container="$(compose ps -q postgres)"
if [[ -z "$app_container" || -z "$postgres_container" ]]; then
  printf 'Compose não criou os dois containers esperados.\n' >&2
  exit 1
fi
for container_id in "$app_container" "$postgres_container"; do
  project_label="$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' "$container_id")"
  if [[ "$project_label" != "$compose_project" ]]; then
    printf 'Container criado com label de projeto divergente; recusando continuar.\n' >&2
    exit 1
  fi
done

app_status=starting
for _ in $(seq 1 180); do
  app_status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$app_container")"
  [[ "$app_status" == healthy ]] && break
  [[ "$app_status" == unhealthy || "$app_status" == exited || "$app_status" == dead ]] && {
    printf 'Aplicação não ficou saudável; estado=%s.\n' "$app_status" >&2
    exit 1
  }
  sleep 2
done
if [[ "$app_status" != healthy ]]; then
  printf 'Timeout aguardando readiness da aplicação; estado=%s.\n' "$app_status" >&2
  exit 1
fi
printf 'Readiness da aplicação aprovada.\n'

compose exec -T postgres psql -v ON_ERROR_STOP=1 -v "run_id=$run_id" -v "tenant_count=$load_tenants" \
  -U agd054_owner -d agd054_load -f - < "$repo_root/scripts/load/seed-fixtures.sql"

app_port="$(compose port app 8080 | sed -E 's/.*:([0-9]+)$/\1/')"
if [[ ! "$app_port" =~ ^[0-9]+$ ]]; then
  printf 'Não foi possível identificar a porta loopback da aplicação.\n' >&2
  exit 1
fi
printf 'Fixtures aplicadas; porta loopback atribuída.\n'

postgres_image_id="$(docker inspect --format '{{.Image}}' "$postgres_container")"
app_image_id="$(docker inspect --format '{{.Image}}' "$app_container")"
postgres_digest="$(docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$postgres_image_id" | sed -n '1p' || true)"
postgres_version="$(compose exec -T postgres psql -U agd054_owner -d agd054_load -tA -c 'SHOW server_version')"
schema_version="$(compose exec -T postgres psql -U agd054_owner -d agd054_load -tA -c 'SELECT version FROM agendamento.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1')"
java_output="$(compose exec -T app java -version 2>&1)"
java_version="$(printf '%s\n' "$java_output" | sed -n '1p' | tr -d '\r')"
docker_engine_version="$(docker version --format '{{.Server.Version}}')"
docker_compose_version="$(docker compose version --short)"
printf 'Versões e identidades dos containers capturadas.\n'
host_cpu_count="$(getconf _NPROCESSORS_ONLN)"
host_memory_bytes="$(awk '/MemTotal:/ {print $2 * 1024}' /proc/meminfo)"
host_cpu_model="$(awk -F: '/model name/ {sub(/^[ \t]+/, "", $2); print $2; exit}' /proc/cpuinfo)"
host_kernel="$(uname -r)"

result_dir="$repo_root/scripts/load/results"
mkdir -p "$result_dir"
result_file="$result_dir/baseline-$run_id.json"
LOAD_RUN_ID="$run_id" \
LOAD_TENANTS="$load_tenants" \
LOAD_AVAILABILITY_REQUESTS="$availability_requests" \
LOAD_CREATE_REQUESTS="$create_requests" \
LOAD_CONFLICT_ROUNDS="$conflict_rounds" \
LOAD_CONCURRENCY="$concurrency" \
LOAD_BASE_URL="http://127.0.0.1:$app_port" \
LOAD_COMPOSE_PROJECT="$compose_project" \
LOAD_ENV_FILE="$env_file" \
LOAD_COMPOSE_FILE="$compose_file" \
LOAD_REPO_ROOT="$repo_root" \
LOAD_HOST_CPU_COUNT="$host_cpu_count" \
LOAD_HOST_MEMORY_BYTES="$host_memory_bytes" \
LOAD_HOST_CPU_MODEL="$host_cpu_model" \
LOAD_HOST_KERNEL="$host_kernel" \
LOAD_DOCKER_ENGINE_VERSION="$docker_engine_version" \
LOAD_DOCKER_COMPOSE_VERSION="$docker_compose_version" \
LOAD_POSTGRES_IMAGE="${postgres_digest:-postgres:16-alpine image-id=$postgres_image_id}" \
LOAD_APP_IMAGE_ID="$app_image_id" \
LOAD_POSTGRES_VERSION="$postgres_version" \
LOAD_SCHEMA_VERSION="$schema_version" \
LOAD_APPLICATION_COMMIT="$(git -C "$repo_root" rev-parse HEAD)" \
LOAD_JAVA_VERSION="$java_version" \
  "$node_bin" "$repo_root/scripts/load/run-baseline.mjs" | tee "$result_file"

printf 'Resultado salvo em %s\n' "${result_file#"$repo_root"/}"
printf 'Container IDs do projeto exclusivo: app=%s postgres=%s\n' "$app_container" "$postgres_container"
