#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
env_file=
project_name=
disposable=false
while (($#)); do
  case "$1" in
    --env-file) env_file=${2:?Missing env file path}; shift 2 ;;
    --project-name) project_name=${2:?Missing Compose project name}; shift 2 ;;
    --disposable) disposable=true; shift ;;
    *) echo "Usage: $0 --env-file FILE --project-name NAME [--disposable]" >&2; exit 2 ;;
  esac
done
[[ -n "$env_file" && -f "$env_file" ]] || { echo "A readable external env file is required." >&2; exit 2; }
[[ -n "$project_name" ]] || { echo "An explicit Compose project name is required." >&2; exit 2; }
[[ "$project_name" =~ ^[a-z0-9][a-z0-9_-]{5,62}$ ]] || { echo "Compose project name must be a unique lowercase slug (6-63 characters)." >&2; exit 2; }

python3 - "$env_file" "$disposable" "$project_name" <<'PY'
import ipaddress, os, re, stat, sys

path, disposable, expected_project = sys.argv[1], sys.argv[2] == "true", sys.argv[3]
mode = stat.S_IMODE(os.stat(path).st_mode)
if mode & 0o077:
    raise SystemExit("Preflight: env file has group/other permissions; require mode 0600.")
values = {}
for raw in open(path, encoding="utf-8"):
    line = raw.strip()
    if not line or line.startswith("#"):
        continue
    if "=" not in line:
        raise SystemExit("Preflight: malformed env file line.")
    key, value = line.split("=", 1)
    values[key] = value

required = [
    "STAGING_PROJECT",
    "APP_HOST", "KEYCLOAK_HOST", "APP_ORIGIN", "OIDC_ISSUER_ORIGIN",
    "OIDC_ISSUER_URI", "OIDC_AUTHORIZATION_URI", "OIDC_TOKEN_URI",
    "OIDC_JWK_SET_URI", "OIDC_USER_INFO_URI", "OIDC_CLIENT_ID",
    "OIDC_CLIENT_SECRET", "OIDC_REDIRECT_URI", "APP_FRONTEND_URL",
    "STAGING_HTTPS_PORT", "STAGING_BIND_ADDRESS", "STAGING_SUBNET",
    "STAGING_INGRESS_SUBNET", "STAGING_PROXY_IP", "STAGING_PROXY_CIDR", "STAGING_TLS_DIR",
    "STAGING_REALM_FILE", "STAGING_POSTGRES_IMAGE", "STAGING_APP_DB_PASSWORD",
    "STAGING_APP_DB_MIGRATOR_PASSWORD", "STAGING_KEYCLOAK_DB_PASSWORD",
    "STAGING_KEYCLOAK_BOOTSTRAP_USER", "STAGING_KEYCLOAK_BOOTSTRAP_PASSWORD",
    "PUBLIC_BOOKING_HMAC_ACTIVE_KEY_ID", "PUBLIC_BOOKING_HMAC_KEYS",
    "STAGING_KEYCLOAK_IMAGE", "STAGING_BACKEND_IMAGE", "STAGING_EDGE_IMAGE",
]
missing = [key for key in required if not values.get(key, "").strip()]
if missing:
    raise SystemExit("Preflight: required values missing: " + ", ".join(missing))
if values["STAGING_PROJECT"] != expected_project:
    raise SystemExit("Preflight: STAGING_PROJECT does not match the explicitly selected Compose project.")

for key in ("STAGING_KEYCLOAK_DB_PASSWORD", "STAGING_APP_DB_PASSWORD",
            "STAGING_APP_DB_MIGRATOR_PASSWORD", "STAGING_KEYCLOAK_BOOTSTRAP_PASSWORD",
            "OIDC_CLIENT_SECRET"):
    value = values[key]
    if len(value) < 32 or value.lower() in {"changeme", "password", "secret"}:
        raise SystemExit(f"Preflight: {key} must be an externally managed strong secret.")
if len({values[key] for key in ("STAGING_KEYCLOAK_DB_PASSWORD", "STAGING_APP_DB_PASSWORD",
                                 "STAGING_APP_DB_MIGRATOR_PASSWORD", "STAGING_KEYCLOAK_BOOTSTRAP_PASSWORD",
                                 "OIDC_CLIENT_SECRET")}) != 5:
    raise SystemExit("Preflight: use a distinct secret for each credential purpose.")
try:
    key_id, encoded = values["PUBLIC_BOOKING_HMAC_KEYS"].split("=", 1)
    import base64
    hmac_key = base64.b64decode(encoded, validate=True)
except Exception as error:
    raise SystemExit("Preflight: public-booking HMAC key ring must use id=base64 format.") from error
if key_id != values["PUBLIC_BOOKING_HMAC_ACTIVE_KEY_ID"] or len(hmac_key) < 32:
    raise SystemExit("Preflight: active public-booking HMAC key must resolve to at least 256 bits.")

for key in ("STAGING_POSTGRES_IMAGE", "STAGING_KEYCLOAK_IMAGE", "STAGING_BACKEND_IMAGE", "STAGING_EDGE_IMAGE"):
    if "@sha256:" not in values[key] and key == "STAGING_POSTGRES_IMAGE":
        raise SystemExit("Preflight: PostgreSQL image must be pinned by digest.")

