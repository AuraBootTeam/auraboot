---
type: backlog
status: active
created: 2026-06-23
updated: 2026-06-24
owner_lane: platform-public-record-pid
branch: codex/watch-field-history-pid
base_head: 78292c928
relates_to:
  - docs/backlog/2026-06-22-platform-public-record-pid-only-migration.md
  - docs/plans/2026-06/2026-06-23-public-record-pid-endgame-follow-up-plan.md
---

# Public Record PID-Only Complete Gap And Follow-Up Task List

This is the current executable tracker for the public-record pid-only migration.
Older v6/v8/v9/v12 green runs remain useful historical evidence, but they are
no longer the current merge gate. The current final evidence is the 2026-06-24
v15/v16 OSS proof plus enterprise v5 validation against the clean OSS artifact,
followed by a post-rebase incremental close-out on latest OSS `origin/main`.

## Final Direction

Development-stage policy is strict pid-only:

- Public APIs, DTOs, OpenAPI schemas, frontend runtime payloads, DSL examples,
  and public response fixtures use `pid`, `recordPid`, `recordPids`,
  `targetRecordPid`, `targetRecordPids`, `boundRecordPid`,
  `triggerRecordPid`, or domain-specific pid names such as `commentPid`.
- Do not add public compatibility aliases named `recordId`, `recordIds`,
  `targetRecordId`, `targetRecordIds`, `boundRecordId`, or
  `triggerRecordId`.
- Numeric ids may remain only behind explicitly internal/admin boundaries.
- Record-adjacent storage that previously mixed public record identity with
  numeric ids must have pid columns, model-aware backfill when needed, tests,
  and final schema verification.

## Current Truth Snapshot

Branch state:

| Item | Current value |
| --- | --- |
| OSS worktree | `/Users/ghj/work/auraboot/.worktrees/oss-watch-field-history-pid` |
| OSS branch | `codex/public-record-dual-id-hardening` |
| Enterprise worktree | `/Users/ghj/work/auraboot/.worktrees/enterprise-public-record-pid-consumer` |
| Enterprise branch | `codex/enterprise-public-record-pid-consumer` |
| Current merge rule | 2026-06-24 maintainer direction: after many prior full OSS/enterprise runs, do not repeat full validation after the latest rebase; merge after post-rebase incremental gates and PR checks are clean. |

Final 2026-06-24 evidence:

| Gate | Final evidence | Result |
| --- | --- | --- |
| Post-rebase base | OSS branch rebased onto latest `origin/main` `8541efe602293966f7417156a1fc04c3f4b0be56` | Full rerun v17 was stopped as merge evidence after finding latest-main/test drift; targeted fixes were applied and incrementally verified. |
| Post-rebase incremental backend | `:test` filtered to `ArchitectureTest`, `AggregateQueryServiceImplDataScopeTest`, `DynamicDataServiceImplCoverageIT`, `AdminUserControllerPasswordResetTest`, `PasswordManagementServiceImplTest` | `BUILD SUCCESSFUL in 41s`; XML `47` tests / `0` failures / `0` errors / `0` skipped. |
| Post-rebase lightweight close-out | `git diff --check`, `scripts/check-public-record-id-contracts.sh`, `scripts/check-docs-governance.sh` | All passed; docs governance has only 3 pre-existing RBAC related-PR link warnings and 0 errors. |
| Public-record source scanner | `scripts/check-public-record-id-contracts.sh` | `0` findings, `0` accepted, `0` new; inventory tests 7/7 passing; OpenAPI scanner tests 4/4 passing. |
| Live OpenAPI scanner | `/tmp/aura-public-record-proof/oss-v15-openapi.json` with `scripts/check-public-record-openapi-contract.mjs --json` | `145` scoped public-record paths, `1308` component schemas, `0` findings. |
| OSS compile | `./gradlew --no-daemon :compileJava` and `:compileTestJava` | Both `BUILD SUCCESSFUL`. |
| OSS targeted pid-only backend | DB `aura_public_record_pid_targeted_20260624_v15` | `BUILD SUCCESSFUL in 1m 55s`. |
| OSS backend full | DB `aura_public_record_pid_oss_full_20260624_v16`, initialized from `database/schema.sql` | `BUILD SUCCESSFUL in 20m 49s`; XML `1510` files / `12021` tests / `67` skipped / `0` failures / `0` errors. |
| OSS frontend static | `pnpm --dir web-admin check` via pnpm `9.15.9` shim | typecheck, plugin routes, datetime display, and design tokens passed. |
| OSS frontend unit | `pnpm --dir web-admin test:unit:run` via pnpm `9.15.9` shim | Latest post-rebase run: `477` files / `4880` tests / `0` failed. |
| OSS schema SQL | `check-schema-sql.sh --local` with Postgres 17 psql on PATH | Latest post-rebase run: `schema.sql` 10681 lines, 312 expected CREATE TABLEs, 313 tables created, clean apply. |
| OSS schema drift | `scripts/db/check-schema-drift.sh --edition oss` | Committed snapshot matches Flyway result at `v20260624021000`. |
| Clean OSS artifact | `publishToMavenLocal -Dmaven.repo.local=/tmp/auraboot-oss-public-record-pid-v15-m2/repository` | `BUILD SUCCESSFUL`. |
| Enterprise docs governance | `./scripts/check-docs-governance.sh` on enterprise branch `codex/public-record-dual-id-standard` | `1924` docs checked, `0` errors, `0` warnings. |
| Enterprise compile | `:compileJava :compileTestJava` against `/tmp/auraboot-oss-public-record-pid-v15-m2/repository` | `BUILD SUCCESSFUL`. |
| Enterprise targeted pid consumers | `AssetCommandExecutionTest` DB v3 and `QrInspectionClosureIT` DB v4 | Asset slice 43 tests passed; QR closure passed. |
| Enterprise backend full | DB `aura_public_record_pid_enterprise_full_20260624_v5` against final OSS artifact | `BUILD SUCCESSFUL in 6m 52s`; XML `167` files / `987` tests / `48` skipped / `0` failures / `0` errors. |

