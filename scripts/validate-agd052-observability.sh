#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="$repo_root/compose.observability.yaml"
project="kronos-agd052-observability"
mode="${1:-validate}"

if [[ "$mode" != "validate" && "$mode" != "smoke" ]]; then
  echo "Uso: $0 [validate|smoke]" >&2
  exit 2
fi

command -v docker >/dev/null || { echo "docker CLI não encontrado" >&2; exit 2; }
command -v python3 >/dev/null || { echo "python3 não encontrado" >&2; exit 2; }
command -v openssl >/dev/null || { echo "openssl necessário para credencial descartável" >&2; exit 2; }

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
AGD052_PROMETHEUS_PORT=19090
AGD052_GRAFANA_PORT=13000
AGD052_SCRAPE_TOKEN_FILE=$tmp_dir/scrape-token
EOF
unset grafana_password
unset scrape_token
set -a
source "$env_file"
set +a

stack_started=0
cleanup() {
  if [[ "$stack_started" == "1" ]]; then
    docker compose --project-name "$project" --env-file "$env_file" -f "$compose_file" down --volumes --remove-orphans
  fi
  rm -rf -- "$tmp_dir"
}
trap cleanup EXIT

cd "$repo_root"
docker compose --project-name "$project" --env-file "$env_file" -f "$compose_file" config --quiet

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

python3 - "$repo_root/ops/observability/grafana" <<'PY'
import json
import sys
from pathlib import Path

base = Path(sys.argv[1])
definitions = base / "provisioning/dashboards/definitions"
expected = {"api", "reservas", "workers", "whatsapp", "cobranca", "exportacoes"}
found = {p.stem for p in definitions.glob("*.json")}
if found != expected:
    raise SystemExit(f"Dashboards esperados {sorted(expected)}, encontrados {sorted(found)}")
uids = set()
for path in sorted(definitions.glob("*.json")):
    dashboard = json.loads(path.read_text(encoding="utf-8"))
    if dashboard.get("uid") in uids or not dashboard.get("uid"):
        raise SystemExit(f"UID de dashboard ausente/duplicado em {path}")
    uids.add(dashboard["uid"])
    if not dashboard.get("panels"):
        raise SystemExit(f"Dashboard sem painéis: {path}")
print(f"JSON de {len(found)} dashboards válidos; provisioning versionado presente")
PY

if [[ "$mode" == "smoke" ]]; then
  existing="$(docker compose --project-name "$project" --env-file "$env_file" -f "$compose_file" ps --quiet)"
  if [[ -n "$existing" ]]; then
    echo "Projeto $project já existe; recusando alterar ou remover recursos preexistentes" >&2
    exit 3
  fi
  stack_started=1
  docker compose --project-name "$project" --env-file "$env_file" -f "$compose_file" up -d
  prometheus_url="http://127.0.0.1:19090"
  grafana_url="http://127.0.0.1:13000"
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
    echo "Prometheus/Grafana não ficaram prontos em 60s; estado e logs redigidos do projeto próprio:" >&2
    echo "Últimos status HTTP: Prometheus=${prometheus_status:-sem resposta}, Grafana=${grafana_status:-sem resposta}" >&2
    docker compose --project-name "$project" --env-file "$env_file" -f "$compose_file" port prometheus 9090 >&2 || true
    docker compose --project-name "$project" --env-file "$env_file" -f "$compose_file" port grafana 3000 >&2 || true
    docker compose --project-name "$project" --env-file "$env_file" -f "$compose_file" ps >&2
    docker compose --project-name "$project" --env-file "$env_file" -f "$compose_file" logs --no-color --tail=80 >&2
    exit 1
  fi
  docker compose --project-name "$project" --env-file "$env_file" -f "$compose_file" exec -T prometheus promtool check config /etc/prometheus/prometheus.yml
  curl --fail --silent --show-error -u "$AGD052_GRAFANA_ADMIN_USER:$AGD052_GRAFANA_ADMIN_PASSWORD" \
    "$grafana_url/api/datasources/uid/prometheus" >"$tmp_dir/datasource.json"
  python3 - "$tmp_dir/datasource.json" <<'PY'
import json
import sys

datasource = json.load(open(sys.argv[1], encoding="utf-8"))
if datasource.get("uid") != "prometheus" or datasource.get("url") != "http://prometheus:9090":
    raise SystemExit("Datasource Prometheus não foi provisionado com o endpoint esperado")
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
fi

echo "Validação AGD-052 concluída ($mode). Credencial e stack sintética não foram persistidas no repositório."
