#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."

if [[ -e .env ]]; then
  echo ".env já existe; nada foi sobrescrito."
  exit 0
fi

umask 077
password=$(openssl rand -hex 32)
cat > .env <<EOF
POSTGRES_DB=agendamento
POSTGRES_USER=agendamento
POSTGRES_PASSWORD=$password
DB_PASSWORD=$password
POSTGRES_PORT=5432
APP_PORT=8080
EOF
echo "Arquivo .env local criado com senha descartável e permissões privadas."