Environment note: the system `/usr/local/bin/pnpm` symlink is broken, and pnpm
11 emits a deprecation warning to stderr for the root `pnpm` field. The final
frontend evidence uses a temporary pnpm `9.15.9` shim from Corepack cache, which
satisfies the repository `pnpm >=9` engine and avoids false stderr failures in
CLI tests that intentionally assert empty stderr.

Historical green evidence that still matters:

| Gate | Historical evidence | Current use |
| --- | --- | --- |
| Public-record static source gate | `scripts/check-public-record-id-contracts.sh` reached 0 findings after local pid-only edits. | Superseded by final 2026-06-24 green run above. |
| Live OpenAPI public-record schema gate | `/tmp/aura-public-record-openapi-v10.json` scanned 145 scoped public-record paths with 0 forbidden public keys. | Superseded by final v15 live scan above, which also covers component schemas. |
| Frontend full unit/typecheck/check | Full unit previously passed: 477 files, 4885 tests. | Superseded by final pnpm 9.15.9 run above. |
| Schema SQL/drift/Flyway validate | Passed after the hard-drop migration and regenerated schema artifacts. | Superseded by final Postgres 17 `check-schema-sql` and drift runs above. |
| OSS backend full v9 | Fresh DB initialized with `platform/src/main/resources/database/schema.sql`; 12062 tests, 43 skipped, 0 failures, 0 errors. | Historical proof that seeded schema path can pass; superseded by v12 for current merge evidence. |
| Enterprise backend full v4 | Passed against an older OSS v10 artifact: 987 tests, 48 skipped, 0 failures, 0 errors. | Stale for final merge because enterprise must consume the final OSS artifact. |

Latest OSS backend evidence:

