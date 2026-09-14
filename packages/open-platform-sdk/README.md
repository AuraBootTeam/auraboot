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
await client.assignAsset(asset.pid, "alice", crypto.randomUUID());
```

The client caches short-lived access tokens, refreshes them before expiry, preserves the public
field aliases, refreshes once after a `401`, preserves write idempotency keys across that retry, and
exposes `status`, `code`, and `requestId` through `OpenPlatformError`.
