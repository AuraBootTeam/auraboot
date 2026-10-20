"""Capture an initialized CI image's internal contract; never bootstrap or persist credentials."""

import argparse
from hashlib import sha256
import importlib.util
import json
import os
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request

spec = importlib.util.spec_from_file_location("release_bootstrap", Path(__file__).with_name("release-bootstrap.py"))
bootstrap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bootstrap)


def capture(request, email, password, output, receipt):
    output, receipt = Path(output), Path(receipt)
    if not email or not password or output == receipt or output.exists() or receipt.exists():
        raise RuntimeError("Explicit credentials and fresh distinct output/receipt paths are required")
    status, body, _ = request("/api/auth/login", method="POST", body={"email": email, "password": password})
    if status != 200:
        raise RuntimeError("Internal contract administrator login failed")
    login = bootstrap.response_data(body, "internal contract administrator login")
    if not isinstance(login, dict) or not isinstance(login.get("jwt"), str) or not login["jwt"]:
        raise RuntimeError("Internal contract login returned no session")
    admin = {"Authorization": "Bearer " + login["jwt"]}
    try:
        bootstrap.capture_internal_document(request, admin, output)
    finally:
        status, body, _ = request("/api/user/sessions/current", method="DELETE", headers=admin)
        if status != 200:
            raise RuntimeError("Internal contract capture session revocation failed")
        bootstrap.response_data(body, "internal contract capture session revocation")
        status, _, _ = request("/api/auth/me", headers=admin)
        if status != 401:
            raise RuntimeError("Internal contract capture session remained authorized after revocation")
    result = {"schemaVersion": 1, "claim": "authenticated-internal-document-capture-only",
              "verdict": "pass", "documentSha256": sha256(output.read_bytes()).hexdigest(),
              "sessionRevoked": True, "identityAfterRevocation": 401, "bootstrapExecuted": False}
    with receipt.open("x", encoding="utf-8") as stream:
        json.dump(result, stream, indent=2, sort_keys=True)
        stream.write("\n")
    return result


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def transport(base_url):
    target = urllib.parse.urlsplit(base_url)
    if (target.scheme not in ("http", "https") or target.hostname not in ("127.0.0.1", "localhost", "::1")
            or target.username or target.password or target.path not in ("", "/") or target.query or target.fragment):
        raise RuntimeError("Internal contract capture is restricted to the admitted CI loopback image")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(path, method="GET", body=None, headers=None):
        payload = json.dumps(body).encode("utf-8") if body is not None else None
        request_headers = {"Accept": "application/json", **(headers or {})}
        if payload is not None:
            request_headers["Content-Type"] = "application/json"
        call = urllib.request.Request(base_url.rstrip("/") + path, data=payload, headers=request_headers, method=method)
        try:
            with opener.open(call, timeout=90) as response:
                return response.status, json.loads(response.read()), dict(response.headers)
        except urllib.error.HTTPError as error:
            # Response bodies may include account or credential details; never report them.
            return error.code, None, {}

    return request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--receipt", required=True)
    args = parser.parse_args()
    result = capture(transport(args.base_url), os.environ.get("ADMIN_EMAIL"), os.environ.get("ADMIN_PASSWORD"), args.output, args.receipt)
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    main()