| Gate | Result | Meaning |
| --- | --- | --- |
| OSS backend full v11 | `12020 tests completed, 38 failed, 43 skipped`; `BUILD FAILED in 28m 3s`. | Historical red evidence that exposed the seed-initialization gap. Not current merge evidence after v12. |
| v11 DB initialization | DB was initialized from `platform/src/main/resources/db/snapshots/schema-current.sql`. | This snapshot is schema-only and omits platform seed data. |
| v11 seed counts | `ab_billing_resource_catalog=0`, `ab_agent_dry_run_support=0`, `ab_agent_capability=0`, `ab_object_alias=0`, while `ab_agent_skill=87`, `ab_meta_model=149`. | Failures collapse to missing seed rows, not 38 unrelated product regressions. |
| Seed-bearing sources | `platform/src/main/resources/database/schema.sql` and `platform/src/main/resources/db/migration/core/V20260618000000__baseline_core_schema.sql` both contain the missing seed inserts. | Final backend full must use schema.sql or Flyway migration, not the DDL-only snapshot. |
| v9 comparison counts | On v9 DB: `catalog=13`, `agent_dry_run_support=6`, `agent_capability=6`, `object_alias=14`. | Confirms seeded initialization path contains the required platform defaults. |
| OSS targeted v12 | DB `aura_public_record_pid_oss_targeted_20260624_v12`, initialized from `platform/src/main/resources/database/schema.sql`; targeted v11 failure suites plus recent edits passed with `BUILD SUCCESSFUL in 2m 16s`. | Confirms the v11 failure classes pass when the DB has platform seeds and recent `TemplateRegistry` / failed-run lifecycle edits are covered. |
| OSS backend full v12 | DB `aura_public_record_pid_oss_full_20260624_v12`, initialized from `platform/src/main/resources/database/schema.sql`; `BUILD SUCCESSFUL in 27m 29s`; XML summary `1509` files, `12020` tests, `43` skipped, `0` failures, `0` errors. | Current OSS backend full evidence is green for this worktree. |
| v12 post-bootstrap seed counts | `scheduled_task=11`, `billing_catalog=13`, `dry_run_support=6`, `agent_capability=6`, `object_alias=14`, `agent_skill=87`, `meta_model=149`. | Confirms seeded initialization plus app/test bootstrap produced the required platform defaults. |

## Data-Layer Answer

The migration is not interface-only. It has three classes of work:

| Class | Surfaces | Data migration impact |
| --- | --- | --- |
| API/runtime rename only | Controller params, response DTOs, frontend props/routes/action payload names. | No business-data migration; still needs API/browser verification because persisted links and payloads carry pid values. |
| Storage alias plus hard drop | Email links/enrollments, automation log/debug, agent action audit, AI action audit. | Migration adds pid-named columns, copies legacy values, updates code/tests, then drops old public/mixed names. If old string values were already pids, this is schema alignment; if shipped data contains numeric strings, production needs resolver backfill. |
| True numeric-to-pid backfill | Inbox `record_id BIGINT`, IM `bound_record_id BIGINT`. | Migration resolves `(tenant_id, model_code, internal id)` through meta-model table mapping and writes pid columns before dropping numeric columns. Production rollout must block if unresolved rows remain. |

If these tables are still pre-release development data, the Flyway migration plus
regenerated `database/schema.sql` / `schema-current.sql` artifacts are enough for
this branch. If any table has shipped with real production data, rollout needs a
formal data audit: pre-count unresolved rows, model-aware backfill, compare
old/new reference counts, and block the drop when unresolved rows remain.

## Complete Current Gap List

