import { OpenPlatformClient, OpenPlatformError, type QueryPage, type ModelEvent, type ResourcePage } from "@auraboot/open-platform-sdk";

const client: OpenPlatformClient = new OpenPlatformClient({
  baseUrl: "https://tenant.example.com",
  clientId: "ab_client_demo",
  clientSecret: "secret-demo",
  scope: "assets.read",
});
const page: ResourcePage<{ pid: string }> = await client.listResources("assets", 10);
const err = new OpenPlatformError(403, "insufficient_scope", "denied", "req_1");
const status: number = err.status;
const code: string = err.code;
const requestId: string | undefined = err.requestId;
console.log(page.items.length, status, code, requestId ?? "none");

interface Part { pid: string; name: string; code: string | null }
const created = await client.createResource<Part>("fixture.parts", { name: "Part", code: "P1" }, "create-part");
const updated = await client.updateResource<Part>("fixture.parts", created.resource.pid, { code: null }, "update-part", created.etag);
const deleted = await client.deleteResource("fixture.parts", updated.resource.pid, "delete-part", updated.etag);
const filtered: ResourcePage<Part> = await client.listResources<Part>("fixture.parts", {
  filters: [{ field: "name", operator: "EQ", value: "Part" }, { field: "code", operator: "IS_NULL" }],
  sort: "name", direction: "DESC", limit: 7,
});
const summary: QueryPage<{ total: string }> = await client.executeQuery<{ total: string }, { zones: string[] }>("fixture.summary", { parameters: { zones: ["A"] } });
const event: ModelEvent<Part> = {
  id: "evt_fixture", type: "open.resource.fixture.parts.updated", schemaVersion: 1, occurredAt: "2026-10-10T00:00:00Z",
  subject: { type: "fixture.parts", pid: "part-1" }, recordVersion: 2, correlationId: "req-fixture", data: updated.resource,
};
// @ts-expect-error IS_NULL has no value in the wire protocol
client.listResources("fixture.parts", { filters: [{ field: "code", operator: "IS_NULL", value: null }] });
// @ts-expect-error update requires the original strong ETag
client.updateResource("fixture.parts", "part-1", {}, "update-part");
console.log(filtered.items.length, summary.schemaVersion, deleted.idempotentReplay, event.recordVersion);
