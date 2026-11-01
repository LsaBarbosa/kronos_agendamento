#!/usr/bin/env python3
"""HTTPS/OIDC smoke against the disposable AGD-003 Compose stack."""

from __future__ import annotations

import base64
import hashlib
import http.client
import json
import os
import pathlib
import urllib.parse
import secrets
import socket
import ssl
import struct
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request


def assert_true(value: bool, message: str) -> None:
    if not value:
        raise RuntimeError(message)


def request(method: str, host: str, port: int, path: str, ca: str, headers=None):
    context = ssl.create_default_context(cafile=ca)
    class ResolvedHTTPSConnection(http.client.HTTPSConnection):
        def connect(self):
            raw = socket.create_connection(("127.0.0.1", port), self.timeout)
            self.sock = self._context.wrap_socket(raw, server_hostname=tls_name)
    tls_name = headers.pop("__tls_name", host) if headers else host
    connection = ResolvedHTTPSConnection(tls_name, port, context=context, timeout=12)
    connection.putrequest(method, path, skip_host=True)
    connection.putheader("Host", host)
    for key, value in (headers or {}).items():
        connection.putheader(key, value)
    connection.endheaders()
    response = connection.getresponse()
    body = response.read()
    result = (response.status, dict(response.getheaders()), body)
    connection.close()
    return result


def wait_for_https(port: int, host: str, path: str, ca: str) -> None:
    deadline = time.monotonic() + 30
    last_error = "not attempted"
    while time.monotonic() < deadline:
        try:
            status, _, _ = request("GET", host, port, path, ca)
            if status == 200:
                print("PASS: host-published HTTPS listener became ready", flush=True)
                return
            last_error = f"HTTP {status}"
        except Exception as error:
            last_error = f"{type(error).__name__}: {error}"
        time.sleep(0.25)
    raise RuntimeError(f"HTTPS listener did not become ready within 30 seconds ({last_error})")


class CDP:
    def __init__(self, ws_url: str):
        parsed = urllib.parse.urlparse(ws_url)
        self.sock = socket.create_connection((parsed.hostname, parsed.port), timeout=15)
        key = base64.b64encode(secrets.token_bytes(16)).decode()
        request_text = (f"GET {parsed.path} HTTP/1.1\r\nHost: {parsed.hostname}:{parsed.port}\r\n"
                        f"Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: {key}\r\n"
                        "Sec-WebSocket-Version: 13\r\n\r\n")
        self.sock.sendall(request_text.encode())
        response = b""
        while b"\r\n\r\n" not in response:
            response += self.sock.recv(4096)
        header = response.split(b"\r\n", 1)[0]
        assert_true(b"101" in header, "Chrome DevTools WebSocket upgrade failed")
        self.sock.settimeout(15)
        self.seq = 0

    def _read_exact(self, size: int) -> bytes:
        data = b""
        while len(data) < size:
            chunk = self.sock.recv(size - len(data))
            if not chunk:
                raise RuntimeError("Chrome DevTools socket closed")
            data += chunk
        return data

    def _send(self, opcode: int, payload: bytes) -> None:
        mask = secrets.token_bytes(4)
        size = len(payload)
        if size < 126:
            header = bytes([0x80 | opcode, 0x80 | size])
        elif size <= 0xFFFF:
            header = bytes([0x80 | opcode, 0x80 | 126]) + struct.pack("!H", size)
        else:
            header = bytes([0x80 | opcode, 0x80 | 127]) + struct.pack("!Q", size)
        masked = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
        self.sock.sendall(header + mask + masked)

    def _recv(self) -> tuple[int, bytes]:
        first, second = self._read_exact(2)
        opcode = first & 0x0F
        size = second & 0x7F
        if size == 126:
            size = struct.unpack("!H", self._read_exact(2))[0]
        elif size == 127:
            size = struct.unpack("!Q", self._read_exact(8))[0]
        mask = self._read_exact(4) if second & 0x80 else b""
        payload = self._read_exact(size)
        if mask:
            payload = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
        return opcode, payload

    def command(self, method: str, params=None):
        self.seq += 1
        command_id = self.seq
        self._send(1, json.dumps({"id": command_id, "method": method, "params": params or {}}).encode())
        while True:
            opcode, payload = self._recv()
            if opcode == 9:
                self._send(10, payload)
                continue
            if opcode == 8:
                raise RuntimeError("Chrome DevTools closed")
            if opcode != 1:
                continue
            message = json.loads(payload)
            if message.get("id") == command_id:
                if "error" in message:
                    raise RuntimeError(f"Chrome DevTools command failed: {method}")
                return message.get("result", {})

    def evaluate(self, expression: str):
        result = self.command("Runtime.evaluate", {
            "expression": expression,
            "awaitPromise": True,
            "returnByValue": True,
            "userGesture": True,
        })
        remote = result.get("result", {})
        if remote.get("subtype") == "error" or "exceptionDetails" in result:
            raise RuntimeError("Browser-side smoke expression failed")
        return remote.get("value")

    def close(self):
        try:
            self._send(8, b"")
        except OSError:
            pass
        self.sock.close()


