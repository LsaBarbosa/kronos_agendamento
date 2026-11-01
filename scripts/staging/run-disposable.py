#!/usr/bin/env python3
"""Build and exercise AGD-003 over isolated local HTTPS; cleanup is project-scoped."""

from __future__ import annotations

import base64
import atexit
import ipaddress
import json
import os
import pathlib
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[2]
COMPOSE_FILE = ROOT / "compose.staging.yaml"
POSTGRES_IMAGE = "postgres:16.13-alpine@sha256:4e6e670bb069649261c9c18031f0aded7bb249a5b6664ddec29c013a89310d50"
PROJECT = f"agd003-https-{int(time.time())}-{os.getpid()}-{secrets.token_hex(3)}"


def run(args, *, env=None, input_text=None, capture=False, check=True, timeout=180):
    result = subprocess.run(
        args,
        cwd=ROOT,
        env=env,
        input=input_text,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
        timeout=timeout,
    )
    if check and result.returncode:
        output = result.stdout or ""
        for secret in SECRETS:
            output = output.replace(secret, "[redacted]")
        raise RuntimeError(f"Command failed ({result.returncode}): {args[0]} {pathlib.Path(args[0]).name if '/' in args[0] else ''}\n{output[-7000:]}")
    return result


SECRETS: list[str] = []
CLEANUP_DIR: pathlib.Path | None = None
CLEANUP_ENV: pathlib.Path | None = None


def cleanup_project() -> None:
    if CLEANUP_ENV and CLEANUP_ENV.is_file():
        result = subprocess.run(compose(CLEANUP_ENV, "down", "--volumes", "--remove-orphans", "--rmi", "local"),
                                cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=180)
        if result.returncode:
            output = result.stdout or ""
            for secret in SECRETS:
                output = output.replace(secret, "[redacted]")
            print("ERROR: project-scoped Compose cleanup failed; inspect only this project:", output[-4000:], file=sys.stderr)
    if CLEANUP_DIR:
        shutil.rmtree(CLEANUP_DIR, ignore_errors=True)


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def unused_subnet(exclude=()) -> ipaddress.IPv4Network:
    existing = []
    network_ids = run(["docker", "network", "ls", "-q"], capture=True).stdout.split()
    if network_ids:
        networks = json.loads(run(["docker", "network", "inspect", *network_ids], capture=True).stdout)
        for network in networks:
            for config in (network.get("IPAM", {}).get("Config") or []):
                if config.get("Subnet"):
                    existing.append(ipaddress.ip_network(config["Subnet"], strict=False))
    existing.extend(exclude)
    routes = json.loads(run(["ip", "-j", "route"], capture=True).stdout)
    for route in routes:
        destination = route.get("dst")
        if destination and destination != "default":
            try:
                existing.append(ipaddress.ip_network(destination, strict=False))
            except ValueError:
                pass
    for _ in range(1000):
        candidate = ipaddress.ip_network(f"10.{secrets.randbelow(180) + 40}.{secrets.randbelow(255)}.0/24")
        if all(not candidate.overlaps(item) for item in existing):
            return candidate
    raise RuntimeError("Could not find an unused isolated IPv4 subnet.")


def write_env(path: pathlib.Path, values: dict[str, str]) -> None:
    path.write_text("".join(f"{key}={value}\n" for key, value in values.items()), encoding="utf-8")
    path.chmod(0o600)


