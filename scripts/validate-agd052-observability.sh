#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="$repo_root/compose.observability.yaml"
mode="${1:-validate}"

if [[ "$mode" != "validate" && "$mode" != "smoke" && "$mode" != "isolation-test" ]]; then
  echo "Uso: $0 [validate|smoke|isolation-test]" >&2
  exit 2
fi

command -v docker >/dev/null || { echo "docker CLI não encontrado" >&2; exit 2; }
command -v python3 >/dev/null || { echo "python3 não encontrado" >&2; exit 2; }
command -v openssl >/dev/null || { echo "openssl necessário para credencial descartável" >&2; exit 2; }

suffix="$(date -u +%Y%m%d%H%M%S)-$$-${RANDOM}"
project="kronos-agd052-check-${suffix}"
tmp_dir="$(mktemp -d "${TMPDIR:-/tmp}/agd052-observability.XXXXXX")"
chmod 700 "$tmp_dir"
env_file="$tmp_dir/compose.env"
grafana_password="$(openssl rand -hex 24)"
scrape_token="$(openssl rand -hex 32)"
umask 077
printf '%s\n' "$scrape_token" >"$tmp_dir/scrape-token"
chmod 600 "$tmp_dir/scrape-token"
cat >"$env_file" <<EOF
AGD052_GRAFANA_ADMIN_USER=agd052-local-admin
AGD052_GRAFANA_ADMIN_PASSWORD=$grafana_password
AGD052_PROMETHEUS_PORT=
AGD052_GRAFANA_PORT=
AGD052_GRAFANA_ROOT_URL=http://localhost/
AGD052_SCRAPE_TOKEN_FILE=$tmp_dir/scrape-token
EOF
unset grafana_password scrape_token
set -a
source "$env_file"
set +a

stack_started=0
sentinel_project=""
sentinel_compose=""
cleanup() {
  if [[ "$stack_started" == "1" ]]; then
    docker compose --project-name "$project" --env-file "$env_file" -f "$compose_file" down --volumes --remove-orphans
  fi
  if [[ -n "$sentinel_project" && -f "$sentinel_compose" ]]; then
    docker compose --project-name "$sentinel_project" -f "$sentinel_compose" down --volumes --remove-orphans
  fi
  rm -rf -- "$tmp_dir"
}
trap cleanup EXIT

cd "$repo_root"
compose=(docker compose --project-name "$project" --env-file "$env_file" -f "$compose_file")
"${compose[@]}" config --quiet

prometheus_image="$(sed -n 's/^[[:space:]]*image: //p' "$compose_file" | head -1)"
docker run --rm --entrypoint /bin/promtool \
  -v "$repo_root/ops/observability/prometheus:/etc/prometheus:ro" \
  -v "$AGD052_SCRAPE_TOKEN_FILE:/run/secrets/agd052_observability_scrape_token:ro" \
  "$prometheus_image" check config /etc/prometheus/prometheus.yml
docker run --rm --entrypoint /bin/promtool \
  -v "$repo_root/ops/observability/prometheus:/etc/prometheus:ro" \
  "$prometheus_image" check rules \
  /etc/prometheus/rules/platform.rules.yml \
  /etc/prometheus/rules/contract.rules.yml
docker run --rm --entrypoint /bin/promtool \
  -v "$repo_root/ops/observability/prometheus:/work/prometheus:ro" \
  -w /work/prometheus/tests \
  "$prometheus_image" test rules platform.rules.test.yml contract.rules.test.yml

python3 - "$repo_root/ops/observability/grafana" "$repo_root" <<'PY'
import json
import sys
from pathlib import Path

base = Path(sys.argv[1])
repo = Path(sys.argv[2])
definitions = base / "provisioning/dashboards/definitions"
expected = {"api", "reservas", "workers", "whatsapp", "cobranca", "exportacoes"}
found = {p.stem for p in definitions.glob("*.json")}
if found != expected:
    raise SystemExit(f"Dashboards esperados {sorted(expected)}, encontrados {sorted(found)}")
