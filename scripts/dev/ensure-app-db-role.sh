#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."
if [[ ! -f .env ]]; then
  echo "Execute scripts/dev/prepare-local-env.sh primeiro." >&2
  exit 1
fi
set -a
source .env
set +a
if [[ -z "${APP_DB_PASSWORD:-}" ]]; then
  APP_DB_PASSWORD=$(openssl rand -hex 32)
  printf '\nAPP_DB_PASSWORD=%s\n' "$APP_DB_PASSWORD" >> .env
  chmod 600 .env
fi

docker compose -p agendamento -f compose.yaml --env-file .env exec -T \
  -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
  psql -X -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -v app_password="$APP_DB_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE agendamento_app LOGIN PASSWORD %L', :'app_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agendamento_app')\gexec
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM pg_roles
    WHERE rolname = 'agendamento_app'
      AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR NOT rolinherit)
  ) THEN
    RAISE EXCEPTION 'agendamento_app has unsafe role attributes; provision a separate least-privilege login';
  END IF;
  IF EXISTS (
    SELECT 1 FROM pg_auth_members membership
    JOIN pg_roles granted_role ON granted_role.oid = membership.roleid
    WHERE membership.member = 'agendamento_app'::regrole
      AND granted_role.rolname <> 'agendamento_runtime'
  ) THEN
    RAISE EXCEPTION 'agendamento_app has unexpected inherited role memberships';
  END IF;
END
$$;
ALTER ROLE agendamento_app LOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE
  NOREPLICATION NOBYPASSRLS PASSWORD :'app_password';
SQL
echo "Login runtime agendamento_app validado; Flyway V5 concede seus privilégios."
