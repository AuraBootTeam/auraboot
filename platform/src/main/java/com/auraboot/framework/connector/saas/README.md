# SaaS Connector Scaffolds (PRD 18 §B.3.2)

This package hosts the **scaffold** for 5 first-class SaaS connectors that ship with the
data platform. Each adapter currently exposes a stable
[`ConnectorDescriptor`](../sdk/ConnectorDescriptor.java) and stream list, but
`discover()` / `read()` throw `UnsupportedOperationException("NOT_YET_IMPLEMENTED")`
until the per-vendor follow-up PRs land.

## Status matrix

| Vendor | Adapter | Status | Auth | Follow-up PR scope |
|---|---|---|---|---|
| Salesforce | `salesforce/SalesforceConnectorAdapter` | scaffold | OAuth2 refresh-token | Bulk API 2.0 + SOQL + `SystemModstamp` cursor |
| HubSpot | `hubspot/HubspotConnectorAdapter` | scaffold | OAuth2 | v3 search + `?after` paging + associations |
| Stripe | `stripe/StripeConnectorAdapter` | scaffold | API-key | `?starting_after` paging + `/v1/events` change feed |
| Shopify | `shopify/ShopifyConnectorAdapter` | scaffold | per-shop OAuth | REST Link-header + GraphQL bulk for orders/products |
| DingTalk | `dingtalk/DingTalkConnectorAdapter` | scaffold | corp-internal app | accessToken cache 7200s + per-stream REST |

All 5 are registered with [`SaasConnectorRegistry`](./SaasConnectorRegistry.java) via Spring
component scan and `@Autowired(required=false) List<AbstractSaasConnectorAdapter>`.

## Architecture

```
ConnectorAdapter (SDK)                      // sdk/ConnectorAdapter.java
   └── AbstractConnectorAdapter             // sdk/AbstractConnectorAdapter.java
         └── AbstractSaasConnectorAdapter   // saas/AbstractSaasConnectorAdapter.java
               ├── SalesforceConnectorAdapter
               ├── HubspotConnectorAdapter
               ├── StripeConnectorAdapter
               ├── ShopifyConnectorAdapter
               └── DingTalkConnectorAdapter
```

`AbstractSaasConnectorAdapter` adds two SaaS-specific hooks beyond the SDK contract:

- **`discover(SaasConnectorConfig)`** — list available streams/objects (some vendors
  do runtime discovery, e.g. HubSpot custom objects; static vendors like Stripe return
  cached metadata).
- **`read(SaasConnectorConfig, String streamName, ReadCursor cursor)`** — lazy paginated
  read with incremental cursor support.

The classic `invoke()` from the SDK returns a `failure("NOT_YET_IMPLEMENTED")` placeholder;
the real high-throughput sync path will route through `read()`.

## Configuration shape

[`SaasConnectorConfig`](./SaasConnectorConfig.java) carries:

- `vendor` — stable connector key (e.g. `"saas-salesforce"`)
- `authType` — one of `oauth2` / `apikey` / `basic` (validated by ctor)
- `clientId` / `clientSecret` / `refreshToken` — decrypted secrets
- `scopes` — OAuth scope list (empty for API-key vendors)
- `apiBaseUrl` — per-tenant base URL (Shopify shop domain, Salesforce instance_url)
- `rateLimitPerMinute` — soft cap; `null` = vendor default
- `extras` — vendor-specific keys (`corpId`, `shopDomain`, `agentId`, ...)

[`ReadCursor`](./ReadCursor.java) carries the incremental resume point:

- `since` (Instant) — for vendors with a timestamp filter (Salesforce, HubSpot, Stripe)
- `pageToken` (String) — for opaque vendor pagination (HubSpot `after`, Stripe
  `starting_after`, Shopify `page_info`)
- `customState` (Map) — vendor-specific extra state (e.g. DingTalk `dingNextCursor`)

## Credential security

**The scaffold does NOT store secrets directly.** The real flow lands in the follow-up PR:

1. Connector definitions live in the existing `ab_connector` table; secret fields
   reference a `cr_csp_connector_pid` resolved at runtime through
   `ConnectorCredentialResolver` (canonical SPI established by ENT #97, the third
   plugin↔host bridge alongside `BackgroundDataAccessor` / `BackgroundTenantAccessor`).
2. The resolver returns a `ResolvedCredentials` envelope (bearer / basic / api_key /
   cookies) which the adapter projects onto `SaasConnectorConfig`.
3. The `SaasConnectorConfig` instance lives only for the duration of one `read()` /
   `discover()` call; nothing is persisted in-process.

Adapters that need OAuth refresh-token rotation must call back into the credential
resolver (write-through update) rather than mutating `SaasConnectorConfig` directly —
the record is immutable.

## Follow-up PR breakdown

Each connector ships in its own PR to keep review focused:

1. **PR-A — Salesforce real implementation** — Bulk API 2.0 + SOQL + describe cache.
2. **PR-B — HubSpot real implementation** — v3 search + paging + associations.
3. **PR-C — Stripe real implementation** — REST + `/v1/events` + Idempotency-Key.
4. **PR-D — Shopify real implementation** — REST + GraphQL bulk + Link cursor.
5. **PR-E — DingTalk real implementation** — accessToken cache + per-stream endpoints.
6. **PR-F — Sync engine** — Kafka-fed sync job that consumes `discover()` + `read()`
   from any registered adapter; deduplication / dead-letter / replay.
7. **PR-G — Admin UI** — connector creation wizard per vendor (OAuth handshake page).

PR-A through PR-E are independent (different teams, no shared file). PR-F depends on
all five being green-lit.

## Testing

- `SaasConnectorRegistryTest` covers registration + lookup of all 5 vendors.
- One unit test per adapter asserts the `descriptor()` shape (vendor key, stream list,
  description).

Integration tests against real SaaS sandboxes are deferred to the follow-up PRs and
will be gated behind credentialed CI profiles (Salesforce Developer Edition org,
HubSpot test account, Stripe test mode, Shopify development store, DingTalk corp-internal
app sandbox).