def make_tls(run_dir: pathlib.Path, app_host: str, id_host: str) -> tuple[pathlib.Path, pathlib.Path, str]:
    tls_dir = run_dir / "tls"
    ca_dir = run_dir / "ca"
    tls_dir.mkdir(mode=0o755)
    ca_dir.mkdir(mode=0o700)
    ca_key = ca_dir / "ca.key"
    ca_cert = ca_dir / "ca.crt"
    leaf_key = tls_dir / "tls.key"
    leaf_csr = tls_dir / "tls.csr"
    leaf_cert = tls_dir / "tls.crt"
    ext = tls_dir / "leaf.ext"
    run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
         "-keyout", str(ca_key), "-out", str(ca_cert), "-subj", "/CN=AGD003 disposable runner CA",
         "-addext", "basicConstraints=critical,CA:TRUE",
         "-addext", "keyUsage=critical,keyCertSign,cRLSign",
         "-addext", "subjectKeyIdentifier=hash"], capture=True)
    run(["openssl", "req", "-newkey", "rsa:2048", "-nodes", "-keyout", str(leaf_key),
         "-out", str(leaf_csr), "-subj", f"/CN={app_host}"], capture=True)
    ext.write_text(f"basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\n"
                   f"subjectAltName=DNS:{app_host},DNS:{id_host}\nextendedKeyUsage=serverAuth\n",
                   encoding="ascii")
    run(["openssl", "x509", "-req", "-in", str(leaf_csr), "-CA", str(ca_cert), "-CAkey", str(ca_key),
         "-CAcreateserial", "-out", str(leaf_cert), "-days", "1", "-sha256", "-extfile", str(ext)], capture=True)
    leaf_key.chmod(0o444)
    leaf_cert.chmod(0o444)
    (tls_dir / "ca.srl").unlink(missing_ok=True)
    leaf_csr.unlink(missing_ok=True)
    ext.unlink(missing_ok=True)
    spki_pem = subprocess.run(["openssl", "x509", "-in", str(leaf_cert), "-pubkey", "-noout"],
                              check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout
    pubkey = subprocess.run(["openssl", "pkey", "-pubin", "-outform", "DER"], input=spki_pem,
                            check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout
    pin = base64.b64encode(__import__("hashlib").sha256(pubkey).digest()).decode()
    verify = run(["openssl", "verify", "-CAfile", str(ca_cert), "-verify_hostname", app_host, str(leaf_cert)], capture=True)
    if not verify.stdout.strip().endswith(": OK"):
        raise RuntimeError("OpenSSL certificate chain/hostname verification failed.")
    verify_id = run(["openssl", "verify", "-CAfile", str(ca_cert), "-verify_hostname", id_host, str(leaf_cert)], capture=True)
    if not verify_id.stdout.strip().endswith(": OK"):
        raise RuntimeError("OpenSSL Keycloak certificate hostname verification failed.")
    return tls_dir, ca_cert, pin


def realm_json(app_origin: str, client_secret: str, admin_sub: str, admin_password: str,
               unlinked_sub: str, unlinked_password: str) -> dict:
    def user(sub: str, username: str, password: str) -> dict:
        return {"id": sub, "username": username, "enabled": True, "email": f"{username}@staging.invalid",
                "emailVerified": True, "firstName": "Staging", "lastName": "Operator",
                "credentials": [{"type": "password", "value": password, "temporary": False}],
                "requiredActions": []}
    return {
        "realm": "agendamento", "enabled": True, "sslRequired": "external",
        "registrationAllowed": False, "loginWithEmailAllowed": False,
        "clients": [{
            "clientId": "agendamento-backend", "name": "AGD-003 disposable backend",
            "enabled": True, "protocol": "openid-connect", "publicClient": False,
            "clientAuthenticatorType": "client-secret", "secret": client_secret,
            "standardFlowEnabled": True, "directAccessGrantsEnabled": False,
            "implicitFlowEnabled": False, "serviceAccountsEnabled": False,
            "redirectUris": [app_origin + "/login/oauth2/code/keycloak"],
            "webOrigins": [app_origin],
            "attributes": {"pkce.code.challenge.method": "S256"},
        }],
        "users": [user(admin_sub, "staging-admin", admin_password),
                  user(unlinked_sub, "staging-unlinked", unlinked_password)],
    }


def compose(env_file: pathlib.Path, *args: str) -> list[str]:
    return ["docker", "compose", "--project-name", PROJECT, "--project-directory", str(ROOT),
            "--file", str(COMPOSE_FILE), "--env-file", str(env_file), *args]


def wait_for(predicate, description: str, timeout: int = 360) -> None:
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            last = predicate()
            if last:
                print(f"PASS: {description}", flush=True)
                return
        except Exception:
            pass
        time.sleep(2)
    raise RuntimeError(f"Timed out waiting for {description}; last observation: {last!r}")


def main() -> int:
    arch = run(["docker", "info", "--format", "{{.OSType}}/{{.Architecture}}"], capture=True).stdout.strip()
    if arch != "linux/x86_64":
        raise RuntimeError(f"The pinned Keycloak JRE is amd64-only; this runner is {arch}.")
    run_dir = pathlib.Path(tempfile.mkdtemp(prefix=f"{PROJECT}-", dir="/tmp"))
    env_file = run_dir / "staging.env"
    global CLEANUP_DIR, CLEANUP_ENV
    CLEANUP_DIR, CLEANUP_ENV = run_dir, env_file
    atexit.register(cleanup_project)
    app_host = f"app-{secrets.token_hex(3)}.staging.test"
    id_host = f"id-{secrets.token_hex(3)}.staging.test"
    port = free_port()
    subnet = unused_subnet()
    ingress_subnet = unused_subnet((subnet,))
    # Docker's dynamic allocator starts near the network gateway; reserve the
    # final usable address so earlier services cannot take the trusted proxy IP.
    proxy_ip = subnet.broadcast_address - 1
    values: dict[str, str] = {
        "STAGING_PROJECT": PROJECT,
        "APP_HOST": app_host, "KEYCLOAK_HOST": id_host,
        "STAGING_HTTPS_PORT": str(port), "STAGING_BIND_ADDRESS": "127.0.0.1",
        "STAGING_SUBNET": str(subnet), "STAGING_PROXY_IP": str(proxy_ip),
        "STAGING_INGRESS_SUBNET": str(ingress_subnet),
        "STAGING_PROXY_CIDR": f"{proxy_ip}/32",
        "STAGING_TLS_DIR": str(run_dir / "tls"),
        "STAGING_REALM_FILE": str(run_dir / "realm.json"),
        "STAGING_POSTGRES_IMAGE": POSTGRES_IMAGE,
        "STAGING_KEYCLOAK_IMAGE": f"kronos-staging-{PROJECT}-keycloak:run",
        "STAGING_BACKEND_IMAGE": f"kronos-staging-{PROJECT}-backend:run",
        "STAGING_EDGE_IMAGE": f"kronos-staging-{PROJECT}-edge:run",
    }
    values.update({key: secrets.token_urlsafe(36) for key in (
        "STAGING_APP_DB_PASSWORD", "STAGING_APP_DB_MIGRATOR_PASSWORD", "STAGING_KEYCLOAK_DB_PASSWORD",
        "STAGING_KEYCLOAK_BOOTSTRAP_PASSWORD", "OIDC_CLIENT_SECRET", "STAGING_ADMIN_PASSWORD", "STAGING_UNLINKED_PASSWORD")})
    values["PUBLIC_BOOKING_HMAC_ACTIVE_KEY_ID"] = "staging-v1"
    values["PUBLIC_BOOKING_HMAC_KEYS"] = "staging-v1=" + base64.b64encode(secrets.token_bytes(32)).decode("ascii")
    values["STAGING_KEYCLOAK_BOOTSTRAP_USER"] = "temporary-staging-bootstrap"
    app_origin = f"https://{app_host}:{port}"
    issuer_origin = f"https://{id_host}:{port}"
    issuer = issuer_origin + "/realms/agendamento"
    values.update({
        "APP_ORIGIN": app_origin, "OIDC_ISSUER_ORIGIN": issuer_origin,
        "OIDC_ISSUER_URI": issuer,
        "OIDC_AUTHORIZATION_URI": issuer + "/protocol/openid-connect/auth",
        "OIDC_TOKEN_URI": "http://keycloak:8080/realms/agendamento/protocol/openid-connect/token",
        "OIDC_JWK_SET_URI": "http://keycloak:8080/realms/agendamento/protocol/openid-connect/certs",
        "OIDC_USER_INFO_URI": "http://keycloak:8080/realms/agendamento/protocol/openid-connect/userinfo",
        "OIDC_CLIENT_ID": "agendamento-backend",
        "OIDC_REDIRECT_URI": app_origin + "/login/oauth2/code/keycloak",
        "APP_FRONTEND_URL": app_origin + "/admin",
    })
    SECRETS.extend(values[key] for key in values if "PASSWORD" in key or "SECRET" in key or "HMAC_KEYS" in key)
    tls_dir, ca_cert, spki_pin = make_tls(run_dir, app_host, id_host)
    admin_sub, unlinked_sub = str(uuid.uuid4()), str(uuid.uuid4())
    (run_dir / "realm.json").write_text(json.dumps(realm_json(
        app_origin, values["OIDC_CLIENT_SECRET"], admin_sub, values["STAGING_ADMIN_PASSWORD"],
        unlinked_sub, values["STAGING_UNLINKED_PASSWORD"]), indent=2), encoding="utf-8")
    values["STAGING_TLS_DIR"] = str(tls_dir)
    write_env(env_file, values)

    cleanup_error = None
    try:
        print(f"Disposable project: {PROJECT}; application/identity hosts use synthetic .test names; secrets are redacted.", flush=True)
        run([str(ROOT / "scripts/staging/preflight.sh"), "--env-file", str(env_file), "--project-name", PROJECT, "--disposable"], capture=True)
        print("PASS: staging config preflight", flush=True)
        for invalid_name, change, expected in (
            ("non-loopback", {"STAGING_BIND_ADDRESS": "0.0.0.0"}, "disposable HTTPS runner may bind only loopback"),
            ("wrong-callback", {"OIDC_REDIRECT_URI": app_origin + "/unapproved"},
             "callback must exactly match the configured public application origin"),
        ):
            invalid_values = dict(values)
            invalid_values.update(change)
            invalid_env = run_dir / (invalid_name + ".env")
            write_env(invalid_env, invalid_values)
            rejected = subprocess.run(
                [str(ROOT / "scripts/staging/preflight.sh"), "--env-file", str(invalid_env),
                 "--project-name", PROJECT, "--disposable"], cwd=ROOT, text=True,
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
            output = rejected.stdout or ""
            if rejected.returncode == 0 or expected not in output or any(secret in output for secret in SECRETS):
                raise RuntimeError(f"Preflight negative case {invalid_name} was not rejected safely")
            print(f"PASS: preflight rejects {invalid_name} configuration without printing secrets", flush=True)
        rendered = json.loads(run(compose(env_file, "config", "--format", "json"), capture=True).stdout)
        edge_ports = rendered["services"]["edge"].get("ports", [])
        if not any(item.get("target") == 8443 and item.get("published") == str(port) and
                   item.get("host_ip") == "127.0.0.1" and item.get("mode") == "host" and
                   item.get("protocol") == "tcp" for item in edge_ports):
            raise RuntimeError("Rendered Compose config does not contain the allocated loopback HTTPS mapping")
        print("PASS: rendered Compose exposes only the allocated loopback HTTPS port", flush=True)
        run(compose(env_file, "build", "--pull", "keycloak", "app", "edge"), timeout=1200)
        for image_var in ("STAGING_KEYCLOAK_IMAGE", "STAGING_BACKEND_IMAGE", "STAGING_EDGE_IMAGE"):
            image_ref = values[image_var]
            image_id = run(["docker", "image", "inspect", "--format", "{{.Id}}", image_ref], capture=True).stdout.strip()
            print(f"Built image identity: {image_var}={image_id}", flush=True)
        try:
            run(compose(env_file, "up", "-d", "--wait", "--wait-timeout", "300", "--remove-orphans"), timeout=360)
            edge_id = run(compose(env_file, "ps", "-q", "edge"), capture=True).stdout.strip()
            if not edge_id:
                raise RuntimeError("Disposable edge container was not created")
            edge_inspect = json.loads(run(["docker", "inspect", edge_id], capture=True).stdout)[0]
            bindings = edge_inspect.get("HostConfig", {}).get("PortBindings", {}).get("8443/tcp") or []
            if not any(binding.get("HostIp") == "127.0.0.1" and int(binding.get("HostPort", "0")) == port
                       for binding in bindings):
                raise RuntimeError("Disposable HTTPS listener mapping mismatch: " + json.dumps({
                    "container": edge_inspect.get("Name"), "configuredPorts": edge_ports,
                    "containerPorts": edge_inspect.get("Config", {}).get("ExposedPorts"),
                    "requestedHostBindings": edge_inspect.get("HostConfig", {}).get("PortBindings"),
                    "runtimeNetworkBindings": edge_inspect.get("NetworkSettings", {}).get("Ports"),
                }, sort_keys=True))
            print("PASS: isolated HTTPS port mapping bound only to 127.0.0.1", flush=True)
        except RuntimeError:
            logs = subprocess.run(compose(env_file, "logs", "--no-color", "--tail", "120"), cwd=ROOT,
                                  text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30).stdout or ""
            edge_logs = subprocess.run(compose(env_file, "logs", "--no-color", "--tail", "100", "edge"), cwd=ROOT,
                                       text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30).stdout or ""
            for secret in SECRETS:
                logs = logs.replace(secret, "[redacted]")
                edge_logs = edge_logs.replace(secret, "[redacted]")
            print("Disposable edge startup logs:\n" + edge_logs[-6000:], file=sys.stderr)
            print("Disposable startup logs (credentials redacted):\n" + logs[-12000:], file=sys.stderr)
            raise

        def app_ready() -> bool:
            result = run(compose(env_file, "exec", "-T", "app", "curl", "-fsS", "http://localhost:8080/actuator/health/readiness"), capture=True, check=False, timeout=8)
            return result.returncode == 0 and '"status":"UP"' in result.stdout.replace(" ", "")
        wait_for(app_ready, "PostgreSQL migrations and application readiness")

        tenant_id = str(uuid.uuid4())
        slug = "agd003-" + secrets.token_hex(4)
        trace_id = str(uuid.uuid4())
        sql = f"""
BEGIN;
INSERT INTO agendamento.tenant (tenant_id, name, slug, timezone, status, version)
VALUES ('{tenant_id}', 'Disposable HTTPS Tenant', '{slug}', 'America/Sao_Paulo', 'ACTIVE', 0);
INSERT INTO agendamento.admin_user (user_id, identity_issuer, identity_subject, status)
VALUES ('{admin_sub}', '{issuer}', '{admin_sub}', 'ACTIVE');
INSERT INTO agendamento.admin_tenant_membership (user_id, tenant_id, role, status)
VALUES ('{admin_sub}', '{tenant_id}', 'TENANT_ADMIN', 'ACTIVE');
INSERT INTO agendamento.audit_event
  (event_id, tenant_id, actor_type, actor_ref, action, target_type, target_id, result,
   correlation_id, trace_id, details)
VALUES
  (gen_random_uuid(), '{tenant_id}', 'SYSTEM', 'CONTROLLED_TENANT_PROVISIONER', 'TENANT_CREATED',
   'TENANT', '{tenant_id}', 'COMMITTED', '{trace_id}', '{trace_id}',
   '{{"changed_fields":["name","slug","timezone"]}}'::jsonb),
  (gen_random_uuid(), '{tenant_id}', 'SYSTEM', 'CONTROLLED_DEMO_PROVISIONER', 'TENANT_ADMIN_LINKED',
   'ADMIN_TENANT_MEMBERSHIP', '{admin_sub}', 'COMMITTED', '{trace_id}', '{trace_id}',
   '{{"changed_fields":["identity","membership"]}}'::jsonb);
COMMIT;
"""
        db_env = os.environ.copy()
        db_env["PGPASSWORD"] = values["STAGING_APP_DB_MIGRATOR_PASSWORD"]
        run(compose(env_file, "exec", "-T", "app-db", "psql", "-X", "-q", "-v", "ON_ERROR_STOP=1",
                    "-U", "staging_migrator", "-d", "agendamento"), env=db_env, input_text=sql, capture=True)
        print("PASS: isolated tenant and active administrator fixture provisioned", flush=True)

        smoke_env = os.environ.copy()
        smoke_env.update({"STAGING_APP_ORIGIN": app_origin, "STAGING_ID_ORIGIN": issuer_origin,
                          "STAGING_ISSUER": issuer, "STAGING_HOST_PORT": str(port),
                          "STAGING_APP_HOST": app_host, "STAGING_ID_HOST": id_host,
                          "STAGING_CA_CERT": str(ca_cert), "STAGING_TLS_SPKI_PIN": spki_pin,
                          "STAGING_ADMIN_USER": "staging-admin", "STAGING_ADMIN_PASSWORD": values["STAGING_ADMIN_PASSWORD"],
                          "STAGING_UNLINKED_USER": "staging-unlinked", "STAGING_UNLINKED_PASSWORD": values["STAGING_UNLINKED_PASSWORD"],
                          "STAGING_TENANT_ID": tenant_id, "STAGING_ADMIN_SUBJECT": admin_sub,
                          "STAGING_PROJECT": PROJECT, "STAGING_REPO_ROOT": str(ROOT),
                          "STAGING_COMPOSE_FILE": str(COMPOSE_FILE), "STAGING_ENV_FILE": str(env_file),
                          "STAGING_PROXY_IP": str(proxy_ip),
                          "STAGING_KEYCLOAK_DB_PASSWORD": values["STAGING_KEYCLOAK_DB_PASSWORD"]})
        try:
            run(["python3", str(ROOT / "scripts/staging/smoke-https.py")], env=smoke_env, timeout=240)
        except RuntimeError:
            logs = subprocess.run(compose(env_file, "logs", "--no-color", "--tail", "100", "edge"), cwd=ROOT,
                                  text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30).stdout or ""
            for secret in SECRETS:
                logs = logs.replace(secret, "[redacted]")
            print("Disposable edge logs (credentials redacted):\n" + logs[-9000:], file=sys.stderr)
            raise
        print("PASS: disposable HTTPS/OIDC/session/authorization/CSRF/logout smoke", flush=True)
        print(f"PASS: exact tested images: {values['STAGING_KEYCLOAK_IMAGE']}, {values['STAGING_BACKEND_IMAGE']}, {values['STAGING_EDGE_IMAGE']}", flush=True)
        return 0
    finally:
        pass


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        message = str(error)
        for secret in SECRETS:
            message = message.replace(secret, "[redacted]")
        print(f"ERROR: {message}", file=sys.stderr)
        sys.exit(1)
