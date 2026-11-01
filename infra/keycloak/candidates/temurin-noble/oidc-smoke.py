#!/usr/bin/env python3
"""Disposable Keycloak authorization-code login test; stdlib only."""

import base64
import html
import http.cookiejar
import json
import sys
import time
import ssl
import urllib.error
import urllib.parse
import urllib.request
import secrets
from html.parser import HTMLParser


ISSUER_REALM = "temurin-smoke"
CLIENT_ID = "oidc-smoke"
CLIENT_SECRET = "synthetic-only-client-secret"
USERNAME = "synthetic-user"
PASSWORD = "synthetic-only-user-password"


class LoginForm(HTMLParser):
    def __init__(self):
        super().__init__()
        self.in_login_form = False
        self.action = None
        self.inputs = {}

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "form" and attrs.get("id") == "kc-form-login":
            self.in_login_form = True
            self.action = attrs.get("action")
        elif self.in_login_form and tag == "input" and attrs.get("name"):
            self.inputs[attrs["name"]] = attrs.get("value", "")

    def handle_endtag(self, tag):
        if tag == "form" and self.in_login_form:
            self.in_login_form = False


class VisibleText(HTMLParser):
    def __init__(self):
        super().__init__()
        self.hidden = 0
        self.parts = []

    def handle_starttag(self, tag, attrs):
        if tag in ("script", "style"):
            self.hidden += 1

    def handle_endtag(self, tag):
        if tag in ("script", "style") and self.hidden:
            self.hidden -= 1

    def handle_data(self, data):
        if not self.hidden:
            text = " ".join(data.split())
            if text:
                self.parts.append(text)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def request_json(opener, url, data=None, headers=None, timeout=5):
    request = urllib.request.Request(url, data=data, headers=headers or {})
    with opener.open(request, timeout=timeout) as response:
        return response.status, json.load(response)


def decode_jwt_payload(token):
    payload = token.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    return json.loads(base64.urlsafe_b64decode(payload))


