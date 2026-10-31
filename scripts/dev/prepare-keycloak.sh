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

compose_project=${COMPOSE_PROJECT_NAME:-${AGD_COMPOSE_PROJECT:-agendamento}}
image_ref=${KEYCLOAK_IMAGE:-kronos-keycloak:26.8.1-custom}
if ! docker image inspect "$image_ref" >/dev/null 2>&1; then
  scripts/dev/build-keycloak-image.sh "$image_ref"
fi
export KEYCLOAK_IMAGE="$image_ref"
compose=(docker compose -p "$compose_project" -f compose.keycloak.yaml --env-file .env.keycloak)
if [[ -n "${AGD_COMPOSE_OVERRIDE:-}" ]]; then
  compose+=(-f "$AGD_COMPOSE_OVERRIDE")
fi
"${compose[@]}" up -d

keycloak_public_url=${KEYCLOAK_PUBLIC_URL:-${AGD_KEYCLOAK_EXTERNAL_URL:-http://localhost:8180}}
keycloak_admin_url=${KEYCLOAK_ADMIN_URL:-http://localhost:8080}
for attempt in $(seq 1 90); do
  if curl --fail --silent "$keycloak_public_url/realms/agendamento/.well-known/openid-configuration" >/dev/null; then
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
  --server "$keycloak_admin_url" \
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
  -s "redirectUris=[\"${APP_PUBLIC_URL:-http://localhost:8080}/login/oauth2/code/keycloak\"]"
  -s "webOrigins=[\"${FRONTEND_PUBLIC_URL:-http://localhost:5173}\"]"
  -s "secret=$OIDC_CLIENT_SECRET"
)
if [[ -z "$client_id" ]]; then
  "${kc[@]}" create clients "${client_args[@]}" >/dev/null
else
  "${kc[@]}" update "clients/$client_id" "${client_args[@]}" >/dev/null
fi

echo "Keycloak pronto em $keycloak_public_url/realms/agendamento"
echo "Cliente OIDC confidencial configurado; secrets permanecem em .env.keycloak (ignorado pelo Git)."
