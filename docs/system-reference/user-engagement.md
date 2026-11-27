---
type: system-reference
status: active
---

# User engagement persistence

The authenticated `/api/user-engagement` boundary stores favorites and recent page visits for
the current actor and tenant. The caller cannot choose another actor's ownership.

The unique target is `(user_id, tenant_id, target_type, target_id, engagement_type)`.
Concurrent first visits use the database conflict boundary and return the stored record identity.
A conflict refreshes the label, JSON context and latest visit time; it preserves the original
identity, creation time and manual sort position. The MyBatis JSONB type handler applies to both
the write parameters and returned entity, including nested context values.

`createdAt` identifies the first visit, while `updatedAt` identifies the latest one. Recent-page
presentation sorts by the latest timestamp before applying its display limit and deduplicates
by page path. Lists and their create, detail and edit pages are distinct paths; subpages use a
secondary label to distinguish their purpose. Recent visits retain at most twenty records per
actor and tenant, pruning the oldest visit time first. Favorites keep their manually assigned
order independently.

Writes participate in the caller transaction. A failed caller cannot leave a refreshed context
or a partially created engagement behind. Tenant interception continues to apply to the atomic
statement and subsequent reads.

The focused real PostgreSQL verification is
`platform/platform-app/src/integrationTest/java/com/auraboot/framework/engagement/UserEngagementConcurrencyIT.java`.
It requires an explicitly owned slot via `TEST_DATABASE_URL`, `TEST_DATABASE_USERNAME` and
`TEST_DATABASE_PASSWORD`, synchronizes concurrent initial reads, verifies one identity and
usable caller transactions, and checks JSONB roundtrip, tenant isolation, rollback and conflict
sort preservation. It retains uniquely identified fixture rows for inspection.
