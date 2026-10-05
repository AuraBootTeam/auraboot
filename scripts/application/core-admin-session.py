"""Obtain a business-space session for Core artifact deployment operations."""

import json
import os
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
        token = business_session(request, os.environ["ADMIN_EMAIL"], os.environ["ADMIN_PASSWORD"],
            os.environ.get("AURA_ADMIN_TENANT_ID") or None)
    except (RuntimeError, urllib.error.URLError, ValueError, KeyError) as failure:
        # Never print a response envelope, credential or token on failure.
        print(f"Core administrator session failed: {failure}", file=sys.stderr)
        return 1
    print(token)
    return 0


if __name__ == "__main__":
    sys.exit(main())
