"""Initialize a fresh release image exclusively through public production APIs."""

import secrets
import json
from pathlib import Path


def provision_replay_actor_commands(request, admin):
    """Install the declared DSL dependencies for the restricted replay actor."""
    for plugin in ("core-meta", "org-management", "platform-admin"):
        _, body, _ = request("/api/plugins/import/import-directory-sync", method="POST", headers=admin,
                             body={"path": "/plugins/" + plugin, "conflictStrategy": "OVERWRITE",
                                   "validateReferences": True, "autoDeployProcesses": True,
                                   "autoPublishModels": True, "autoPublishFields": True,
                                   "autoPublishCommands": True, "autoPublishPages": True,
                                   "createResourcePermissions": True})
        if not isinstance(body, dict) or body.get("success") is not True:
            raise RuntimeError("Replay actor plugin import did not succeed: " + plugin)
    _, body, _ = request("/api/meta/commands?modelCode=tenant_member", headers=admin)
    commands = response_data(body, "replay actor command catalog")
    if not isinstance(commands, list) or not any(isinstance(row, dict) and row.get("code") == "admin:create_member"
                                               for row in commands):
        raise RuntimeError("Replay actor member command was not published")


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


def capture_internal_document(request, admin, destination):
    """Capture authenticated documentation without persisting the administrator session."""
    if not isinstance(admin, dict) or not isinstance(admin.get("Authorization"), str) or not admin["Authorization"].startswith("Bearer "):
        raise RuntimeError("Internal contract capture requires the bootstrap administrator session")
    status, document, _ = request("/v3/api-docs/internal-web-admin", headers=admin)
    if (status != 200 or not isinstance(document, dict)
            or not str(document.get("openapi", "")).startswith("3.")
            or not isinstance(document.get("paths"), dict) or not document["paths"]):
        raise RuntimeError("Authenticated internal contract capture returned no OpenAPI document")
    # Semantic comparison is the subsequent Node drift gate, not this raw capture.
    with Path(destination).open("x", encoding="utf-8") as output:
        json.dump(document, output, sort_keys=True)
        output.write("\n")
