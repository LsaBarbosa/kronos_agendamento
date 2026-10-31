#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."

if [[ $# -lt 2 || $# -gt 3 ]]; then
	echo "Uso: $0 <slug-tenant> <nome-tenant> [usuario-admin]" >&2
  exit 2
fi
slug=$1
tenant_name=$2
username=${3:-demo-admin}
compose_project=${COMPOSE_PROJECT_NAME:-${AGD_COMPOSE_PROJECT:-agendamento}}
keycloak_public_url=${KEYCLOAK_PUBLIC_URL:-${AGD_KEYCLOAK_EXTERNAL_URL:-http://localhost:8180}}
keycloak_issuer_uri=${AGD_KEYCLOAK_ISSUER_URI:-$keycloak_public_url/realms/agendamento}
if [[ ! "$slug" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ ]]; then
  echo "Slug deve ser minúsculo e kebab-case." >&2
  exit 2
fi
if [[ ! "$username" =~ ^[a-zA-Z0-9._-]+$ ]]; then
	echo "Usuário deve conter somente letras, números, ponto, hífen ou sublinhado." >&2
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

compose=(docker compose -p "$compose_project" -f compose.keycloak.yaml --env-file .env.keycloak)
if [[ -n "${AGD_COMPOSE_OVERRIDE:-}" ]]; then
  compose+=(-f "$AGD_COMPOSE_OVERRIDE")
fi
kc=("${compose[@]}" exec -T keycloak /opt/keycloak/bin/kcadm.sh)
"${kc[@]}" config credentials \
  --server http://localhost:8080 \
  --realm master \
  --user "$KEYCLOAK_ADMIN_USERNAME" \
  --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null

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
existing_tenant=$("${compose[@]}" exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
  psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" \
  -d "${POSTGRES_DB:-agendamento}" -v tenant_slug="$slug" <<'SQL'
SELECT EXISTS (SELECT 1 FROM agendamento.tenant WHERE slug = :'tenant_slug');
SQL
)

if [[ "$existing_tenant" == "t" ]]; then
  echo "Slug já provisionado; use um slug novo. Nenhuma credencial foi alterada." >&2
  exit 1
fi
existing_identity=$("${compose[@]}" exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
  psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" \
  -d "${POSTGRES_DB:-agendamento}" -v issuer="$keycloak_issuer_uri" \
  -v subject="$subject" <<'SQL'
SELECT EXISTS (SELECT 1 FROM agendamento.admin_user WHERE identity_issuer = :'issuer' AND identity_subject = :'subject');
SQL
)
if [[ "$existing_identity" == "t" ]]; then
  echo "A identidade já está provisionada no banco; nenhuma credencial foi alterada." >&2
  exit 1
fi
"${kc[@]}" update "users/$subject" -r agendamento \
	-s enabled=true -s "email=$username@local.invalid" -s emailVerified=true \
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
tenant_id=$("${compose[@]}" exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
  psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" \
  -d "${POSTGRES_DB:-agendamento}" \
  -v tenant_id="$tenant_id" -v tenant_name="$tenant_name" -v tenant_slug="$slug" \
  -v user_id="$subject" -v issuer="$keycloak_issuer_uri" \
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

if [[ "${AGD_E2E_RETENTION_FIXTURE:-false}" == "true" ]]; then
  retention_client_id=$(uuidgen)
  "${compose[@]}" exec -T \
    -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
    psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" \
    -d "${POSTGRES_DB:-agendamento}" \
    -v tenant_id="$tenant_id" -v client_id="$retention_client_id" <<'SQL'
INSERT INTO agendamento.client
  (client_id, tenant_id, display_name, normalized_phone)
VALUES
  (:'client_id', :'tenant_id', 'E2E Synthetic Retention Client', '+14155550001');
SQL
  echo "Cliente sintético de retenção provisionado somente como fixture explícita de E2E."
fi

if [[ "${AGD_E2E_TRIAL_FIXTURE:-false}" == "true" ]]; then
  trial_subscription_id=$(uuidgen)
  trial_grant_id=$(uuidgen)
  trial_operation_id=$(uuidgen)
  trial_trace_id=$(uuidgen)
  trial_identity_digest=$(printf '%s' 'e2e-only-stable-commercial-identity' | sha256sum | cut -d' ' -f1)
  "${compose[@]}" exec -T \
    -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
    psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" \
    -d "${POSTGRES_DB:-agendamento}" \
    -v tenant_id="$tenant_id" -v subscription_id="$trial_subscription_id" \
    -v grant_id="$trial_grant_id" -v operation_id="$trial_operation_id" \
    -v identity_digest="$trial_identity_digest" -v trace_id="$trial_trace_id" <<'SQL'
BEGIN;
WITH timing AS (SELECT clock_timestamp() AS started_at)
INSERT INTO agendamento.subscription
  (subscription_id, tenant_id, status, trial_started_at, trial_ends_at, version)
SELECT :'subscription_id', :'tenant_id', 'TRIALING', started_at,
       started_at + INTERVAL '336 hours', 1
FROM timing;
INSERT INTO agendamento.commercial_trial_grant
  (grant_id, tenant_id, commercial_identity_digest, operation_id, granted_at)
SELECT :'grant_id', :'tenant_id', :'identity_digest', :'operation_id', trial_started_at
FROM agendamento.subscription WHERE subscription_id = :'subscription_id';
INSERT INTO agendamento.subscription_history
  (event_id, tenant_id, subscription_id, event_type, from_status, to_status,
   event_at, version, operation_id)
SELECT gen_random_uuid(), tenant_id, subscription_id, 'TRIAL_GRANTED', NULL, 'TRIALING',
       trial_started_at, 1, :'operation_id'
FROM agendamento.subscription WHERE subscription_id = :'subscription_id';
INSERT INTO agendamento.audit_event
  (event_id, tenant_id, actor_type, actor_ref, action, target_type, target_id, result,
   correlation_id, trace_id, details)
VALUES
  (gen_random_uuid(), :'tenant_id', 'SYSTEM', 'SUBSCRIPTION_LIFECYCLE',
   'SUBSCRIPTION_TRIAL_GRANTED', 'SUBSCRIPTION', :'subscription_id', 'COMMITTED',
   :'trace_id', :'trace_id',
   '{"changed_fields":["subscription"],"version_before":0,"version_after":1}'::jsonb);
COMMIT;
SQL
fi

umask 077
printf 'KEYCLOAK_DEMO_USERNAME=%s\nKEYCLOAK_DEMO_PASSWORD=%s\nKEYCLOAK_UNLINKED_USERNAME=%s\nKEYCLOAK_UNLINKED_PASSWORD=%s\nDEMO_TENANT_ID=%s\n' \
  "$username" "$temporary_password" "$unlinked_username" "$unlinked_password" "$tenant_id" > .demo-admin.credentials
echo "Admin associado e identidade não associada provisionados. Credenciais e tenant_id estão em .demo-admin.credentials (ignorado pelo Git)."
if [[ "${AGD_E2E_TRIAL_FIXTURE:-false}" == "true" ]]; then
  echo "Trial sintético de 14 dias provisionado somente como fixture explícita de E2E."
fi
