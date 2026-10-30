#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat >&2 <<'EOF'
Usage: verify-runtime-hardening.sh CONTAINER EXPECTED_UID:EXPECTED_GID

Inspects one existing Docker container. It does not start, stop, or remove it.
EXPECTED_UID:EXPECTED_GID must be numeric, for example 1000:0.
EOF
}

failures=0
fail() {
  printf 'FAIL: %s\n' "$1" >&2
  failures=$((failures + 1))
}

if [[ $# -ne 2 ]]; then
  usage
  exit 2
fi
if [[ ! $2 =~ ^[0-9]+:[0-9]+$ ]]; then
  usage
  exit 2
fi
if [[ ! $1 =~ ^[[:alnum:]][[:alnum:]_.-]*$ ]]; then
  printf 'FAIL: informe um nome ou ID de container sem opções ou caracteres de controle.\n' >&2
  usage
  exit 2
fi

container=$1
expected_uid=${2%%:*}
expected_gid=${2##*:}

for dependency in docker jq; do
  if ! command -v "$dependency" >/dev/null 2>&1; then
    printf 'FAIL: dependência ausente: %s\n' "$dependency" >&2
    exit 2
  fi
done

if ! initial_inspect=$(docker inspect --type container "$container" 2>/dev/null); then
  printf 'FAIL: container não encontrado ou não inspecionável: %s\n' "$container" >&2
  exit 1
fi

if ! jq -e 'length == 1 and (.[0] | has("Id"))' \
  <<<"$initial_inspect" >/dev/null 2>&1; then
  printf 'FAIL: resposta de docker inspect inválida para o container indicado.\n' >&2
  exit 1
fi

container_id=$(jq -r '.[0].Id' <<<"$initial_inspect")
if [[ ! $container_id =~ ^[[:xdigit:]]{64}$ ]]; then
  printf 'FAIL: docker inspect não retornou um ID completo de container.\n' >&2
  exit 1
fi

# Pin all subsequent observations to the immutable ID. If a name is reused,
# this reinspection and every docker exec still target the original instance.
if ! inspect_json=$(docker inspect --type container "$container_id" 2>/dev/null); then
  printf 'FAIL: a instância resolvida (%s) desapareceu ou não pôde ser reinspecionada; abortando sem fallback pelo nome.\n' \
    "$container_id" >&2
  exit 1
fi
if ! jq -e --arg id "$container_id" \
  'length == 1 and (.[0].Id == $id) and (.[0] | has("HostConfig") and has("Config") and has("State"))' \
  <<<"$inspect_json" >/dev/null 2>&1; then
  printf 'FAIL: a instância resolvida não corresponde ao ID reinspecionado; abortando.\n' >&2
  exit 1
fi

container_short_id=${container_id:0:12}
running=$(jq -r '.[0].State.Running // false' <<<"$inspect_json")
configured_user=$(jq -r '.[0].Config.User // ""' <<<"$inspect_json")
privileged=$(jq -r '.[0].HostConfig.Privileged // false' <<<"$inspect_json")
cap_add=$(jq -c '.[0].HostConfig.CapAdd // []' <<<"$inspect_json")
security_opts=$(jq -c '.[0].HostConfig.SecurityOpt // []' <<<"$inspect_json")

printf 'Container ID: %s\n' "$container_short_id"
printf 'Configured user: %s\n' "${configured_user:-<default> (root)}"
printf 'Expected process user: %s:%s\n' "$expected_uid" "$expected_gid"
printf 'Docker privileged: %s\n' "$privileged"
printf 'Docker CapAdd: %s\n' "$cap_add"
if jq -e 'any(.[]?; ascii_downcase | startswith("no-new-privileges:true"))' \
  <<<"$security_opts" >/dev/null 2>&1; then
  printf 'Docker no-new-privileges option: configured\n'
else
  printf 'Docker no-new-privileges option: not explicitly configured\n'
fi

if [[ $running != true ]]; then
  printf 'FAIL: a instância resolvida (%s) não está em execução; não foi iniciada pelo verificador.\n' \
    "$container_id" >&2
  exit 1
fi

if [[ $privileged == true ]]; then
  fail 'HostConfig.Privileged está habilitado.'
fi

if ! jq -e '((.[0].HostConfig.CapAdd // []) | length) == 0' \
  <<<"$inspect_json" >/dev/null 2>&1; then
  fail "HostConfig.CapAdd contém capabilities adicionadas: $cap_add"
fi

# Resolve the container's configured user by running only id(1) as the
# container's default exec identity. PID 1 is checked separately below.
if ! exec_identity=$(docker exec "$container_id" /bin/sh -c 'printf "%s:%s\n" "$(id -u)" "$(id -g)"' 2>/dev/null); then
  printf 'FAIL: inspeção da instância resolvida (%s) falhou ou ela parou/desapareceu; abortando sem fallback pelo nome.\n' \
    "$container_id" >&2
  exit 1
elif [[ ! $exec_identity =~ ^[0-9]+:[0-9]+$ ]]; then
  fail 'identidade resolvida do container está em formato inesperado.'
else
  printf 'Resolved configured process identity: %s\n' "$exec_identity"
  configured_uid=${exec_identity%%:*}
  configured_gid=${exec_identity##*:}
  if [[ $configured_uid != "$expected_uid" || $configured_gid != "$expected_gid" ]]; then
    fail "identidade configurada do container é $exec_identity; esperado $expected_uid:$expected_gid."
  fi
fi

# This reads only the main process status, not its command line or environment.
if ! proc_status=$(docker exec "$container_id" /bin/sh -c 'cat /proc/1/status' 2>/dev/null); then
  printf 'FAIL: não foi possível ler /proc/1/status da instância resolvida (%s); abortando sem fallback pelo nome.\n' \
    "$container_id" >&2
  exit 1
fi

read_status_value() {
  local key=$1
  awk -v wanted="${key}:" '$1 == wanted { print $2; exit }' <<<"$proc_status"
}

uid_tuple=$(awk '$1 == "Uid:" { print $2, $3, $4, $5; exit }' <<<"$proc_status")
gid_tuple=$(awk '$1 == "Gid:" { print $2, $3, $4, $5; exit }' <<<"$proc_status")

if [[ -z $uid_tuple || -z $gid_tuple ]]; then
  fail 'Uid/Gid ausentes em /proc/1/status; usuário efetivo não comprovado.'
else
  printf 'PID 1 Uid (real/effective/saved/fs): %s\n' "$uid_tuple"
  printf 'PID 1 Gid (real/effective/saved/fs): %s\n' "$gid_tuple"
  for uid in $uid_tuple; do
    if [[ $uid != "$expected_uid" ]]; then
      fail "PID 1 possui UID $uid em um dos campos; esperado $expected_uid em todos."
      break
    fi
  done
  for gid in $gid_tuple; do
    if [[ $gid != "$expected_gid" ]]; then
      fail "PID 1 possui GID $gid em um dos campos; esperado $expected_gid em todos."
      break
    fi
  done
fi

no_new_privs=$(read_status_value NoNewPrivs)
if [[ -z $no_new_privs ]]; then
  fail 'NoNewPrivs ausente em /proc/1/status.'
elif [[ $no_new_privs != 1 ]]; then
  fail "NoNewPrivs=$no_new_privs; esperado 1."
else
  printf 'PID 1 NoNewPrivs: 1\n'
fi

capability_fields=(CapInh CapPrm CapEff CapBnd CapAmb)
for field in "${capability_fields[@]}"; do
  value=$(read_status_value "$field")
  if [[ ! $value =~ ^[[:xdigit:]]+$ ]]; then
    fail "$field ausente ou inválido em /proc/1/status."
    continue
  fi
  normalized=${value,,}
  while [[ ${#normalized} -gt 1 && ${normalized:0:1} == 0 ]]; do
    normalized=${normalized:1}
  done
  printf 'PID 1 %s: %s\n' "$field" "$value"
  if [[ $normalized != 0 ]]; then
    fail "$field não está zerado (valor hexadecimal $value)."
  fi
done

# Inspect common executable directories without printing container environment,
# process command lines, or unrelated filesystem entries.
if ! mount_modes=$(docker exec "$container_id" /bin/sh -c '
  for directory in /bin /sbin /usr/bin /usr/sbin /usr/local/bin /usr/local/sbin; do
    for name in mount umount; do
      path="$directory/$name"
      if [ -e "$path" ]; then
        mode=$(stat -Lc "%a" "$path") || exit 1
        printf "%s\t%s\n" "$path" "$mode"
      fi
    done
  done
' 2>/dev/null); then
  printf 'FAIL: não foi possível inspecionar os modos de mount/umount da instância resolvida (%s); abortando sem fallback pelo nome.\n' \
    "$container_id" >&2
  exit 1
fi

printf 'SUID search scope: /bin /sbin /usr/bin /usr/sbin /usr/local/bin /usr/local/sbin (links followed; no other paths scanned).\n'
if [[ -z $mount_modes ]]; then
  printf 'SUID mount/umount: nenhum executável encontrado nos diretórios listados.\n'
else
  while IFS=$'\t' read -r path mode; do
    [[ -n $path ]] || continue
    printf 'Executável: %s modo=%s\n' "$path" "$mode"
    if [[ ! $mode =~ ^[0-7]+$ ]] || (( (8#$mode & 04000) != 0 )); then
      fail "$path está com SUID ou seu modo não pôde ser interpretado com segurança."
    fi
  done <<<"$mount_modes"
fi

if (( failures > 0 )); then
  printf 'Hardening verification FAILED: %d condição(ões) não satisfeita(s).\n' "$failures" >&2
  exit 1
fi

printf 'Hardening verification PASSED for container %s.\n' "$container_short_id"
