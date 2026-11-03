#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
runner="$repo_root/scripts/load/run-baseline.sh"
node_bin="${NODE_BIN:-$(command -v node || true)}"
[[ -x "$node_bin" ]] || { printf 'Defina NODE_BIN para um Node.js executável.\n' >&2; exit 2; }

test_root="$(mktemp -d /tmp/agd054-cleanup-test.XXXXXX)"
trap 'rm -rf -- "$test_root"' EXIT
base_id="$(printf '%s' "$test_root" | sha256sum | cut -c1-24)"
shim_dir="$test_root/bin"
mkdir -p "$shim_dir"

cat > "$shim_dir/od" <<'EOF'
#!/usr/bin/env bash
printf ' %s\n' "${FAKE_OD_HEX:?}" | sed -E 's/([[:xdigit:]]{2})/\1 /g'
EOF

cat > "$shim_dir/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_DOCKER_LOG"
args="$*"
project="agd054-load-${FAKE_OD_HEX}"
image="agd054-load-app:${FAKE_OD_HEX}"

state_add() { printf '%s\n' "$1" >> "$FAKE_DOCKER_STATE"; }
state_remove() { grep -Fxv "$1" "$FAKE_DOCKER_STATE" > "$FAKE_DOCKER_STATE.next" || true; mv "$FAKE_DOCKER_STATE.next" "$FAKE_DOCKER_STATE"; }

if [[ "$FAKE_DOCKER_MODE" == collision-project ]]; then
  case "$args" in
    'ps -aq '*) printf 'stopped-container\n'; exit 0 ;;
    'volume ls -q '*) printf 'preexisting-volume\n'; exit 0 ;;
    'network ls -q '*) printf 'preexisting-network\n'; exit 0 ;;
    'image ls '*) printf '%s\n' "$image"; exit 0 ;;
  esac
fi
if [[ "$FAKE_DOCKER_MODE" == collision-tag ]]; then
  case "$args" in
    'ps -aq '*|'volume ls -q '*|'network ls -q '*) exit 0 ;;
    'image ls '*) printf '%s\n' "$image"; exit 0 ;;
  esac
fi
if [[ "$FAKE_DOCKER_MODE" == partial ]]; then
  case "$args" in
    'ps -aq '*"label=com.docker.compose.project"*|'volume ls -q '*"label=com.docker.compose.project"*|'network ls -q '*"label=com.docker.compose.project"*) exit 0 ;;
    'image ls '*) grep -Fxq 'owned-image' "$FAKE_DOCKER_STATE" && printf '%s\n' "$image" || true; exit 0 ;;
    'ps -aq '*"label=com.kronos.agd054.run=${FAKE_OD_HEX}"*)
      grep -Fxq 'owned-container' "$FAKE_DOCKER_STATE" && printf 'owned-container\n' || true; exit 0 ;;
    'volume ls -q '*"label=com.kronos.agd054.run=${FAKE_OD_HEX}"*)
      grep -Fxq 'owned-volume' "$FAKE_DOCKER_STATE" && printf 'owned-volume\n' || true; exit 0 ;;
    'network ls -q '*"label=com.kronos.agd054.run=${FAKE_OD_HEX}"*)
      grep -Fxq 'owned-network' "$FAKE_DOCKER_STATE" && printf 'owned-network\n' || true; exit 0 ;;
  esac
fi

case "$args" in
  *'compose '*" config --quiet") exit 0 ;;
  *'compose '*" build app") state_add 'owned-image'; exit 0 ;;
  *'compose '*" up --detach")
    state_add 'owned-container'; state_add 'owned-volume'; state_add 'owned-network'
    state_add 'sentinel-stopped-container'; state_add 'sentinel-volume'; state_add 'sentinel-network'; state_add 'sentinel-image'
    exit 23
    ;;
  'ps -aq '*"label=com.kronos.agd054.run=${FAKE_OD_HEX}"*)
    grep -Fxq 'owned-container' "$FAKE_DOCKER_STATE" && printf 'owned-container\n' || true
    ;;
  'volume ls -q '*"label=com.kronos.agd054.run=${FAKE_OD_HEX}"*)
    grep -Fxq 'owned-volume' "$FAKE_DOCKER_STATE" && printf 'owned-volume\n' || true
    ;;
  'network ls -q '*"label=com.kronos.agd054.run=${FAKE_OD_HEX}"*)
    grep -Fxq 'owned-network' "$FAKE_DOCKER_STATE" && printf 'owned-network\n' || true
    ;;
  'inspect '*owned-container)
    printf '%s|%s\n' "$FAKE_OD_HEX" "$project"
    ;;
  'volume inspect '*owned-volume|'network inspect '*owned-network)
    printf '%s|%s\n' "$FAKE_OD_HEX" "$project"
    ;;
  'image inspect '*)
    if grep -Fxq 'owned-image' "$FAKE_DOCKER_STATE"; then printf 'sha256:owned|%s\n' "$FAKE_OD_HEX"; else exit 1; fi
    ;;
  'rm -f owned-container') state_remove 'owned-container' ;;
  'volume rm owned-volume') state_remove 'owned-volume' ;;
  'network rm owned-network') state_remove 'owned-network' ;;
  "image rm ${image}") state_remove 'owned-image' ;;
  *'compose '*" down "*) printf 'unexpected-compose-down\n' >> "$FAKE_DOCKER_STATE" ;;
  *) printf 'fake Docker recebeu comando não previsto: %s\n' "$args" >&2; exit 90 ;;