uids = set()
expressions = []
for path in sorted(definitions.glob("*.json")):
    dashboard = json.loads(path.read_text(encoding="utf-8"))
    if dashboard.get("uid") in uids or not dashboard.get("uid"):
        raise SystemExit(f"UID de dashboard ausente/duplicado em {path}")
    uids.add(dashboard["uid"])
    if not dashboard.get("panels"):
        raise SystemExit(f"Dashboard sem painéis: {path}")
    for panel in dashboard["panels"]:
        expressions.extend(target.get("expr", "") for target in panel.get("targets", []))

rules = (repo / "ops/observability/prometheus/rules/contract.rules.yml").read_text(encoding="utf-8")
if 'source="postgresql"' in rules or 'source="appointments"' in rules:
    raise SystemExit("Regra de source exige projeção não implementada no contrato atual")
if "MetricsSourceNeverSampled" not in rules or "agd_metrics_source_sampled" not in rules:
    raise SystemExit("Regras não diferenciam startup sem primeira amostra")
if any('agd_reconciliation_pending{subsystem="payments"}' in expr for expr in expressions):
    raise SystemExit("Dashboard consulta projeção billing.pending ausente do contrato v1.2")
if any('agd_reconciliation_outcome_unknown{subsystem="exports"}' in expr or 'agd_reconciliation_pending{subsystem="exports"}' in expr for expr in expressions):
    raise SystemExit("Dashboard consulta reconciliação de exports não publicada no contrato v1.2")
for path in (repo / "ops/observability/prometheus/rules").glob("*.yml"):
    for line in path.read_text(encoding="utf-8").splitlines():
        if "runbook_path:" in line:
            runbook = line.split(":", 1)[1].strip()
            if not (repo / runbook).is_file():
                raise SystemExit(f"Runbook referenciado ausente: {runbook}")
print(f"JSON de {len(found)} dashboards válidos; regras sem fontes não implementadas; runbooks presentes")
PY

if [[ "$mode" == "validate" ]]; then
  echo "Validação AGD-052 concluída (validate); nenhuma stack Compose foi iniciada."
  exit 0
fi

if [[ "$mode" == "smoke" ]]; then
  containers="$(docker ps -aq --filter "label=com.docker.compose.project=$project")"
  volumes="$(docker volume ls -q --filter "label=com.docker.compose.project=$project")"
  networks="$(docker network ls -q --filter "label=com.docker.compose.project=$project")"
  if [[ -n "$containers$volumes$networks" ]]; then
    echo "Projeto aleatório $project já possui recursos; recusando cleanup." >&2
    exit 3
  fi
  stack_started=1
  "${compose[@]}" up -d
  prometheus_port="$("${compose[@]}" port prometheus 9090 | sed -E 's/^.*:([0-9]+)$/\1/')"
  grafana_port="$("${compose[@]}" port grafana 3000 | sed -E 's/^.*:([0-9]+)$/\1/')"
  if [[ -z "$prometheus_port" || -z "$grafana_port" ]]; then
    echo "Docker não alocou portas efêmeras para o projeto isolado." >&2
    exit 1
  fi
  prometheus_url="http://127.0.0.1:$prometheus_port"
  grafana_url="http://127.0.0.1:$grafana_port"
  ready=0
  for _ in $(seq 1 60); do
    prometheus_status="$(curl --noproxy '*' --connect-timeout 1 --max-time 2 --silent --output /dev/null --write-out '%{http_code}' "$prometheus_url/-/ready" || true)"
    grafana_status="$(curl --noproxy '*' --connect-timeout 1 --max-time 2 --silent --output /dev/null --write-out '%{http_code}' "$grafana_url/api/health" || true)"
    if [[ "$prometheus_status" == "200" && "$grafana_status" == "200" ]]; then
      ready=1
      break
    fi
    sleep 1
  done
  if [[ "$ready" != "1" ]]; then
    echo "Prometheus/Grafana não ficaram prontos em 60s; status HTTP: Prometheus=${prometheus_status:-sem resposta}, Grafana=${grafana_status:-sem resposta}" >&2
    "${compose[@]}" ps >&2
    "${compose[@]}" logs --no-color --tail=80 >&2
    exit 1
  fi
  "${compose[@]}" exec -T prometheus promtool check config /etc/prometheus/prometheus.yml
  curl --fail --silent --show-error -u "$AGD052_GRAFANA_ADMIN_USER:$AGD052_GRAFANA_ADMIN_PASSWORD" \
    "$grafana_url/api/datasources/uid/prometheus" >"$tmp_dir/datasource.json"
  python3 - "$tmp_dir/datasource.json" <<'PY'
