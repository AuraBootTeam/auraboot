# AuraBoot Open Platform SDK

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
