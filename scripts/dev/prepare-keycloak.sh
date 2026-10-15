#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."

if [[ ! -f .env.keycloak ]]; then
  umask 077
  cat > .env.keycloak <<EOF
KEYCLOAK_DB_PASSWORD=$(openssl rand -hex 32)
KEYCLOAK_ADMIN_USERNAME=kc-local-admin
KEYCLOAK_ADMIN_PASSWORD=$(openssl rand -hex 32)
OIDC_CLIENT_SECRET=$(openssl rand -hex 32)
EOF
fi

set -a
source .env.keycloak
set +a

compose=(docker compose -p agendamento -f compose.keycloak.yaml --env-file .env.keycloak)
"${compose[@]}" up -d

for attempt in $(seq 1 90); do
  if curl --fail --silent http://localhost:8180/realms/agendamento/.well-known/openid-configuration >/dev/null; then
    break
  fi
  if [[ "$attempt" == 90 ]]; then
    echo "Keycloak não ficou pronto em 90 tentativas." >&2
    exit 1
  fi
  sleep 2
done

kc=("${compose[@]}" exec -T keycloak /opt/keycloak/bin/kcadm.sh)
"${kc[@]}" config credentials \
  --server http://localhost:8080 \
  --realm master \
  --user "$KEYCLOAK_ADMIN_USERNAME" \
  --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null

clients=$("${kc[@]}" get clients -r agendamento -q clientId=agendamento-backend)
client_id=$(jq -r '.[0].id // empty' <<<"$clients")
client_args=(
  -r agendamento
  -s clientId=agendamento-backend
  -s protocol=openid-connect
  -s publicClient=false
  -s standardFlowEnabled=true
  -s directAccessGrantsEnabled=false
  -s 'redirectUris=["http://localhost:8080/login/oauth2/code/keycloak"]'
  -s 'webOrigins=["http://localhost:5173"]'
  -s "secret=$OIDC_CLIENT_SECRET"
)
if [[ -z "$client_id" ]]; then
  "${kc[@]}" create clients "${client_args[@]}" >/dev/null
else
  "${kc[@]}" update "clients/$client_id" "${client_args[@]}" >/dev/null
fi

echo "Keycloak pronto em http://localhost:8180/realms/agendamento"
echo "Cliente OIDC confidencial configurado; secrets permanecem em .env.keycloak (ignorado pelo Git)."
