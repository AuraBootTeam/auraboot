"""Obtain a business-space session for Core artifact deployment operations."""

import json
import os
import stat
from pathlib import Path
import sys
import urllib.error
import urllib.request


def response_data(body, stage):
    if not isinstance(body, dict) or str(body.get("code")) != "0":
        raise RuntimeError(f"{stage}: unsuccessful API envelope")
    return body.get("data")


def business_session(request, email, password, tenant_id=None):
    login = response_data(request("/api/auth/login", method="POST", body={
        "email": email, "password": password,
    }), "administrator login")
    if not isinstance(login, dict) or not login.get("jwt"):
        raise RuntimeError("Administrator login returned no session")
    headers = {"Authorization": "Bearer " + login["jwt"]}
    spaces = response_data(request("/api/tenant-selection/my-spaces", headers=headers), "administrator spaces")
    if not isinstance(spaces, list):
        raise RuntimeError("Administrator spaces returned no inventory")
    business = [space for space in spaces if isinstance(space, dict)
                and space.get("spaceType") == "business" and space.get("tenantId") is not None
                and (tenant_id is None or str(space["tenantId"]) == str(tenant_id))]
    if len(business) != 1:
        raise RuntimeError("Select exactly one business space with AURA_ADMIN_TENANT_ID")
    selected = response_data(request("/api/tenant-selection/process", method="POST", headers=headers,
        body={"action": "select", "tenantId": business[0]["tenantId"]}), "business space selection")
    if not isinstance(selected, dict) or not selected.get("jwt"):
        raise RuntimeError("Business space selection returned no session")
    if str(selected.get("tenantId")) != str(business[0]["tenantId"]):
        raise RuntimeError("Business space selection returned a different tenant")
    return selected["jwt"]



def cached_business_session(request, email, password, filename, base, tenant_id=None):
    """Reuse one explicitly selected runtime session; invalid saved sessions fail closed."""
    path = Path(filename)
    if not path.is_absolute():
        raise RuntimeError("Administrator session path must be absolute")
    saved = None
    if path.exists() or path.is_symlink():
        info = path.lstat()
        if not stat.S_ISREG(info.st_mode) or stat.S_IMODE(info.st_mode) != 0o600 or info.st_uid != os.geteuid():
            raise RuntimeError("Administrator session must be an owned private regular file")
        if info.st_size > 65536:
            raise RuntimeError("Administrator session exceeds its size limit")
        saved = json.loads(path.read_text())
        if (not isinstance(saved, dict) or set(saved) != {"backendUrl", "email", "tenantId", "userId", "jwt"}
                or saved["backendUrl"] != base or saved["email"] != email
                or not isinstance(saved["jwt"], str) or not saved["jwt"]
                or tenant_id is not None and str(saved["tenantId"]) != str(tenant_id)):
            raise RuntimeError("Administrator session does not match this runtime and actor")
        token = saved["jwt"]
    else:
        token = business_session(request, email, password, tenant_id)
    data = response_data(request("/api/auth/me", headers={"Authorization": "Bearer " + token}), "administrator identity")
    user = data.get("user") if isinstance(data, dict) else None
    if (not isinstance(user, dict) or not user.get("id") or not user.get("tenantId")
            or user.get("email") != email
            or tenant_id is not None and str(user["tenantId"]) != str(tenant_id)):
        raise RuntimeError("Administrator identity does not match the selected business session")
    record = {"backendUrl": base, "email": email, "tenantId": str(user["tenantId"]),
              "userId": str(user["id"]), "jwt": token}
    if saved is not None:
        if record != saved:
            raise RuntimeError("Saved administrator identity changed")
    else:
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w") as stream:
            json.dump(record, stream)
            stream.flush()
            os.fsync(stream.fileno())
    return token


def main():
    base = os.environ["AURA_CORE_BASE_URL"].rstrip("/")

    def request(path, method="GET", body=None, headers=None):
        merged = dict(headers or {})
        payload = None
        if body is not None:
            payload = json.dumps(body).encode()
            merged["Content-Type"] = "application/json"
        req = urllib.request.Request(base + path, data=payload, headers=merged, method=method)
        with urllib.request.urlopen(req, timeout=90) as response:
            return json.load(response)

    try:
        cache = os.environ.get("AURA_ADMIN_SESSION_FILE")
        if cache:
            token = cached_business_session(request, os.environ["ADMIN_EMAIL"], os.environ["ADMIN_PASSWORD"],
                cache, base, os.environ.get("AURA_ADMIN_TENANT_ID") or None)
        else:
            token = business_session(request, os.environ["ADMIN_EMAIL"], os.environ["ADMIN_PASSWORD"],
                os.environ.get("AURA_ADMIN_TENANT_ID") or None)
    except (RuntimeError, urllib.error.URLError, ValueError, KeyError, OSError) as failure:
        # Never print a response envelope, credential or token on failure.
        print(f"Core administrator session failed: {failure}", file=sys.stderr)
        return 1
    print(token)
    return 0


if __name__ == "__main__":
    sys.exit(main())
