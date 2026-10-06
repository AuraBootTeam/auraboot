"""Initialize a fresh release image exclusively through public production APIs."""

import secrets


def response_data(body, stage):
    if not isinstance(body, dict) or str(body.get("code")) != "0":
        raise RuntimeError(f"{stage}: unsuccessful API envelope")
    return body.get("data")


def bootstrap_admin(request):
    _, body, _ = request("/api/bootstrap/status")
    status = response_data(body, "bootstrap status")
    if not isinstance(status, dict) or status.get("initialized") is not False or status.get("inProgress") is not False:
        raise RuntimeError("Release probe requires a fresh, uninitialized database")
    email = "release-probe@auraboot.test"
    password = "Release9!" + secrets.token_urlsafe(32)
    _, body, _ = request("/api/bootstrap/setup", method="POST", body={
        "companyName": "Release image protocol probe", "adminEmail": email,
        "adminPassword": password, "adminDisplayName": "Release probe administrator",
        "systemMode": "single",
    })
    setup = response_data(body, "bootstrap setup")
    if not isinstance(setup, dict) or setup.get("success") is not True or not setup.get("tenantId"):
        raise RuntimeError("Bootstrap did not create the business tenant")
    _, body, _ = request("/api/bootstrap/status")
    if response_data(body, "completed bootstrap status").get("initialized") is not True:
        raise RuntimeError("Bootstrap did not persist initialized state")
    _, body, _ = request("/api/auth/login", method="POST", body={"email": email, "password": password})
    login = response_data(body, "administrator login")
    if not isinstance(login, dict) or not login.get("jwt"):
        raise RuntimeError("Administrator login returned no session")
    admin = {"Authorization": "Bearer " + login["jwt"]}
    _, body, _ = request("/api/tenant-selection/my-spaces", headers=admin)
    spaces = response_data(body, "administrator spaces")
    business = [space for space in spaces if space.get("spaceType") == "business"
                and str(space.get("tenantId")) == str(setup["tenantId"])]
    if len(business) != 1:
        raise RuntimeError("Bootstrap administrator is not a member of the created business tenant")
    _, body, _ = request("/api/tenant-selection/process", method="POST", headers=admin,
                         body={"action": "select", "tenantId": business[0]["tenantId"]})
    selected = response_data(body, "business tenant selection")
    if not isinstance(selected, dict) or not selected.get("jwt"):
        raise RuntimeError("Business tenant selection returned no session")
    return {"Authorization": "Bearer " + selected["jwt"]}
