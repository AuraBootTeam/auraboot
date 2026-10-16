# AuraBoot Open Platform SDK

For authentication, end-to-end HTTP CRUD/query examples and a durable signed-event receiver,
see the [External Integration Guide](../../web-admin/public/docs/open-platform-integration.html) or [Chinese guide](../../web-admin/public/docs/open-platform-integration.zh-CN.html).
A deployed Web Admin serves it at `/docs/open-platform-integration.html`; the Open Platform
management page links to the same packaged document.

Published on npm: `npm install @auraboot/open-platform-sdk`

```ts
import { OpenPlatformClient } from "@auraboot/open-platform-sdk";

const client = new OpenPlatformClient({
  baseUrl: process.env.AURABOOT_BASE_URL!,
  clientId: process.env.AURABOOT_CLIENT_ID!,
  clientSecret: process.env.AURABOOT_CLIENT_SECRET!,
  scope: "assets.read assets.manage",
});

const asset = await client.getAsset("asset_public_pid");
const current = await client.getAssetVersioned(asset.pid);
await client.assignAsset(asset.pid, "alice", crypto.randomUUID(), current.etag);
```

Callers that already manage a short-lived token can use the smaller runtime surface:

```ts
const client = new OpenPlatformClient({ baseUrl, accessToken });
const firstPage = await client.listStockIns(50);
const receipt = await client.getStockIn(firstPage.items[0].pid);
await client.confirmStockIn(
  receipt.resource.pid,
  crypto.randomUUID(),
  receipt.etag,
);
```

The client caches short-lived access tokens, refreshes them before expiry, preserves the public
field aliases, refreshes once after a `401`, preserves write idempotency keys across that retry, and
exposes `status`, `code`, and `requestId` through `OpenPlatformError`.
List cursors are opaque and resource-bound. Conditional commands require the strong ETag returned by a
versioned resource read; the SDK never retries `412 precondition_failed`.

## Configured dynamic models (source build)

The generic methods use the public resource/query code and aliases declared by the tenant's
published contract. The additions below are verified from a locally packed source build;
they have not been published to npm in this development task.

```ts
interface Part { pid: string; name: string; code: string | null }

const created = await client.createResource<Part>(
  "manufacturing.parts", { name: "Motor", code: "M1" }, crypto.randomUUID(),
);
const changed = await client.updateResource<Part>(
  "manufacturing.parts", created.resource.pid, { code: null },
  crypto.randomUUID(), created.etag,
);
const page = await client.listResources<Part>("manufacturing.parts", {
  limit: 20,
  filters: [{ field: "name", operator: "EQ", value: "Motor" }],
  sort: "name", direction: "ASC",
});
const totals = await client.executeQuery<{ total: string }>("manufacturing.totals", {
  parameters: { zones: ["A"] }, limit: 20,
});
await client.deleteResource("manufacturing.parts", changed.resource.pid,
  crypto.randomUUID(), changed.etag);
```

Create and update return `{ resource, etag, idempotentReplay, location?, requestId? }`.
Delete accepts the empty `204` response and returns `{ idempotentReplay, requestId? }`.
Retain the same key, body and ETag when replaying the same write intent. A new write intent needs
its own key; a stale ETag requires a new read and deliberate reconciliation. No automatic retry
occurs on `409`, `412`, `429`, transport errors or server errors. The single `401` token refresh
preserves the original write intent. Omitted PATCH fields remain unchanged; `null` is explicit
and is accepted only by nullable fields in the published contract.

Lists support only published filter operators and sort fields. Use the returned opaque cursor
with the same contract, installation, limit, filters and order. NamedQuery returns
`{ items, total, hasMore, nextCursor, schemaVersion }`; parameters remain typed JSON values.
The old `listResources(code, limit, cursor)` signature remains available.

`ModelEvent<T>` describes the installation-projected model notification body. Verify the HMAC
against the original HTTP bytes before parsing. Store event IDs to handle duplicate delivery,
and use `recordVersion` to handle out-of-order events. A TypeScript type does not verify a
signature, enforce an installation grant or perform runtime payload validation.

## License

Apache-2.0. See [LICENSE](./LICENSE) for the full text. The package license does not grant AuraBoot trademark rights.
