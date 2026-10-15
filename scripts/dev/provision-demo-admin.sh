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
existing_tenant=$(docker compose -p agendamento exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
  psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" \
  -d "${POSTGRES_DB:-agendamento}" -v tenant_slug="$slug" \
  -c "SELECT EXISTS (SELECT 1 FROM agendamento.tenant WHERE slug = :'tenant_slug')")
if [[ "$existing_tenant" == "t" ]]; then
  echo "Slug já provisionado; use um slug novo. Nenhuma credencial foi alterada." >&2
  exit 1
fi
existing_identity=$(docker compose -p agendamento exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
  psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" \
  -d "${POSTGRES_DB:-agendamento}" -v issuer="http://localhost:8180/realms/agendamento" \
  -v subject="$subject" \
  -c "SELECT EXISTS (SELECT 1 FROM agendamento.admin_user WHERE identity_issuer = :'issuer' AND identity_subject = :'subject')")
if [[ "$existing_identity" == "t" ]]; then
  echo "A identidade já está provisionada no banco; nenhuma credencial foi alterada." >&2
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
  -v tenant_id="$tenant_id" -v tenant_name="$tenant_name" -v tenant_slug="$slug" \
  -v user_id="$subject" -v issuer="http://localhost:8180/realms/agendamento" \
  -v subject="$subject" -v trace_id="$(uuidgen)" <<'SQL'
BEGIN;
INSERT INTO agendamento.tenant (tenant_id, name, slug, timezone, status, version)
VALUES (:'tenant_id', :'tenant_name', :'tenant_slug', 'America/Sao_Paulo', 'ACTIVE', 0)
RETURNING tenant_id;
INSERT INTO agendamento.admin_user (user_id, identity_issuer, identity_subject, status)
VALUES (:'user_id', :'issuer', :'subject', 'ACTIVE');
INSERT INTO agendamento.admin_tenant_membership (user_id, tenant_id, role, status)
VALUES (:'user_id', :'tenant_id', 'TENANT_ADMIN', 'ACTIVE');
INSERT INTO agendamento.audit_event
  (event_id, tenant_id, actor_type, actor_ref, action, target_type, target_id, result,
   correlation_id, trace_id, details)
VALUES
  (gen_random_uuid(), :'tenant_id', 'SYSTEM', 'CONTROLLED_DEMO_PROVISIONER',
   'TENANT_CREATED', 'TENANT', :'tenant_id', 'COMMITTED', :'trace_id', :'trace_id',
   '{"changed_fields":["name","slug","timezone"]}'::jsonb),
  (gen_random_uuid(), :'tenant_id', 'SYSTEM', 'CONTROLLED_DEMO_PROVISIONER',
   'TENANT_ADMIN_LINKED', 'ADMIN_TENANT_MEMBERSHIP', :'user_id', 'COMMITTED',
   :'trace_id', :'trace_id', '{"changed_fields":["identity","membership"]}'::jsonb);
COMMIT;
SQL
)

umask 077
printf 'KEYCLOAK_DEMO_USERNAME=%s\nKEYCLOAK_DEMO_PASSWORD=%s\nKEYCLOAK_UNLINKED_USERNAME=%s\nKEYCLOAK_UNLINKED_PASSWORD=%s\nDEMO_TENANT_ID=%s\n' \
  "$username" "$temporary_password" "$unlinked_username" "$unlinked_password" "$tenant_id" > .demo-admin.credentials
echo "Admin associado e identidade não associada provisionados. Credenciais e tenant_id estão em .demo-admin.credentials (ignorado pelo Git)."