def main(base):
    base = base.rstrip("/")
    realm_url = f"{base}/realms/{ISSUER_REALM}"
    cookies = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(cookies),
        urllib.request.HTTPSHandler(context=ssl._create_unverified_context()),
        NoRedirect(),
    )

    deadline = time.monotonic() + 120
    last_error = ""
    while time.monotonic() < deadline:
        try:
            status, discovery = request_json(
                opener, f"{realm_url}/.well-known/openid-configuration", timeout=3
            )
            if status == 200 and discovery.get("issuer") == realm_url:
                break
        except (OSError, urllib.error.URLError, json.JSONDecodeError) as exc:
            last_error = str(exc)
        time.sleep(1)
    else:
        raise RuntimeError(f"Keycloak did not become ready; last error: {last_error}")

    redirect_uri = sys.argv[2]
    state = secrets.token_urlsafe(18)
    nonce = secrets.token_urlsafe(18)
    authorize_query = urllib.parse.urlencode(
        {
            "client_id": CLIENT_ID,
            "redirect_uri": redirect_uri,
            "response_type": "code",
            "scope": "openid profile",
            "state": state,
            "nonce": nonce,
        }
    )
    authorize_url = f"{realm_url}/protocol/openid-connect/auth?{authorize_query}"
    with opener.open(authorize_url, timeout=10) as response:
        login_html = response.read().decode("utf-8")

    form = LoginForm()
    form.feed(login_html)
    if not form.action:
        raise RuntimeError("Keycloak login form was not present in the authorization response")
    action_url = urllib.parse.urljoin(authorize_url, form.action)
    if urllib.parse.urlparse(action_url).netloc != urllib.parse.urlparse(base).netloc:
        raise RuntimeError(
            "Authorization form action changed authority; refusing to POST outside the "
            "disposable candidate's loopback endpoint"
        )

    fields = dict(form.inputs)
    fields.update({"username": USERNAME, "password": PASSWORD})
    login_request = urllib.request.Request(
        action_url,
        data=urllib.parse.urlencode(fields).encode(),
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        method="POST",
    )
    try:
        opener.open(login_request, timeout=10)
        raise RuntimeError("Expected authorization response redirect after successful login")
    except urllib.error.HTTPError as response:
        if response.code not in (302, 303):
            body = response.read().decode("utf-8", errors="replace")
            visible = VisibleText()
            visible.feed(body)
            action_query = urllib.parse.parse_qs(
                urllib.parse.urlparse(urllib.parse.urljoin(authorize_url, form.action)).query
            )
            cookie_probe = urllib.request.Request(action_url)
            cookies.add_cookie_header(cookie_probe)
            cookie_names = sorted(
                part.split("=", 1)[0]
                for part in cookie_probe.get_header("Cookie", "").split("; ")
                if part
            )
            cookie_state = sorted(
                (cookie.name, cookie.domain, cookie.path, cookie.secure)
                for cookie in cookies
            )
            message = html.unescape(" ".join(visible.parts))[:220]
            raise RuntimeError(
                f"Login POST returned HTTP {response.code}; "
                f"form fields={sorted(fields)}; action parameter names={sorted(action_query)}; "
                f"cookie names sent={cookie_names}; cookie jar={cookie_state}; "
                f"visible response={message!r}"
            )
        location = response.headers.get("Location", "")

    callback = urllib.parse.urlparse(location)
    callback_query = urllib.parse.parse_qs(callback.query)
    code = callback_query.get("code", [None])[0]
    if callback.netloc and callback.netloc != urllib.parse.urlparse(redirect_uri).netloc:
        raise RuntimeError(
            "Authorization redirect did not reach the synthetic callback authority; "
            f"authority={callback.netloc!r}; path={callback.path}; "
            f"query names={sorted(callback_query)}; "
            f"error={callback_query.get('error', [''])[0]!r}; "
            f"error_description={callback_query.get('error_description', [''])[0]!r}"
        )
    if not code or callback_query.get("state", [None])[0] != state:
        raise RuntimeError(
            "Authorization redirect had no matching code/state; "
            f"path={callback.path}; query names={sorted(callback_query)}; "
            f"error={callback_query.get('error', [''])[0]!r}; "
            f"error_description={callback_query.get('error_description', [''])[0]!r}"
        )

    token_data = urllib.parse.urlencode(
        {
            "grant_type": "authorization_code",
            "client_id": CLIENT_ID,
            "client_secret": CLIENT_SECRET,
            "code": code,
            "redirect_uri": redirect_uri,
        }
    ).encode()
    token_status, tokens = request_json(
        opener,
        f"{realm_url}/protocol/openid-connect/token",
        data=token_data,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    id_claims = decode_jwt_payload(tokens["id_token"])
    if (
        token_status != 200
        or id_claims.get("iss") != realm_url
        or id_claims.get("nonce") != nonce
        or id_claims.get("preferred_username") != USERNAME
    ):
        raise RuntimeError("ID token claims did not match the synthetic OIDC login")

    userinfo_status, userinfo = request_json(
        opener,
        f"{realm_url}/protocol/openid-connect/userinfo",
        headers={"Authorization": f"Bearer {tokens['access_token']}"},
    )
    if userinfo_status != 200 or userinfo.get("sub") != id_claims.get("sub"):
        raise RuntimeError("Access token was not accepted by the userinfo endpoint")
    if userinfo.get("preferred_username") != USERNAME:
        raise RuntimeError("Userinfo did not resolve to the synthetic user")

    print(
        "OIDC authorization-code login passed: "
        f"issuer={realm_url}; user={userinfo['preferred_username']}; "
        "state, nonce, code exchange and userinfo verified"
    )


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: oidc-smoke.py <keycloak-base-url> <redirect-uri>")
    main(sys.argv[1])