import json
import sys

datasource = json.load(open(sys.argv[1], encoding="utf-8"))
if datasource.get("uid") != "prometheus" or datasource.get("url") != "http://prometheus:9090":
    raise SystemExit("Datasource Prometheus não foi provisionado com endpoint esperado")
print("Grafana carregou o datasource Prometheus provisionado")
PY
  curl --fail --silent --show-error -u "$AGD052_GRAFANA_ADMIN_USER:$AGD052_GRAFANA_ADMIN_PASSWORD" \
    "$grafana_url/api/search?type=dash-db" >"$tmp_dir/dashboards.json"
  python3 - "$tmp_dir/dashboards.json" <<'PY'
import json
import sys

expected = {"agd052-api", "agd052-reservas", "agd052-workers", "agd052-whatsapp", "agd052-cobranca", "agd052-exportacoes"}
actual = {item.get("uid") for item in json.load(open(sys.argv[1], encoding="utf-8"))}
missing = expected - actual
if missing:
    raise SystemExit(f"Grafana não carregou dashboards: {sorted(missing)}")
print("Grafana carregou os seis dashboards provisionados")
PY
  echo "Smoke AGD-052 passou no projeto exclusivo $project (Prometheus 127.0.0.1:$prometheus_port; Grafana 127.0.0.1:$grafana_port)."
  exit 0
fi

sentinel_project="kronos-agd052-sentinel-${suffix}"
sentinel_compose="$tmp_dir/sentinel.compose.yaml"
cat >"$sentinel_compose" <<EOF
services:
  sentinel:
    image: $prometheus_image
    command: ["--version"]
    networks: [sentinel-net]
    volumes: [sentinel-data:/sentinel-data]
networks:
  sentinel-net: {}
volumes:
  sentinel-data: {}
EOF
sentinel=(docker compose --project-name "$sentinel_project" -f "$sentinel_compose")
sentinel_containers="$(docker ps -aq --filter "label=com.docker.compose.project=$sentinel_project")"
sentinel_volumes="$(docker volume ls -q --filter "label=com.docker.compose.project=$sentinel_project")"
sentinel_networks="$(docker network ls -q --filter "label=com.docker.compose.project=$sentinel_project")"
if [[ -n "$sentinel_containers$sentinel_volumes$sentinel_networks" ]]; then
  echo "Prefix aleatório sentinela já possui recursos; recusando alterá-los." >&2
  exit 3
fi
"${sentinel[@]}" up -d
sentinel_container="$("${sentinel[@]}" ps -aq sentinel)"
sentinel_volume="$(docker volume ls -q --filter "label=com.docker.compose.project=$sentinel_project" | head -1)"
sentinel_network="$(docker network ls -q --filter "label=com.docker.compose.project=$sentinel_project" | head -1)"
if [[ -z "$sentinel_container" || -z "$sentinel_volume" || -z "$sentinel_network" ]]; then
  echo "Não foi possível criar os três recursos sentinela descartáveis." >&2
  exit 1
fi
sentinel_status="$(docker inspect --format '{{.State.Status}}' "$sentinel_container")"
if [[ "$sentinel_status" != "exited" ]]; then
  docker stop "$sentinel_container" >/dev/null
  sentinel_status="$(docker inspect --format '{{.State.Status}}' "$sentinel_container")"
fi
if [[ "$sentinel_status" != "exited" ]]; then
  echo "Container sentinela não está parado antes da regressão (status=$sentinel_status)." >&2
  exit 1
fi
"$repo_root/scripts/validate-agd052-observability.sh" smoke
docker inspect "$sentinel_container" >/dev/null
docker volume inspect "$sentinel_volume" >/dev/null
docker network inspect "$sentinel_network" >/dev/null
sentinel_status_after="$(docker inspect --format '{{.State.Status}}' "$sentinel_container")"
if [[ "$sentinel_status_after" != "exited" ]]; then
  echo "Container sentinela mudou de estado: $sentinel_status_after" >&2
  exit 1
fi
echo "Regressão de isolamento passou: smoke não removeu container parado, volume nem rede do projeto sentinela próprio."