| Gap | Status | Owner area | Evidence | Required close-out |
| --- | --- | --- | --- | --- |
| G0 | `DONE` | OSS backend validation recipe | v11 full used `db/snapshots/schema-current.sql`; missing seed counts were `catalog=0`, `dry_run=0`, `capability=0`, `object_alias=0`. v12 used `database/schema.sql` and post-bootstrap counts are `billing_catalog=13`, `dry_run_support=6`, `agent_capability=6`, `object_alias=14`. | Correct recipe is documented below. Do not initialize seed-dependent backend full runs from `schema-current.sql` alone. |
| G1 | `DONE` | OSS backend full final | OSS full v16 on `aura_public_record_pid_oss_full_20260624_v16`: `BUILD SUCCESSFUL in 20m 49s`; XML `1510` files / `12021` tests / `67` skipped / `0` failures / `0` errors. Latest post-rebase code fixes were covered by the 47-test incremental run per maintainer skip-full direction. | Use v16 as historical full evidence plus post-rebase incremental gates as current close-out evidence. |
| G2 | `DONE` | Public-record static scanner | Final gate: `0` findings, `0` accepted, `0` new; inventory tests 7/7; OpenAPI scanner tests 4/4. | Keep this as the final source-contract evidence. |
| G3 | `DONE` | Live OpenAPI contract | Final live scan: `145` scoped paths, `1308` component schemas, `0` findings. | Keep `/tmp/aura-public-record-proof/oss-v15-openapi.json` as the captured proof artifact. |
| G4 | `DONE` | Frontend runtime gates | Final `web-admin check` passed; final full unit passed `477` files / `4885` tests / `0` failed with pnpm `9.15.9`. | Document pnpm 11 stderr warning as environment noise, not a product failure. |
| G5 | `DONE` | Schema and migration gates | Final schema SQL and drift gates passed with Postgres 17 psql on PATH. | Schema artifacts match Flyway result at `v20260624021000`. |
| G6 | `DONE` | Recent OSS code edits | v15 targeted pid-only tests and v16 full include fixture/seed, permission audit, FIELD_MAP insert, OpenAPI, template/lifecycle, watch/history, and v11 seed-failure coverage. | No code weakening or test relaxation was needed. |
| G7 | `DONE` | API/runtime proof | Runtime proof includes live `/v3/api-docs` scan from the booted backend plus targeted/full integration coverage for dynamic, inbox, IM, email, share, automation, agent audit, mobile/autofill/capability, change-log, SOD, permission, test fixture, and seed DTO boundaries. | Separate Playwright browser E2E was not required for this backend/API contract migration; frontend `check` and full unit covered touched runtime helpers. |
| G8 | `DONE` | OSS artifact publish | Clean local Maven repo `/tmp/auraboot-oss-public-record-pid-v15-m2/repository` published after OSS green gates. | Enterprise validation consumed this artifact. |
| G9 | `DONE` | Enterprise final validation | Enterprise full v5 against final OSS artifact: `BUILD SUCCESSFUL in 6m 52s`; XML `167` files / `987` tests / `48` skipped / `0` failures / `0` errors. | Keep v5 as final enterprise backend evidence. |
| G10 | `DONE` | Enterprise pid consumer contracts | `AssetCommandExecutionTest` and `QrInspectionClosureIT` targeted runs passed; same changes were included in enterprise full v5. | Enterprise consumer branch is ready for stage/commit/push/PR. |
| G11 | `IN_PROGRESS` | PR topology | OSS child PR #1060 has been superseded/merged into the parent branch; OSS parent PR #1059 is the merge target. Enterprise docs PR #657 and consumer PR #669 are already open. | Stage/commit/push this final state, update PR evidence, then merge after PR checks are clean. |
| G12 | `DONE` | Agent/canonical doc precipitation | Enterprise canonical docs updated in `docs/agent-rules/public-record-dual-id-contract.md`, `docs/standards/core/data-and-api.md`, `docs/standards/meta/id-pid-cross-module-reference-policy.md`, and agent-rules index. Docs governance passed. | Durable SoT no longer depends only on this PROCESS backlog. |
| G13 | `IN_PROGRESS` | Final commit/merge gate | Post-rebase incremental gates are green; worktree is still dirty before final commit. | Stage/commit/push, update PR evidence, and merge after PR checks are clean. |

## Latest OSS v11 Failure Inventory

The 38 failed tests group into four missing-seed clusters:

| Cluster | Failed tests | Direct symptom | Missing seed table |
| --- | ---: | --- | --- |
| Billing resource catalog | 6 | `AI_TOKEN`, `SEAT`, `INSTANCE_COUNT`, `AUDIT_RETENTION_DAY`, and the 13 standard resources are absent. | `ab_billing_resource_catalog` |
| Billing quota/metering | 19 | Quota authorization/provisioning returns `RESOURCE_NOT_REGISTERED`; metering records are rejected and no usage rows are written. | Cascades from `ab_billing_resource_catalog` |
| Agent grounding/capability routing | 10 | Object resolver returns null for CRM aliases; capability router returns empty skill lists instead of `dsl.query` / `dsl.command`. | `ab_object_alias`, `ab_agent_capability` |
| Shadow/dry-run support | 3 | `dsl.query` / `dsl.command` dry-run levels resolve as `NONE` instead of `FULL` / `SIMULATED`. | `ab_agent_dry_run_support` |

Exact failed suites:

