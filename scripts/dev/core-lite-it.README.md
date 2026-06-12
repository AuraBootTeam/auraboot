# W2 — Shared core-lite integration-test harness

> M1·W2. A single OSS-core isolated stack that any plugin's IT can import into on demand,
> instead of every IT spinning up the full enterprise stack. 2026-06-01.

## Why

The platform builds plugin physical tables only at import time (model.json → platform import →
`SchemaManagementService.createTableByModel`). So an IT needs a live backend + Postgres to get
real tables — it cannot generate schema offline. W2 starts **one** OSS-core stack and lets each
plugin IT `import-directory-sync <its plugin>` into it, asserting against the real API. Tenant
scoping (rows carry `tenant_id` from the JWT) keeps plugins/tenants from colliding.

## Pieces

- `scripts/dev/core-lite-it.sh` — starts/reuses one OSS-core isolated stack (`start-isolated.sh`,
  NOT the enterprise full stack), mounts a hybrid plugin's backend jar via `ENTERPRISE_PLUGIN_JARS_DIR`,
  then `docker cp`s the plugin **root** dir (the one with `plugin.json`) into the backend container
  and imports it via `POST /api/plugins/import/import-directory-sync` (the OSS-core container does
  not mount enterprise plugin config, so copy-in is required). Fails fast on an unhealthy stack.
- `platform/src/test/java/com/auraboot/framework/coreliteit/AbstractCoreLiteIT.java` — reusable
  env-gated (`CORE_LITE_IT=1`) live HTTP base: `adminLogin()` (jwt is nested at `data.jwt`),
  `importPluginDir()`, `dynamicList()` (**GET** `/api/dynamic/{pageKey}/list?pageNum&pageSize`),
  `parseTenantId()` (tenantId is a quoted 64-bit string). Pure helpers are unit-tested in normal CI.
- `BomCoreLiteHarnessIT` — acceptance #1: bom-standardization end-to-end (import → model-driven DDL
  → table queryable via DynamicController + idempotent re-import `success:true`).
- `TwoPluginCoexistenceIT` — acceptance #2: bom + crm lists concurrently on one stack, no cross-model
  contamination.

## Run

```bash
# 1. Build the hybrid plugin jar(s) and stage them.
( cd auraboot-enterprise && ./platform/gradlew :plugins:bom-standardization:backend:jar --no-daemon )
mkdir -p /tmp/core-lite-jars && cp auraboot-enterprise/plugins/bom-standardization/backend/build/libs/bom-standardization-plugin-*.jar /tmp/core-lite-jars/

# 2. Start the shared OSS-core stack + import the plugin (config) by its ROOT dir.
auraboot/scripts/dev/core-lite-it.sh --slug=core-lite --jars-dir=/tmp/core-lite-jars \
  --plugin="$PWD/auraboot-enterprise/plugins/bom-standardization" --rebuild
BE_PORT=$(grep '^BE_PORT=' auraboot/.aura-stack/core-lite.env | cut -d= -f2)   # port DRIFTS — always read it

# 3. Run the env-gated ITs against the live stack (use :test, the root project task).
cd auraboot/platform
CORE_LITE_IT=1 CORE_LITE_BE_PORT=$BE_PORT ./gradlew :test \
  --tests 'com.auraboot.framework.coreliteit.BomCoreLiteHarnessIT' \
  --tests 'com.auraboot.framework.coreliteit.TwoPluginCoexistenceIT' --no-daemon

# 4. Tear down.
auraboot/scripts/dev/stop-isolated.sh --slug=core-lite
```

## Verified evidence (red line #1, 2026-06-01)

Isolated OSS-core stack `core-lite` (BE_PORT=6531): bom config imported via docker cp +
import-directory-sync → `success:true`; `GET /api/dynamic/bom_conversion_task_pcba_list/list` → 200 with a
real `{records,total}` envelope (model-driven DDL built the table); idempotent re-import
`success:true`. `BomCoreLiteHarnessIT` 2/2 + `TwoPluginCoexistenceIT` 1/1 PASSED.

## Gotchas

- **OSS-core only, not enterprise full stack** (M1 risk-table requirement): the harness uses
  `start-isolated.sh`; the enterprise full stack / `reset-and-init.sh` are NOT used.
- **Port drifts** — always read `BE_PORT` from `.aura-stack/<slug>.env`, never hardcode.
- **Gradle filter** — the wildcard `*BomCoreLiteHarnessIT` matches no tests; use the FQN, and target
  `:test` (root project), not the bare `test` (which also runs `:platform-plugin-api:test` and fails
  "No tests found").
- **Enterprise plugin config is not mounted** in the OSS-core container — the harness `docker cp`s it
  in. The backend **jar** is mounted via `ENTERPRISE_PLUGIN_JARS_DIR` at stack start.

## 2c — offline DDL precheck (DDR-4: a fast pre-check ONLY, never a gate)

2c (generate `schema.sql` from models offline and `psql` it) is explicitly **subordinate** to the
2a golden IT above. It MAY be used for a seconds-fast "did I obviously break a model field" check
while editing, but it forks the DDL source of truth (loses model version / field binding / permission
consistency), so it **must not** be presented as W2 acceptance evidence. The real gate is the live
import + DynamicController assertions above + platform validator `success:true` (red line #2.2). No
offline generator is implemented in M1 (YAGNI); this note exists so a future contributor does not
mistake an offline precheck for the golden IT.

## W3 follow-ups

- Promote `AbstractCoreLiteIT` to an OSS-published `testFixtures` so each plugin's backend module can
  host its own core-lite IT (instead of all living under OSS platform test).
- True cross-**tenant** isolation: `TenantController` exposes no create endpoint today, so the
  coexistence IT proves per-plugin model/table isolation, not cross-tenant row invisibility. Add a
  second-tenant provisioning fixture, then assert tenant A's rows are invisible to tenant B on the
  same physical table.
- inventory plugin list returned 403 for the seed admin (page permission not granted on import) —
  worth confirming whether plugin-page permissions should auto-grant to the importing admin.
