#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."

if [[ $# -ne 2 ]]; then
  echo "Uso: $0 <slug-tenant> <nome-tenant>" >&2
  exit 2
fi
slug=$1
tenant_name=$2
if [[ ! "$slug" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ ]]; then
  echo "Slug deve ser minúsculo e kebab-case." >&2
  exit 2
fi
if [[ ! -f .env || ! -f .env.keycloak ]]; then
  echo "Prepare .env da aplicação e execute scripts/dev/prepare-keycloak.sh primeiro." >&2
  exit 1
fi

set -a
source .env
source .env.keycloak
set +a

if [[ -z "${POSTGRES_PASSWORD:-}" || -z "${POSTGRES_USER:-}" ]]; then
  echo ".env precisa configurar POSTGRES_USER e POSTGRES_PASSWORD." >&2
  exit 1
fi

compose=(docker compose -p agendamento -f compose.keycloak.yaml --env-file .env.keycloak)
kc=("${compose[@]}" exec -T keycloak /opt/keycloak/bin/kcadm.sh)
"${kc[@]}" config credentials \
  --server http://localhost:8080 \
  --realm master \
  --user "$KEYCLOAK_ADMIN_USERNAME" \
  --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null

username=demo-admin
temporary_password=$(openssl rand -base64 24)
unlinked_password=$(openssl rand -base64 24)
users=$("${kc[@]}" get users -r agendamento -q username="$username")
subject=$(jq -r '.[0].id // empty' <<<"$users")
if [[ -z "$subject" ]]; then
  "${kc[@]}" create users -r agendamento \
    -s "username=$username" -s enabled=true -s emailVerified=true >/dev/null
  users=$("${kc[@]}" get users -r agendamento -q username="$username")
  subject=$(jq -r '.[0].id // empty' <<<"$users")
fi
if [[ -z "$subject" ]]; then
  echo "Não foi possível obter o subject provisionado no Keycloak." >&2
  exit 1
fi
"${kc[@]}" update "users/$subject" -r agendamento \
  -s enabled=true -s email=demo-admin@local.invalid -s emailVerified=true \
  -s firstName=Demo -s lastName=Admin -s 'requiredActions=[]' >/dev/null
"${kc[@]}" set-password -r agendamento --username "$username" \
  --new-password "$temporary_password" >/dev/null

unlinked_username=unlinked-demo
unlinked_users=$("${kc[@]}" get users -r agendamento -q username="$unlinked_username")
if [[ -z "$(jq -r '.[0].id // empty' <<<"$unlinked_users")" ]]; then
  "${kc[@]}" create users -r agendamento \
    -s "username=$unlinked_username" -s enabled=true -s emailVerified=true >/dev/null
  unlinked_users=$("${kc[@]}" get users -r agendamento -q username="$unlinked_username")
fi
unlinked_subject=$(jq -r '.[0].id // empty' <<<"$unlinked_users")
"${kc[@]}" update "users/$unlinked_subject" -r agendamento \
  -s enabled=true -s email=unlinked-demo@local.invalid -s emailVerified=true \
  -s firstName=Unlinked -s lastName=Demo -s 'requiredActions=[]' >/dev/null
"${kc[@]}" set-password -r agendamento --username "$unlinked_username" \
  --new-password "$unlinked_password" >/dev/null

tenant_id=$(uuidgen)
tenant_id=$(docker compose -p agendamento exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
  psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" \
  -d "${POSTGRES_DB:-agendamento}" \
  -v tenant_id="$tenant_id" -v tenant_name="$tenant_name" -v tenant_slug="$slug" <<'SQL'
INSERT INTO agendamento.tenant (tenant_id, name, slug, timezone, status, version)
VALUES (:'tenant_id', :'tenant_name', :'tenant_slug', 'America/Sao_Paulo', 'ACTIVE', 0)
ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name
RETURNING tenant_id;
SQL
)

issuer=http://localhost:8180/realms/agendamento
docker compose -p agendamento exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
  psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" \
  -d "${POSTGRES_DB:-agendamento}" \
  -v user_id="$subject" -v issuer="$issuer" -v subject="$subject" -v tenant_id="$tenant_id" <<'SQL'
INSERT INTO agendamento.admin_user (user_id, identity_issuer, identity_subject, status)
VALUES (:'user_id', :'issuer', :'subject', 'ACTIVE')
ON CONFLICT (identity_issuer, identity_subject) DO UPDATE SET status = 'ACTIVE';
INSERT INTO agendamento.admin_tenant_membership (user_id, tenant_id, role, status)
SELECT user_id, :'tenant_id', 'TENANT_ADMIN', 'ACTIVE'
FROM agendamento.admin_user
WHERE identity_issuer = :'issuer' AND identity_subject = :'subject'
ON CONFLICT (user_id) DO UPDATE SET tenant_id = EXCLUDED.tenant_id, role = EXCLUDED.role, status = 'ACTIVE';
SQL

umask 077
printf 'KEYCLOAK_DEMO_USERNAME=%s\nKEYCLOAK_DEMO_PASSWORD=%s\nKEYCLOAK_UNLINKED_USERNAME=%s\nKEYCLOAK_UNLINKED_PASSWORD=%s\nDEMO_TENANT_ID=%s\n' \
  "$username" "$temporary_password" "$unlinked_username" "$unlinked_password" "$tenant_id" > .demo-admin.credentials
echo "Admin associado e identidade não associada provisionados. Credenciais e tenant_id estão em .demo-admin.credentials (ignorado pelo Git)."