def wait_browser(cdp: CDP, predicate: str, label: str, timeout=45):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if cdp.evaluate(predicate):
                return
        except Exception:
            pass
        time.sleep(0.2)
    raise RuntimeError(f"Browser timed out waiting for {label}")


def new_browser(app_host: str, id_host: str, pin: str, profile: str):
    chrome = os.environ.get("STAGING_CHROME", "google-chrome")
    process = subprocess.Popen([
        chrome, "--headless=new", "--no-sandbox", "--disable-dev-shm-usage", "--disable-gpu",
        "--disable-background-networking", "--no-first-run", "--no-default-browser-check",
        "--remote-debugging-port=0", f"--user-data-dir={profile}",
        f"--host-resolver-rules=MAP {app_host} 127.0.0.1,MAP {id_host} 127.0.0.1",
        f"--ignore-certificate-errors-spki-list={pin}", "about:blank",
    ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    active = pathlib.Path(profile) / "DevToolsActivePort"
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline and process.poll() is None:
        if active.exists():
            lines = active.read_text(encoding="ascii").splitlines()
            port = int(lines[0])
            version = json.load(urllib.request.urlopen(f"http://127.0.0.1:{port}/json/version", timeout=3))
            target = urllib.request.Request(f"http://127.0.0.1:{port}/json/new?about:blank", method="PUT")
            tab = json.load(urllib.request.urlopen(target, timeout=5))
            cdp = CDP(tab["webSocketDebuggerUrl"])
            cdp.command("Page.enable")
            cdp.command("Runtime.enable")
            cdp.command("Network.enable")
            return process, cdp
        time.sleep(0.1)
    process.terminate()
    raise RuntimeError("Chrome DevTools did not become ready")


def browser_login(cdp: CDP, app_origin: str, username: str, password: str):
    cdp.command("Page.navigate", {"url": app_origin + "/admin"})
    wait_browser(cdp, "document.readyState === 'complete' && !!document.querySelector('.login-link')", "administrative login link")
    cdp.evaluate("document.querySelector('.login-link').click()")
    wait_browser(cdp, "document.readyState === 'complete' && !!document.querySelector('input[name=username]')", "Keycloak login form")
    cdp.evaluate("""(() => {
      const username = document.querySelector('input[name=username]');
      const password = document.querySelector('input[name=password]');
      if (!username || !password) throw new Error('login form inputs missing');
      username.value = %s;
      password.value = %s;
      username.dispatchEvent(new Event('input', {bubbles:true}));
      password.dispatchEvent(new Event('input', {bubbles:true}));
      document.querySelector('form').requestSubmit();
      return true;
    })()""" % (json.dumps(username), json.dumps(password)))
    wait_browser(cdp, "location.origin === %s && document.readyState === 'complete'" % json.dumps(app_origin), "OIDC callback")


def run_smoke() -> None:
    app_origin = os.environ["STAGING_APP_ORIGIN"]
    id_origin = os.environ["STAGING_ID_ORIGIN"]
    issuer = os.environ["STAGING_ISSUER"]
    app_host = os.environ["STAGING_APP_HOST"]
    id_host = os.environ["STAGING_ID_HOST"]
    port = int(os.environ["STAGING_HOST_PORT"])
    ca = os.environ["STAGING_CA_CERT"]
    pin = os.environ["STAGING_TLS_SPKI_PIN"]

    wait_for_https(port, id_host, "/realms/agendamento/.well-known/openid-configuration", ca)
    status, _, body = request("GET", id_host, port, "/realms/agendamento/.well-known/openid-configuration", ca)
    assert_true(status == 200, "Keycloak HTTPS discovery did not return 200")
    discovery = json.loads(body)
    assert_true(discovery.get("issuer") == issuer, "OIDC discovery issuer differs from configured HTTPS issuer")
    assert_true(discovery.get("authorization_endpoint") == issuer + "/protocol/openid-connect/auth",
                "Discovery authorization endpoint does not match the public HTTPS issuer")
    print("PASS: TLS chain/hostname and Keycloak discovery issuer", flush=True)
    curl = subprocess.run([
        "curl", "--fail", "--silent", "--show-error", "--cacert", ca,
        "--connect-timeout", "5", "--resolve", f"{id_host}:{port}:127.0.0.1",
        f"https://{id_host}:{port}/realms/agendamento/.well-known/openid-configuration",
    ], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, timeout=10)
    if curl.returncode != 0:
        raise RuntimeError("curl TLS verification failed for Keycloak discovery")
    curl_discovery = json.loads(curl.stdout)
    assert_true(curl_discovery.get("issuer") == issuer,
                "curl-verified discovery issuer differs from configured HTTPS issuer")
    print("PASS: curl --cacert verifies TLS chain and hostname using the runner-only CA", flush=True)

    status, headers, _ = request("GET", app_host, port, "/oauth2/authorization/keycloak", ca,
                                 {"X-Forwarded-Host": "attacker.invalid", "X-Forwarded-Proto": "http",
                                  "Forwarded": "host=attacker.invalid;proto=http"})
    location = headers.get("Location") or headers.get("location") or ""
    redirect = urllib.parse.urlparse(location)
    query = urllib.parse.parse_qs(redirect.query)
    redirect_ok = (status in (302, 303) and redirect.scheme == "https" and
                   redirect.netloc == f"{id_host}:{port}" and
                   redirect.path == "/realms/agendamento/protocol/openid-connect/auth" and
                   query.get("client_id") == ["agendamento-backend"] and
                   query.get("redirect_uri") == [app_origin + "/login/oauth2/code/keycloak"] and
                   query.get("code_challenge_method") == ["S256"])
    assert_true(redirect_ok,
                "OIDC redirect mismatch (minimized): " + json.dumps({
                    "status": status, "scheme": redirect.scheme, "netloc": redirect.netloc,
                    "path": redirect.path, "queryKeys": sorted(query),
                    "clientIdMatches": query.get("client_id") == ["agendamento-backend"],
                    "callbackMatches": query.get("redirect_uri") == [app_origin + "/login/oauth2/code/keycloak"],
                    "pkceS256": query.get("code_challenge_method") == ["S256"],
                }, sort_keys=True))
    print("PASS: exact HTTPS OIDC authorization redirect/callback and PKCE S256", flush=True)

    status, _, body = request("GET", app_host, port, "/api/v1/admin/session", ca,
                              {"X-Forwarded-Host": "attacker.invalid", "X-Forwarded-Proto": "http", "Forwarded": "host=attacker.invalid;proto=http"})
    assert_true(status == 401, "Unauthenticated administrative session was not rejected with 401")
    assert_true(len(body) < 512, "Unauthenticated API error response unexpectedly large")
    status, _, body = request("GET", "untrusted.invalid", port, "/admin", ca, {"__tls_name": app_host})
    assert_true(status == 421 and not body, "Unknown Host was not rejected without content")
    status, _, body = request("GET", id_host, port, "/realms/agendamento/.well-known/openid-configuration", ca,
                              {"X-Forwarded-Host": "attacker.invalid", "X-Forwarded-Proto": "http", "X-Forwarded-Port": "80"})
    assert_true(status == 200 and json.loads(body).get("issuer") == issuer,
                "Spoofed forwarded headers changed Keycloak's fixed issuer")
    print("PASS: Host allowlist, forwarded-header spoof rejection, unauthenticated admin API", flush=True)

    def stop_browser(browser, cdp, profile):
        cdp.close()
        browser.terminate()
        try:
            browser.wait(timeout=5)
        except subprocess.TimeoutExpired:
            browser.kill()
        import shutil
        shutil.rmtree(profile, ignore_errors=True)

    # First clean browser profile proves the standard login, cookie, CSRF, and logout path.
    profile = tempfile.mkdtemp(prefix="agd003-browser-")
    browser, cdp = new_browser(app_host, id_host, pin, profile)
    try:
        browser_login(cdp, app_origin, os.environ["STAGING_ADMIN_USER"], os.environ["STAGING_ADMIN_PASSWORD"])
        wait_browser(cdp, "document.body.innerText.includes('Sessão administrativa ativa.')", "authorized administrative session")
        session = cdp.evaluate("fetch('/api/v1/admin/session',{credentials:'same-origin',cache:'no-store'}).then(async r=>({status:r.status,body:await r.text()}))")
        session_body = json.loads(session["body"])
        assert_true(session["status"] == 200 and session_body.get("authenticated") is True and
                    session_body.get("tenantId") == os.environ["STAGING_TENANT_ID"],
                    "OIDC session did not resolve to the server-associated tenant")
        cookies = cdp.command("Network.getAllCookies").get("cookies", [])
        cookie = next((item for item in cookies if item.get("name") == "AGDSESSION" and item.get("domain", "").lstrip(".") == app_host), None)
        assert_true(cookie is not None and cookie.get("secure") and cookie.get("httpOnly") and cookie.get("sameSite") == "Lax",
                    "Administrative session cookie does not preserve Secure/HttpOnly/SameSite=Lax")
        print("PASS: Authorization Code + PKCE OIDC, active tenant association, Secure HttpOnly SameSite=Lax cookie", flush=True)

        csrf_probe = cdp.evaluate("""(async()=>{
          const noToken=await fetch('/api/v1/admin/logout',{method:'POST',credentials:'same-origin'});
          const bad=await fetch('/api/v1/admin/logout',{method:'POST',credentials:'same-origin',headers:{'X-CSRF-TOKEN':'invalid'}});
          const tokenResponse=await fetch('/api/v1/csrf',{credentials:'same-origin',cache:'no-store'});
          const csrf=await tokenResponse.json();
          const valid=await fetch('/api/v1/admin/logout',{method:'POST',credentials:'same-origin',headers:{[csrf.headerName]:csrf.token}});
          return {noToken:noToken.status,bad:bad.status,valid:valid.status};
        })()""")
        assert_true(csrf_probe.get("noToken") == 403 and csrf_probe.get("bad") == 403,
                    "Logout without a valid CSRF token was not rejected")
        assert_true(csrf_probe.get("valid") == 200, "Logout with valid CSRF did not complete its frontend redirect")
        post_logout = cdp.evaluate("fetch('/api/v1/admin/session',{credentials:'same-origin',cache:'no-store'}).then(r=>r.status)")
        assert_true(post_logout == 401, "Session remained usable after CSRF-protected logout")
        print("PASS: CSRF rejection and local logout revoke the authenticated session", flush=True)
    finally:
        stop_browser(browser, cdp, profile)

    # A second fresh browser avoids inheriting the first browser's Keycloak SSO.
    revocation_profile = tempfile.mkdtemp(prefix="agd003-revocation-browser-")
    browser, cdp = new_browser(app_host, id_host, pin, revocation_profile)
    try:
        browser_login(cdp, app_origin, os.environ["STAGING_ADMIN_USER"], os.environ["STAGING_ADMIN_PASSWORD"])
        wait_browser(cdp, "document.body.innerText.includes('Sessão administrativa ativa.')", "session before association revocation")
        revoke_sql = "UPDATE agendamento.admin_tenant_membership SET status='DISABLED' WHERE user_id='" + os.environ["STAGING_ADMIN_SUBJECT"] + "';"
        revoke = subprocess.run(["docker", "compose", "--project-name", os.environ["STAGING_PROJECT"],
                                 "--project-directory", os.environ["STAGING_REPO_ROOT"], "--file",
                                 os.environ["STAGING_COMPOSE_FILE"], "--env-file", os.environ["STAGING_ENV_FILE"],
                                 "exec", "-T", "app-db", "psql", "-X", "-q", "-v", "ON_ERROR_STOP=1",
                                 "-U", "staging_migrator", "-d", "agendamento"], input=revoke_sql, text=True,
                                stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, env=os.environ.copy(), timeout=20)
        assert_true(revoke.returncode == 0, "Could not perform disposable association-revocation fixture")
        first = cdp.evaluate("fetch('/api/v1/admin/session',{credentials:'same-origin',cache:'no-store'}).then(r=>r.status)")
        second = cdp.evaluate("fetch('/api/v1/admin/session',{credentials:'same-origin',cache:'no-store'}).then(r=>r.status)")
        assert_true(first == 403 and second == 401, "Revoked association did not invalidate current and subsequent requests")
        print("PASS: disabling the server-side tenant association revokes the live JDBC session", flush=True)
    finally:
        stop_browser(browser, cdp, revocation_profile)

    unlinked_profile = tempfile.mkdtemp(prefix="agd003-unlinked-browser-")
    browser2, cdp2 = new_browser(app_host, id_host, pin, unlinked_profile)
    try:
        browser_login(cdp2, app_origin, os.environ["STAGING_UNLINKED_USER"], os.environ["STAGING_UNLINKED_PASSWORD"])
        status = cdp2.evaluate("fetch('/api/v1/admin/session',{credentials:'same-origin',cache:'no-store'}).then(r=>r.status)")
        assert_true(status in (401, 403), "OIDC user without an active database association received administrative access")
        assert_true("Sessão administrativa ativa." not in (cdp2.evaluate("document.body.innerText") or ""),
                    "Unassociated OIDC identity rendered an authenticated administration state")
        print("PASS: OIDC login without a server-side association is denied", flush=True)
    finally:
        stop_browser(browser2, cdp2, unlinked_profile)


if __name__ == "__main__":
    run_smoke()