app_origin = values["APP_ORIGIN"].rstrip("/")
issuer_origin = values["OIDC_ISSUER_ORIGIN"].rstrip("/")
issuer = values["OIDC_ISSUER_URI"].rstrip("/")
if app_origin != f"https://{values['APP_HOST']}:{values['STAGING_HTTPS_PORT']}":
    raise SystemExit("Preflight: APP_ORIGIN must match the HTTPS application host and public port.")
if issuer_origin != f"https://{values['KEYCLOAK_HOST']}:{values['STAGING_HTTPS_PORT']}":
    raise SystemExit("Preflight: Keycloak HTTPS origin does not match its public host/port.")
if issuer != issuer_origin + "/realms/agendamento":
    raise SystemExit("Preflight: OIDC issuer must be the exact agendamento realm issuer.")
if values["OIDC_AUTHORIZATION_URI"] != issuer + "/protocol/openid-connect/auth":
    raise SystemExit("Preflight: authorization endpoint must use the public HTTPS issuer.")
if values["OIDC_REDIRECT_URI"] != app_origin + "/login/oauth2/code/keycloak":
    raise SystemExit("Preflight: callback must exactly match the configured public application origin.")
if values["APP_FRONTEND_URL"] != app_origin + "/admin":
    raise SystemExit("Preflight: frontend URL must remain on the application HTTPS origin.")
for key in ("OIDC_TOKEN_URI", "OIDC_JWK_SET_URI", "OIDC_USER_INFO_URI"):
    if not (values[key].startswith("http://keycloak:8080/realms/agendamento/") or
            values[key].startswith(issuer + "/protocol/openid-connect/")):
        raise SystemExit(f"Preflight: {key} must use the isolated Keycloak backchannel or public issuer.")

if not re.fullmatch(r"[a-z0-9.-]+", values["APP_HOST"]) or not re.fullmatch(r"[a-z0-9.-]+", values["KEYCLOAK_HOST"]):
    raise SystemExit("Preflight: hosts must be DNS names without URL syntax.")
if values["APP_HOST"] == values["KEYCLOAK_HOST"]:
    raise SystemExit("Preflight: application and identity hosts must be distinct.")
if not disposable and (values["APP_HOST"].endswith(".test") or values["KEYCLOAK_HOST"].endswith(".test") or
                       "localhost" in values["APP_HOST"] or "localhost" in values["KEYCLOAK_HOST"]):
    raise SystemExit("Preflight: .test/localhost hosts are allowed only for the disposable runner.")
try:
    subnet = ipaddress.ip_network(values["STAGING_SUBNET"])
    ingress_subnet = ipaddress.ip_network(values["STAGING_INGRESS_SUBNET"])
    proxy_ip = ipaddress.ip_address(values["STAGING_PROXY_IP"])
    trusted = ipaddress.ip_network(values["STAGING_PROXY_CIDR"], strict=False)
except ValueError as error:
    raise SystemExit("Preflight: invalid network configuration.") from error
if (subnet.version != 4 or ingress_subnet.version != 4 or subnet.overlaps(ingress_subnet) or
        proxy_ip not in subnet or trusted != ipaddress.ip_network(f"{proxy_ip}/32")):
    raise SystemExit("Preflight: proxy IP/CIDR must be a single address inside the staging IPv4 subnet.")
if values["STAGING_BIND_ADDRESS"] != "127.0.0.1" and disposable:
    raise SystemExit("Preflight: disposable HTTPS runner may bind only loopback.")
if values["STAGING_BIND_ADDRESS"] != "127.0.0.1":
    raise SystemExit("Preflight: this preparation binds HTTPS only to loopback; public exposure is outside scope.")
try:
    https_port = int(values["STAGING_HTTPS_PORT"])
except ValueError as error:
    raise SystemExit("Preflight: HTTPS port must be numeric.") from error
if not 1 <= https_port <= 65535:
    raise SystemExit("Preflight: HTTPS port must be between 1 and 65535.")
for key in ("STAGING_TLS_DIR",):
    if not os.path.isabs(values[key]) or not os.path.isdir(values[key]):
        raise SystemExit(f"Preflight: {key} must point to an existing absolute directory.")
if not os.path.isabs(values["STAGING_REALM_FILE"]) or not os.path.isfile(values["STAGING_REALM_FILE"]):
    raise SystemExit("Preflight: realm import must be an existing absolute file.")
for key in ("STAGING_KEYCLOAK_IMAGE", "STAGING_BACKEND_IMAGE", "STAGING_EDGE_IMAGE"):
    if not re.fullmatch(r"[a-z0-9][a-z0-9_.-]*:[A-Za-z0-9_.-]+", values[key]):
        raise SystemExit(f"Preflight: {key} must be a unique, repository-local run tag.")
    if expected_project not in values[key].split(":", 1)[0]:
        raise SystemExit(f"Preflight: {key} repository must be scoped to the selected Compose project.")
print("Staging preflight: required domains, HTTPS issuer/callback, network isolation and secret presence validated; values redacted.")
PY

docker compose --project-name "$project_name" \
  --project-directory "$repo_root" --file "$repo_root/compose.staging.yaml" \
  --env-file "$env_file" config --quiet
echo "Staging preflight: Docker Compose configuration valid."
