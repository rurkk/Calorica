#!/usr/bin/env python3
"""First-auth regression for a fresh disposable local backend; no retries of mutations."""
import argparse
import json
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--url", required=True, help="Fresh local backend after readiness; no preceding auth calls")
args = parser.parse_args()
base = args.url.rstrip("/")
parsed = urllib.parse.urlparse(base)
if parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost", "::1") or parsed.path:
    parser.error("Only a disposable backend on a loopback HTTP URL is allowed")
class NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirects())
evidence = []

def request(method, path, expected, body=None, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(base + path, method=method, headers=headers,
        data=None if body is None else json.dumps(body).encode())
    start = time.monotonic()
    try:
        response = opener.open(req, timeout=15)
    except urllib.error.HTTPError as failure:
        response = failure
    with response:
        raw = response.read()
        status = response.status
        correlation = response.headers.get("X-Request-Id")
    seconds = round(time.monotonic() - start, 3)
    evidence.append({"method": method, "path": path, "status": status,
        "seconds": seconds, "correlationId": correlation})
    assert status == expected, f"Expected {expected}, got {status} for {method} {path}; no retry"
    assert seconds < 8, f"HTTP budget exceeded for {method} {path}: {seconds}s"
    return json.loads(raw) if raw else None

try:
    request("GET", "/actuator/health/readiness", 200)
    credentials = {"email": "cold-auth-" + uuid.uuid4().hex + "@example.org", "password": secrets.token_urlsafe(24)}
    first = request("POST", "/api/v1/accounts/register", 201, credentials)
    assert first["account"]["role"] == "USER"
    assert first["account"]["email"] == credentials["email"]
    assert all(value is None for value in first["account"]["profile"].values())
    assert first["tokenType"] == "Bearer"
    login = request("POST", "/api/v1/accounts/login", 200, credentials)
    next_tokens = request("POST", "/api/v1/accounts/refresh", 200, {"refreshToken": first["refreshToken"]})
    assert next_tokens["sessionExpiresAt"] == first["sessionExpiresAt"]
    assert next_tokens["refreshToken"] != first["refreshToken"]
    me = request("GET", "/api/v1/accounts/me", 200, token=next_tokens["accessToken"])
    assert me["id"] == first["account"]["id"]
    request("PATCH", "/api/v1/profile", 200, {"calorieTarget": "2000.25"}, token=next_tokens["accessToken"])
    request("POST", "/api/v1/accounts/refresh", 401, {"refreshToken": first["refreshToken"]})
    request("GET", "/api/v1/accounts/me", 401, token=next_tokens["accessToken"])
    request("POST", "/api/v1/accounts/logout", 204, token=login["accessToken"])
    request("GET", "/api/v1/accounts/me", 401, token=login["accessToken"])
finally:
    # No response bodies, email, password or tokens in evidence.
    print(json.dumps(evidence, indent=2))
