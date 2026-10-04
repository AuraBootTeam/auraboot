#!/usr/bin/env python3
"""Release-image HTTP probe: seed, install two real plugins, provision OAuth, then exercise v1."""

import json
import os
import urllib.parse

from open_platform_http import Client, data

BASE = os.environ.get("BASE_URL", "http://app:6443").rstrip("/")
ARTIFACT = os.environ.get("CREDENTIAL_ARTIFACT", "/artifacts/open-platform-credentials.json")


def main():
    request = Client(BASE).request
    _, seed, _ = request("/api/test/seed?testRunId=open-platform-release-image", method="POST", body={})
    jwt = data(seed)["jwt"]
    admin = {"Authorization": f"Bearer {jwt}"}

    for template in ("asset-management", "simple-inventory"):
        _, installed, _ = request(f"/api/templates/{template}/install", method="POST", body={}, headers=admin)
        result = data(installed)
        if isinstance(result, dict) and result.get("success") is False:
            raise RuntimeError(f"template {template} failed: {result}")

    _, application_body, _ = request(
        "/api/open-platform/applications", method="POST",
        body={"name": "Release image protocol probe", "description": "ephemeral CI only"}, headers=admin,
    )
    application = data(application_body)
    scopes = [
        "assets.read", "assets.manage", "inventory.stock-ins.read", "inventory.stock-ins.manage",
        "openapi.events.read", "openapi.profile.read", "automation.events.write",
    ]
    _, installation_body, _ = request(
        f"/api/open-platform/applications/{application['pid']}/installations", method="POST",
        body={"environment": "production", "scopes": scopes, "rateLimitPerMinute": 100000}, headers=admin,
    )
    installation = data(installation_body)
    _, credential_body, _ = request(
        f"/api/open-platform/installations/{installation['pid']}/credentials", method="POST", body={}, headers=admin,
    )
    credential = data(credential_body)

    _, token, _ = request("/oauth2/token", method="POST", form={
        "grant_type": "client_credentials",
        "client_id": credential["clientId"],
        "client_secret": credential["clientSecret"],
        "scope": " ".join(scopes),
    })
    machine = {"Authorization": f"Bearer {token['access_token']}"}


    def dynamic_create(model, fields):
        _, body, _ = request(f"/api/dynamic/{model}/create", method="POST", body=fields, headers=admin)
        return data(body)


    asset1 = dynamic_create("tasset_asset", {
        "tasset_as_code": "REL-ASSET-001", "tasset_as_name": "Release Probe Asset 1",
        "tasset_as_status": "available",
    })
    dynamic_create("tasset_asset", {
        "tasset_as_code": "REL-ASSET-002", "tasset_as_name": "Release Probe Asset 2",
        "tasset_as_status": "available",
    })
    product = dynamic_create("tinv_product", {
        "tinv_pd_code": "REL-PRODUCT-001", "tinv_pd_name": "Release Probe Product",
    })
    warehouse = dynamic_create("tinv_warehouse", {
        "tinv_wh_code": "REL-WAREHOUSE-001", "tinv_wh_name": "Release Probe Warehouse",
    })
    stock_in = dynamic_create("tinv_stock_in", {
        "tinv_si_code": "REL-STOCK-IN-001",
        "tinv_si_product_id": product["pid"], "tinv_si_warehouse_id": warehouse["pid"],
        "tinv_si_quantity": 3, "tinv_si_unit_cost": 12.5, "tinv_si_supplier": "CI fixture",
    })

    _, asset, asset_headers = request(f"/api/open/v1/resources/assets/{asset1['pid']}", headers=machine)
    asset_etag = asset_headers.get("ETag") or asset_headers.get("Etag")
    if not asset_etag or "row_version" in asset or "tasset_as_name" in asset:
        raise RuntimeError("asset projection or strong ETag contract failed")
    command_headers = dict(machine, **{"Idempotency-Key": "release-asset-assign-0001", "If-Match": asset_etag})
    _, assigned, _ = request(
        "/api/open/v1/commands/assets.assign:execute", method="POST",
        body={"targetPid": asset1["pid"], "input": {"assignee": "release-bot"}}, headers=command_headers,
    )
    if assigned["resource"]["status"] != "in_use":
        raise RuntimeError("asset command did not transition the real plugin record")
    request(
        "/api/open/v1/commands/assets.assign:execute", method="POST",
        body={"targetPid": asset1["pid"], "input": {"assignee": "other"}},
        headers=dict(machine, **{"Idempotency-Key": "release-asset-assign-0002", "If-Match": asset_etag}),
        expected=(412,),
    )

    _, stock, stock_headers = request(
        f"/api/open/v1/resources/inventory.stock-ins/{stock_in['pid']}", headers=machine,
    )
    stock_etag = stock_headers.get("ETag") or stock_headers.get("Etag")
    _, confirmed, _ = request(
        "/api/open/v1/commands/inventory.stock-ins.confirm:execute", method="POST",
        body={"targetPid": stock_in["pid"], "input": {}},
        headers=dict(machine, **{"Idempotency-Key": "release-stock-confirm-0001", "If-Match": stock_etag}),
    )
    if confirmed["resource"]["status"] != "confirmed":
        raise RuntimeError("stock-in command did not transition the real plugin record")

    _, page, _ = request("/api/open/v1/resources/assets?limit=1", headers=machine)
    cursor = page.get("nextCursor")
    if not cursor or asset1["pid"] in cursor:
        raise RuntimeError("opaque cursor contract failed")
    request(f"/api/open/v1/resources/assets?limit=1&cursor={urllib.parse.quote(cursor + 'x')}",
            headers=machine, expected=(400,))
    _, catalog, _ = request("/api/open/v1/event-catalog", headers=machine)
    types = {event["type"] for event in catalog}
    if types != {"assets.assignment.changed", "inventory.stock-in.confirmed"}:
        raise RuntimeError(f"unexpected external event catalog: {types}")

    with open(ARTIFACT, "w", encoding="utf-8") as handle:
        json.dump({"clientId": credential["clientId"], "clientSecret": credential["clientSecret"]}, handle)
    os.chmod(ARTIFACT, 0o600)
    print(json.dumps({
        "assetPid": asset1["pid"], "stockInPid": stock_in["pid"],
        "cursorOpaque": True, "staleWriteStatus": 412, "catalogCount": len(catalog),
    }, sort_keys=True))


if __name__ == "__main__":
    main()