- `com.auraboot.framework.billing.catalog.ResourceCatalogServiceIntegrationTest`
- `com.auraboot.framework.billing.metering.MeteringServiceIntegrationTest`
- `com.auraboot.framework.billing.quota.QuotaServiceIntegrationTest`
- `com.auraboot.framework.billing.quota.QuotaPriorityIntegrationTest`
- `com.auraboot.framework.billing.quota.QuotaProvisionIntegrationTest`
- `com.auraboot.framework.agent.GroundingServiceIntegrationTest`
- `com.auraboot.framework.agent.AcpP1FeaturesIntegrationTest`
- `com.auraboot.framework.integration.agent.CapabilityRouterIntegrationTest`
- `com.auraboot.framework.integration.agent.DslCommandShadowInvokerIntegrationTest`
- `com.auraboot.framework.integration.agent.ShadowExecutorIntegrationTest`

## Correct Backend Full Recipe

Do not initialize full-test databases from `db/snapshots/schema-current.sql`
alone. That snapshot is generated with `pg_dump --schema-only` by
`scripts/db/generate-schema-snapshot.sh`; it is appropriate for drift checking,
not for seed-dependent backend full tests.

Preferred initialized-schema path:

```bash
DB=aura_public_record_pid_oss_full_20260623_v12

dropdb --if-exists "$DB"
createdb "$DB"

psql -v ON_ERROR_STOP=1 \
  -d "$DB" \
  -f platform/src/main/resources/database/schema.sql

psql -d "$DB" -Atc "
select 'scheduled_task', count(*) from ab_scheduled_task
union all select 'billing_catalog', count(*) from ab_billing_resource_catalog
union all select 'dry_run_support', count(*) from ab_agent_dry_run_support
union all select 'agent_capability', count(*) from ab_agent_capability
union all select 'object_alias', count(*) from ab_object_alias
union all select 'agent_skill', count(*) from ab_agent_skill
union all select 'meta_model', count(*) from ab_meta_model;"

AURA_ENV=test \
IMPORT_TEST_FIXTURES=true \
AURA_REGISTRY_ROOT_PLUGINS=/Users/ghj/work/auraboot/.worktrees/oss-watch-field-history-pid/plugins \
SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/${DB}?charSet=UTF8" \
SPRING_DATASOURCE_USERNAME=ghj \
SPRING_DATASOURCE_PASSWORD= \
./gradlew --no-daemon cleanTest test -Dspring.test.context.cache.maxSize=8 --console=plain
```

Flyway path is also valid if the target is to prove migration bootstrapping:

```bash
DB=aura_public_record_pid_oss_full_20260623_v12_flyway
dropdb --if-exists "$DB"
createdb "$DB"
PG_DB="$DB" scripts/db/flyway-migrate.sh --edition oss
PG_DB="$DB" scripts/db/flyway-validate.sh --edition oss
```

Whichever path is used, the preflight must prove the four platform seed tables
are non-empty before Gradle starts.

## Execution Order From Here

1. Run docs governance after this document update.
2. Stage/commit/push the OSS parent branch.
3. Update OSS PR #1059 with post-rebase incremental evidence.
4. Confirm enterprise PR #657 and #669 are still clean.
5. Merge OSS PR #1059 after PR checks are clean, per maintainer skip-full direction.

## What Not To Do

- Do not weaken the 38 red tests. The tests are correctly asserting required
  platform defaults; the v11 DB was missing those defaults.
- Do not use `schema-current.sql` alone as a backend full-test initializer.
- Do not add public `recordId` compatibility aliases to make old tests pass.
- Do not create a duplicate OSS PR for the same stacked branch; update PR #1060.
- Do not merge based on v9/v10/v4 historical green evidence; use v15/v16 OSS
  proof and enterprise v5 evidence.

## Closure Requirement

This tracker can move to `status: closed` only after:

- PR topology is resolved and the branches are pushed/PR'd.

The technical verification portion is green as of 2026-06-24.

## SOT Updates

Status: done. This backlog is PROCESS and is not the stable SoT. Durable rules
were synced to enterprise canonical docs:

- `/Users/ghj/work/auraboot/auraboot-enterprise/docs/standards/core/data-and-api.md`
- `/Users/ghj/work/auraboot/auraboot-enterprise/docs/standards/meta/id-pid-cross-module-reference-policy.md`
- relevant agent-rule docs that explain why public pid-only is enforced and why
  dual-id means internal numeric id plus public pid, not dual public contracts.
