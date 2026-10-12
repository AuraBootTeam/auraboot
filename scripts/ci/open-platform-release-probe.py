#!/usr/bin/env python3
"""Release-image HTTP probe: bootstrap, install two real plugins, provision OAuth, then exercise v1."""

import importlib.util
from pathlib import Path

import json
import os
import urllib.parse

from open_platform_http import Client, data
from open_platform_dynamic_probe import run_dynamic_probe, run_sdk_consumer, audited_command, verify_command_audits, prepare_slo_fixture
from open_platform_model_event_probe import run_model_event_probe
from open_platform_model_event_runtime import DockerInfrastructure

bootstrap_spec = importlib.util.spec_from_file_location(
    "release_bootstrap", Path(__file__).with_name("release-bootstrap.py"))
bootstrap_module = importlib.util.module_from_spec(bootstrap_spec)
bootstrap_spec.loader.exec_module(bootstrap_module)

BASE = os.environ.get("BASE_URL", "http://app:6443").rstrip("/")
ARTIFACT = os.environ.get("CREDENTIAL_ARTIFACT", "/artifacts/open-platform-credentials.json")


def run_static_probe(request, admin):
    """Reuse the original real-plugin command/read protocol stage without runtime controls."""
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
    command_audits = {"applicationPid": application["pid"], "installationPid": installation["pid"],
                      "assetPid": asset1["pid"], "stockInPid": stock_in["pid"], "auditProofs": []}
    command_headers = dict(machine, **{"Idempotency-Key": "release-asset-assign-0001", "If-Match": asset_etag})
    _, assigned, _ = audited_command(request, admin, command_audits, "asset-assign",
        "/api/open/v1/commands/assets.assign:execute",
        body={"targetPid": asset1["pid"], "input": {"assignee": "release-bot"}}, headers=command_headers,
    )
    if assigned["resource"]["status"] != "in_use":
        raise RuntimeError("asset command did not transition the real plugin record")
    audited_command(request, admin, command_audits, "asset-stale",
        "/api/open/v1/commands/assets.assign:execute",
        body={"targetPid": asset1["pid"], "input": {"assignee": "other"}},
        headers=dict(machine, **{"Idempotency-Key": "release-asset-assign-0002", "If-Match": asset_etag}),
        expected=(412,),
    )

    _, stock, stock_headers = request(
        f"/api/open/v1/resources/inventory.stock-ins/{stock_in['pid']}", headers=machine,
    )
    stock_etag = stock_headers.get("ETag") or stock_headers.get("Etag")
    _, confirmed, _ = audited_command(request, admin, command_audits, "inventory-confirm",
        "/api/open/v1/commands/inventory.stock-ins.confirm:execute",
        body={"targetPid": stock_in["pid"], "input": {}},
        headers=dict(machine, **{"Idempotency-Key": "release-stock-confirm-0001", "If-Match": stock_etag}),
    )
    if confirmed["resource"]["status"] != "confirmed":
        raise RuntimeError("stock-in command did not transition the real plugin record")

    verify_command_audits(command_audits)

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

    return {"credential": credential, "public": {
        "assetPid": asset1["pid"], "stockInPid": stock_in["pid"],
        "cursorOpaque": True, "staleWriteStatus": 412, "catalogCount": len(catalog),
        "commandAudits": command_audits}}


def main():
    request = Client(BASE, max_requests=350, allow_expected_rate_limit=True).request
    admin = bootstrap_module.bootstrap_admin(request)
    static = run_static_probe(request, admin)
    credential = static["credential"]

    sdk_command = ["docker", "run", "--rm", "-i", "--user", os.environ["SDK_RUNNER_USER"],
                   "--network", os.environ["SDK_NETWORK"], "-v", os.environ["SDK_CONSUMER_ROOT"] + ":/consumer:ro",
                   os.environ["SDK_NODE_IMAGE"], "node", "/consumer/real-backend.mjs"]
    sdk = None
    load_input = None

    def consume(fixture):
        nonlocal sdk, load_input
        load_input = fixture
        sdk = run_sdk_consumer(fixture, sdk_command, base_url=os.environ["SDK_BASE_URL"],
                               evidence_path=Path(ARTIFACT).with_name("sdk-probe.json"))

    from open_platform_authentication_probe import PsqlAuditReader
    audit_reader = PsqlAuditReader(["docker", "exec", "-i", os.environ["MODEL_POSTGRES"], "psql", "-U", "auraboot",
                                   "-d", "open_platform_ci", "-X", "-At", "-v", "ON_ERROR_STOP=1"], "open_platform_ci", allow_fixture_clock=True)
    dynamic = run_dynamic_probe(request, admin, audit_reader=audit_reader, evidence_path=Path(ARTIFACT).with_name("dynamic-probe.json"),
                                consumer=consume)
    infrastructure = DockerInfrastructure(app=os.environ["MODEL_APP"], postgres=os.environ["MODEL_POSTGRES"],
        network=os.environ["SDK_NETWORK"], job_id=os.environ["AURA_CI_JOB_ID"],
        source_ref=os.environ["MODEL_SOURCE_REF"], image_digest=os.environ["MODEL_IMAGE_DIGEST"],
        base_url=BASE, receiver_host=os.environ["MODEL_RECEIVER_HOST"])
    model_events = run_model_event_probe(request, admin, infrastructure,
        evidence_path=Path(ARTIFACT).parent / "model-events" / "result.json",
        receiver_host=os.environ["MODEL_RECEIVER_HOST"], secret_file=os.environ["MODEL_EVENT_SECRET_FILE"], measure_load=True)

    slo_fixture = prepare_slo_fixture(request, admin, dynamic, load_input,
        fixture_path=os.environ.get("SLO_FIXTURE_ARTIFACT", str(Path(ARTIFACT).with_name("slo-fixture.json"))))

    with open(ARTIFACT, "w", encoding="utf-8") as handle:
        json.dump({"clientId": credential["clientId"], "clientSecret": credential["clientSecret"]}, handle)
    os.chmod(ARTIFACT, 0o600)
    print(json.dumps(dict(static["public"], dynamic=dynamic, sdk=sdk, modelEvents=model_events, sloFixture=slo_fixture), sort_keys=True))


if __name__ == "__main__":
    main()
