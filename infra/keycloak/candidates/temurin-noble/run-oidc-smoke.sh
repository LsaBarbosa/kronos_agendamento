#!/usr/bin/env bash
set -euo pipefail

image_ref="${1:-kronos-keycloak:temurin-noble-prototype}"
fixture_dir="infra/keycloak/candidates/temurin-noble/realm-import"
container_name="temurin-noble-oidc-smoke-$$"
redirect_uri="https://127.0.0.1/callback"
cert_dir="$(mktemp -d /tmp/temurin-noble-oidc-cert.XXXXXX)"

cleanup() {
  docker stop "${container_name}" >/dev/null 2>&1 || true
  rm -rf "${cert_dir}"
}
trap cleanup EXIT

openssl req -x509 -newkey rsa:2048 -nodes -days 1 \
  -keyout "${cert_dir}/tls.key" \
  -out "${cert_dir}/tls.crt" \
  -subj "/CN=127.0.0.1" \
  -addext "subjectAltName=IP:127.0.0.1" >/dev/null 2>&1

docker run --rm --detach \
  --name "${container_name}" \
  --publish 127.0.0.1::8443 \
  --volume "${PWD}/${fixture_dir}:/opt/keycloak/data/import:ro" \
  --volume "${cert_dir}:/run/keycloak-test-tls:ro" \
  --env KC_BOOTSTRAP_ADMIN_USERNAME=synthetic-admin \
  --env KC_BOOTSTRAP_ADMIN_PASSWORD=synthetic-only-admin-password \
  --env KC_HTTPS_CERTIFICATE_FILE=/run/keycloak-test-tls/tls.crt \
  --env KC_HTTPS_CERTIFICATE_KEY_FILE=/run/keycloak-test-tls/tls.key \
  "${image_ref}" start-dev --http-enabled=false --https-port=8443 \
    --hostname-strict=false --import-realm >/dev/null

published="$(docker port "${container_name}" 8443/tcp | head -n 1)"
port="${published##*:}"
python3 infra/keycloak/candidates/temurin-noble/oidc-smoke.py \
  "https://127.0.0.1:${port}" "${redirect_uri}"
