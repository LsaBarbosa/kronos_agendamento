#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."

umask 077
if [[ ! -e .env ]]; then
  password=$(openssl rand -hex 32)
  app_password=$(openssl rand -hex 32)
  cat > .env <<EOF
POSTGRES_DB=agendamento
POSTGRES_USER=agendamento
POSTGRES_PASSWORD=$password
APP_DB_PASSWORD=$app_password
POSTGRES_PORT=5432
APP_PORT=8080
EOF
  echo "Arquivo .env local criado com credenciais descartáveis e permissões privadas."
fi

if ! grep -q '^PUBLIC_BOOKING_HMAC_ACTIVE_KEY_ID=.' .env; then
  printf '\nPUBLIC_BOOKING_HMAC_ACTIVE_KEY_ID=local-v1\n' >> .env
fi
if ! grep -q '^PUBLIC_BOOKING_HMAC_KEYS=.' .env; then
  printf 'PUBLIC_BOOKING_HMAC_KEYS=local-v1=%s\n' "$(openssl rand -base64 32)" >> .env
fi
chmod 600 .env
echo "Configuração local de HMAC estável preparada em .env (ignorado pelo Git)."

if [[ ! -e .env.e2e ]]; then
  printf 'APP_BILLING_E2E_WEBHOOK_SECRET=%s\n' "$(openssl rand -hex 32)" > .env.e2e
fi
chmod 600 .env.e2e
echo "Segredo descartável do webhook E2E preparado em .env.e2e (ignorado pelo Git)."