esac
EOF
chmod +x "$shim_dir/od" "$shim_dir/docker"

assert_no_destructive_call() {
  local log_file="$1"
  if grep -E '^(rm -f|volume rm|network rm|image rm|compose .* down)' "$log_file" >/dev/null; then
    printf 'Falha: recusa por colisão tentou remover recurso preexistente.\n' >&2
    return 1
  fi
}

run_collision_case() {
  local mode="$1" run_id="$2" state_file log_file status
  state_file="$test_root/$mode.state"
  log_file="$test_root/$mode.log"
  printf 'sentinel-stopped-container\nsentinel-volume\nsentinel-network\nsentinel-image\n' > "$state_file"
  cp "$state_file" "$test_root/$mode.expected"
  set +e
  FAKE_DOCKER_MODE="$mode" FAKE_DOCKER_LOG="$log_file" FAKE_DOCKER_STATE="$state_file" FAKE_OD_HEX="$run_id" \
    PATH="$shim_dir:$PATH" NODE_BIN="$node_bin" "$runner" > "$test_root/$mode.output" 2>&1
  status=$?
  set -e
  [[ "$status" == 2 ]] || { cat "$test_root/$mode.output" >&2; printf 'Esperado exit 2 em %s; recebido %s.\n' "$mode" "$status" >&2; return 1; }
  assert_no_destructive_call "$log_file"
  cmp -s "$test_root/$mode.expected" "$state_file" || { printf 'Estado sentinela foi alterado em %s.\n' "$mode" >&2; return 1; }
  [[ ! -e "/tmp/agd054-load-${run_id}.lock" ]] || { printf 'Lock do teste não foi liberado em %s.\n' "$mode" >&2; return 1; }
  printf 'PASS simulado: %s preservou container parado, volume, rede e imagem.\n' "$mode"
}

run_collision_case collision-project "${base_id:0:24}"
second_id="$(printf '%s' "${base_id}tag" | sha256sum | cut -c1-24)"
run_collision_case collision-tag "$second_id"

partial_id="$(printf '%s' "${base_id}partial" | sha256sum | cut -c1-24)"
partial_state="$test_root/partial.state"
partial_log="$test_root/partial.log"
: > "$partial_state"
set +e
FAKE_DOCKER_MODE=partial FAKE_DOCKER_LOG="$partial_log" FAKE_DOCKER_STATE="$partial_state" FAKE_OD_HEX="$partial_id" \
  PATH="$shim_dir:$PATH" NODE_BIN="$node_bin" "$runner" > "$test_root/partial.output" 2>&1
partial_status=$?
set -e
[[ "$partial_status" == 23 ]] || { cat "$test_root/partial.output" >&2; printf 'Falha parcial deveria retornar 23; recebido %s.\n' "$partial_status" >&2; exit 1; }
printf 'sentinel-stopped-container\nsentinel-volume\nsentinel-network\nsentinel-image\n' | sort > "$test_root/expected.state"
sort "$partial_state" > "$test_root/actual.state"
cmp -s "$test_root/expected.state" "$test_root/actual.state" || { cat "$test_root/actual.state" >&2; printf 'Cleanup parcial não preservou exatamente as sentinelas.\n' >&2; exit 1; }
for expected_command in 'rm -f owned-container' 'volume rm owned-volume' 'network rm owned-network' "image rm agd054-load-app:${partial_id}"; do
  grep -Fxq "$expected_command" "$partial_log" || { printf 'Cleanup não removeu o recurso próprio esperado: %s\n' "$expected_command" >&2; exit 1; }
done
if grep -E '^(rm -f|volume rm|network rm|image rm)' "$partial_log" | grep -Ev 'owned-container|owned-volume|owned-network|agd054-load-app:' >/dev/null; then
  printf 'Cleanup tentou remover recurso fora da execução parcial.\n' >&2
  exit 1
fi
[[ ! -e "/tmp/agd054-load-${partial_id}.lock" ]] || { printf 'Lock do cenário parcial não foi liberado.\n' >&2; exit 1; }
printf 'PASS simulado: falha parcial removeu apenas container, volume, rede e imagem rotulados pela execução.\n'
