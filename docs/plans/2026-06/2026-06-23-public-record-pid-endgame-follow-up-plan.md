---
type: plan-impl
status: active
created: 2026-06-23
owner_lane: platform-public-record-pid
relates_to:
  - docs/backlog/2026-06-22-platform-public-record-pid-only-migration.md
---

# Public Record PID-Only Endgame Follow-up Plan

> 2026-06-24 final refresh: the executable remaining-task inventory is
> `docs/backlog/2026-06-23-public-record-pid-only-remaining-tasks.md`.
> Treat that backlog as current truth when this historical plan conflicts.
> The original 529-count tables below are historical planning context. After
> the local inbox/IM/email/share/utility/automation/agent fixes, the source
> scanner reached 0 findings and live OpenAPI scanning passed 145 scoped
> public-record paths plus 1308 component schemas with 0 findings. Final OSS
> backend full v16 passed on a DB initialized from `database/schema.sql`; final
> frontend `check` and full unit passed with pnpm 9.15.9; final schema SQL and
> drift gates passed; final enterprise full v5 passed against the clean OSS
> artifact `/tmp/auraboot-oss-public-record-pid-v15-m2/repository`.
>
> The v11 OSS full red was traced to a seedless initialization path:
> `db/snapshots/schema-current.sql` is schema-only and omits platform seed rows.
> The same required seeds are present in `database/schema.sql` and baseline
> Flyway. Seeded OSS targeted v12 and backend full v12 passed on databases
> initialized from `database/schema.sql`; they are now superseded by v15/v16
> final proof in the current backlog.
>
> 2026-06-23 hard-mode update: user decision is development-stage one-shot
> pid-only completion. Do not add public compatibility aliases. Record-adjacent
> legacy columns are backfilled into pid columns and dropped in the same
> package. Use the remaining-task inventory above as current truth when this
> historical plan conflicts.
>
> Current non-technical close-out: stage/commit/push current OSS and enterprise
> branches, update existing OSS PR #1060 and enterprise standards PR #657 by
> push, create the enterprise consumer PR, and merge only after PR
> topology/review/CI are clean.
>
> 2026-06-24 post-rebase close-out override: after rebasing the OSS parent
> branch onto latest `origin/main` `8541efe602293966f7417156a1fc04c3f4b0be56`,
> maintainer direction is to skip another full OSS/enterprise rerun because
> multiple full validations already exist. Current close-out evidence is the
> historical full OSS/enterprise proof plus post-rebase incremental tests
> (`47` tests, `0` failures/errors), public-record contract scan, docs
> governance, and `git diff --check`.

**For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` or `superpowers:executing-plans` when implementing this plan. Steps use checkbox syntax for tracking.

**Goal:** Finish the platform public-record pid-only migration without leaving ambiguous `recordId` public contracts, data columns, DSL examples, or frontend fallbacks behind.

**Architecture:** Public API and frontend runtime use pid-named fields (`recordPid`, `recordPids`, `targetRecordPid`, `commentPid`) as the canonical contract. Internal numeric ids may remain only behind admin/internal boundaries or compatibility aliases with telemetry. Record-adjacent storage must either have a pid column, prove the existing column stores pid, or be explicitly classified as internal-only.

**Tech Stack:** Spring Boot controllers/services, MyBatis dynamic data access, Flyway/PostgreSQL schema migrations, React/TypeScript runtime, Vitest, Gradle integration tests, OSS/enterprise full validation.

---

## Decisions

1. This migration is not interface-only. Several remaining surfaces can be fixed by API/DTO naming, but inbox and IM still have numeric business-record columns and require schema + backfill before the public contract is honestly pid-only.
2. Do the data-layer cleanup before removing aliases. If this waits until after rollout, production data will need model-aware backfills from numeric ids to dynamic-table pids.
3. Hard mode supersedes the earlier compatibility-window idea for this branch. New public contracts must not add `recordId` names, and existing public aliases should be removed unless the current backlog explicitly classifies them as internal/admin-only.
4. Treat `recordId` string columns case by case for backfill correctness. Some already store pids in legacy-named columns, but they still move to pid-named storage aliases before the old columns are dropped.
5. User direction is now one-shot completion on the current branch. The original strict full-gate stance was satisfied by repeated OSS/enterprise full runs before the latest rebase; the final maintainer direction is to merge from post-rebase incremental evidence instead of repeating full validation again.

## Historical Starting Evidence

Current branch: `codex/watch-field-history-pid`.

The table below is the original starting inventory for this follow-up plan, not
the current merge gate. The current merge gate is the executable backlog linked
above.

| Category | Findings | Files |
| --- | ---: | ---: |
| backend-public-api | 91 | 17 |
| dynamic-read-boundary | 79 | 2 |
| frontend-runtime | 359 | 102 |
| dsl-config | 0 | 0 |
| named-query-export | 0 | 0 |
| **Total** | **529** | **121** |

Current status already implemented in the stacked slice:

- DSL/config and NamedQuery/export direct SQL findings are at 0.
- Comments/activity have pid-only public paths and hidden internal ids.
- Watch/follow has `ab_watch.record_pid`, pid APIs, pid watcher routing, and tests.
- Field history has `ab_field_change_log.record_pid`, pid lookup, DTO responses without raw internal ids, and tests.
- Public-record gate blocks new findings but still accepts the 529 known gaps above.

## Data-Layer Answer

The remaining work is a mix of API-only, storage-alias, and true data migration.

| Surface | Current storage evidence | Migration class | Required decision |
| --- | --- | --- | --- |
| Record share | `ab_record_share.record_id BIGINT`, `record_pid VARCHAR(64)` already exists | API + backfill audit | Public controller/DTO should use `recordPid`; ensure `record_pid` is non-null for active shares before dropping legacy public/mixed storage. |
| Watch/follow | `ab_watch.record_pid` added in current slice | Done | Keep legacy numeric column only as an internal cleanup/drop target. |
| Field history | `ab_field_change_log.record_pid` added in current slice | Done, with audit | Current backfill uses `record_id::text`; before final hard removal, run a model-aware audit to confirm old numeric rows are not exposed as pids. |
| Record comments/activity | `ab_record_comment.record_pid` already canonical | Done | No further data migration. |
| Inbox | `ab_inbox_item.record_id BIGINT` | True migration | Add `record_pid VARCHAR(64)`, backfill from `(tenant_id, model_code, record_id) -> dynamic table pid`, index it, dual-write, then public read uses `recordPid` / `sourceRecordPid`. |
| IM object conversation | `ab_im_conversation.bound_record_id BIGINT` | True migration | Add `bound_record_pid VARCHAR(64)`, backfill by model table, add unique index on `(tenant_id, bound_model_code, bound_record_pid)`, keep numeric fallback internal-only. |
| Email record links | `ab_email_record_link.record_id VARCHAR(100)` | Storage-alias migration | Add `record_pid VARCHAR(100)` or formally document legacy column as pid-only. Preferred: add pid column, backfill by detecting pid match first and numeric-id resolver second. |
| Email sequence enrollment | `ab_email_sequence_enrollment.record_id VARCHAR(100)` | Storage-alias migration | Same as email record links; public API and templates move to `recordPid`. |
| Automation log | `ab_automation_log.trigger_record_id VARCHAR(26)` | Storage-alias migration | Add `trigger_record_pid`, dual-write, expose `triggerRecordPid`; keep `triggerRecordId` as deprecated alias with telemetry. |
| Automation debug session | `ab_automation_debug_session.record_id VARCHAR(255)` | Storage-alias migration | Add `record_pid`, backfill, expose `recordPid`; ensure debug execution context uses pid-named keys. |
| Agent action audit | `ab_agent_action.target_record_id VARCHAR(26)` and `target_record_ids JSONB` | Storage-alias migration | Existing values are pid-shaped, but the name is ambiguous. Add pid-named public DTO fields now; decide whether to add physical `target_record_pid` / `target_record_pids` before hard removal. |
| AI action audit | `ab_ai_action_audit_log.record_id VARCHAR(64)` | Storage-alias migration | Service comments already say this stores target pid; add `recordPid` API/DTO alias and telemetry for legacy reads. |
| Permission audit | `ab_permission_audit_log.record_id BIGINT` | Internal/admin-only unless exposed | Do not expose as public record identity. If any public endpoint reads it, join/resolve to `recordPid` before returning. |
| Reconciliation/internal relation tables | numeric source/target ids | Internal/admin-only unless exposed | Leave numeric if internal-only; any public DTO/export path must map to pid. |

If this ships without the true migration rows above, a future rollout will require production backfills. For numeric columns, the backfill cannot be `record_id::text`; it must resolve through the model metadata table name and the dynamic business table's `id -> pid` mapping under the same tenant.

## Work Packages

### P0: Stack And Merge Hygiene

**Files:**

- Modify only if state changes: `docs/backlog/2026-06-22-platform-public-record-pid-only-migration.md`
- Read before merging: PR #1060 and its base PR `codex/public-record-dual-id-hardening`

- [ ] Land the parent `codex/public-record-dual-id-hardening` branch first.
- [ ] Rebase `codex/watch-field-history-pid` on the updated base after the parent lands.
- [ ] Re-run public-record inventory and update the baseline only when a work package intentionally reduces findings.
- [ ] Do not merge any follow-up package unless OSS and enterprise full tests pass without newly introduced public-record findings.

**Done when:** current watch/field slice is merged cleanly and the next packages start from `main`, not from a stale stacked base.

### P1-DATA: Record-Adjacent Storage Migration

**Files:**

- Create: `platform/src/main/resources/db/migration/core/V20260623xxxx__public_record_pid_record_links.sql`
- Modify: `platform/src/main/resources/database/schema.sql`
- Modify: `platform/src/main/resources/database/schema-snapshot.sql`
- Modify services/controllers listed in P1-SHARE, P1-EMAIL-IM-INBOX, P1-AUTOMATION-AGENT

**Steps:**

- [ ] Add pid columns and indexes for inbox, IM, email record links, email sequence enrollment, automation log/debug, and any chosen agent audit storage aliases.
- [ ] Implement model-aware backfill helpers for numeric columns: resolve table name from model metadata, then update pid columns from dynamic table `pid` by `(tenant_id, id)`.
- [ ] For string legacy columns, backfill with pid-first detection: if a row exists by `pid`, copy directly; otherwise if numeric, resolve by internal `id`.
- [ ] In hard-mode branches, backfill pid columns and drop legacy public/mixed columns in the same package; only explicitly internal/admin numeric storage may remain.
- [ ] Add integration tests that seed legacy numeric rows and prove public reads return only pid fields.

**Done when:** `check-schema-sql`, schema drift, and targeted integration tests prove no public surface needs numeric record storage to render or route.

### P1-DYNAMIC: Dynamic Read Boundary And Cursor

**Files:**

- Modify: `platform/src/main/java/com/auraboot/framework/meta/controller/DynamicController.java`
- Modify: `platform/src/main/java/com/auraboot/framework/meta/service/impl/DynamicDataServiceImpl.java`
- Test: existing dynamic controller/service integration tests plus new pid cursor/export tests

Current findings: 79 dynamic-read-boundary, plus 24 backend-public-api findings in `DynamicController`.

**Steps:**

- [ ] Replace public cursor payloads derived from raw `id` with opaque signed/encoded cursors or pid-safe cursor fields.
- [ ] Re-check every dynamic return path after create/update/batch/relation/custom query for sanitizer coverage.
- [ ] Mark internal/admin dynamic reads explicitly so the gate does not confuse them with public reads.
- [ ] Add tests for list/detail/custom/relation/export proving `id`, `tenant_id`, raw actor ids, and cursor internals are hidden.

**Done when:** dynamic-read-boundary accepted findings are reduced to only documented internal/admin cases, or to 0 if the gate can classify them precisely.

### P1-SHARE: Record Share Public Contract

**Files:**

- Modify: `platform/src/main/java/com/auraboot/framework/permission/controller/RecordShareController.java`
- Modify: record-share DTO/service/mapper files found by `rg "RecordShare"`
- Modify: `web-admin/app/ui/shared/RecordShareDialog.tsx`
- Test: record-share controller/service integration tests

Current findings: 10 backend-public-api, 1 frontend-runtime.

**Steps:**

- [ ] Change public request/response fields and paths to `recordPid`.
- [ ] Remove public `recordId` input/output aliases; legacy keys may appear only in negative tests or internal cleanup code that strips them.
- [ ] Backfill or validate `ab_record_share.record_pid` for all rows before relying on it exclusively.
- [ ] Add tests for share create/list/revoke using pid-only values and no raw `record_id` in response.

**Done when:** RecordShare no longer contributes backend-public-api findings and the dialog no longer falls back to numeric ids.

### P1-EMAIL-IM-INBOX: Messaging And Inbox Record Links

**Files:**

- Modify: `platform/src/main/java/com/auraboot/framework/email/controller/EmailMessageController.java`
- Modify: `platform/src/main/java/com/auraboot/framework/email/controller/EmailSequenceController.java`
- Modify: `platform/src/main/java/com/auraboot/framework/email/service/EmailRecordLinkService.java`
- Modify: `platform/src/main/java/com/auraboot/framework/email/service/EmailSequenceService.java`
- Modify: `platform/src/main/java/com/auraboot/framework/email/service/EmailSequenceExecutor.java`
- Modify: `platform/src/main/java/com/auraboot/framework/im/controller/ImConversationController.java`
- Modify: `platform/src/main/java/com/auraboot/framework/im/service/ImEventListener.java`
- Modify: `platform/src/main/java/com/auraboot/framework/im/service/impl/ImConversationServiceImpl.java`
- Modify: `web-admin/app/routes/inbox/index.tsx`
- Modify: `web-admin/app/shared/services/inboxService.ts`
- Test: email link, sequence enrollment, inbox route, and IM object conversation integration tests

Current findings: 10 backend-public-api across email/IM, 12 frontend-runtime in inbox, plus storage gaps in `ab_inbox_item` and `ab_im_conversation`.

**Steps:**

- [ ] Migrate inbox and IM storage first as described in P1-DATA.
- [ ] Change public email link APIs from `recordId` to `recordPid`; reject/strip public legacy keys instead of accepting alias input.
- [ ] Change email sequence templates from `{{recordId}}` to `{{recordPid}}`; legacy template keys should not resolve.
- [ ] Change object conversations to bind by `boundRecordPid`; numeric `boundRecordId` may exist only as internal storage before the migration drops it.
- [ ] Make inbox deep links resolve only from `recordPid` / `sourceRecordPid`; numeric `recordId` must not be a public path.
- [ ] Add tests for migrated legacy rows and new pid-only creates, plus negative tests for rejected legacy public keys.

**Done when:** inbox/IM/email public flows work from pid-only data, and no public response requires numeric record ids.

### P1-AUTOMATION-AGENT: Automation, Agent Context, And Audit

**Files:**

- Modify: `platform/src/main/java/com/auraboot/framework/automation/controller/AutomationController.java`
- Modify: `platform/src/main/java/com/auraboot/framework/automation/service/AutomationService.java`
- Modify: `platform/src/main/java/com/auraboot/framework/automation/service/impl/AutomationServiceImpl.java`
- Modify: `platform/src/main/java/com/auraboot/framework/automation/service/impl/DebugSessionServiceImpl.java`
- Modify: `platform/src/main/java/com/auraboot/framework/agent/controller/AgentRunAuditController.java`
- Modify: `platform/src/main/java/com/auraboot/framework/agent/controller/AgentRunQuerySupport.java`
- Modify: `platform/src/main/java/com/auraboot/framework/agent/controller/AiActionAuditController.java`
- Modify: `platform/src/main/java/com/auraboot/framework/agent/controller/AgentSseController.java`
- Modify: agent runtime/context files found by `rg "recordId|targetRecordId" platform/src/main/java/com/auraboot/framework/agent`
- Test: automation manual trigger/debug tests, agent audit/query tests, pending-context freshness tests

Current findings: 11 backend-public-api across automation/agent controllers, plus many frontend/runtime references.

**Steps:**

- [ ] Add pid-named storage aliases for automation logs/debug sessions before changing controller DTOs.
- [ ] Rename public manual trigger input to `recordPid`; do not accept public legacy `recordId` alias input.
- [ ] Rename public agent audit output fields to `recordPid`, `targetRecordPid`, and `targetRecordPids`.
- [ ] Keep internal policy/provenance names only if they are explicitly pid-semantic and documented as internal.
- [ ] Add freshness and dry-run tests that use pid-only input and reject numeric-only public input once aliases are disabled.

**Done when:** automation and agent public APIs no longer teach `recordId` as the canonical record key.

### P1-AI-MOBILE-AUTOFILL-CAPABILITY: Utility APIs

**Files:**

- Modify: `platform/src/main/java/com/auraboot/framework/meta/controller/AutoFillController.java`
- Modify: `platform/src/main/java/com/auraboot/framework/meta/ai/AiFieldController.java`
- Modify: `platform/src/main/java/com/auraboot/framework/meta/controller/RecordCapabilityController.java`
- Modify: `platform/src/main/java/com/auraboot/framework/mobile/controller/MobileSearchController.java`
- Modify: `platform/src/main/java/com/auraboot/framework/meta/controller/config/ChangeLogController.java`
- Modify: `platform/src/main/java/com/auraboot/framework/meta/controller/config/SodController.java`
- Modify: `platform/src/main/java/com/auraboot/framework/permission/controller/PermissionMatrixController.java`
- Test: targeted controller tests for pid-only request/response behavior

Current findings: 36 backend-public-api.

**Steps:**

- [ ] Change request params/path variables to `recordPid` where these are public APIs.
- [ ] Ensure service calls use dynamic pid lookup, not `id` lookup, unless explicitly internal.
- [ ] Update response DTOs so `recordId` is not emitted alongside `recordPid`.
- [ ] Add compatibility telemetry for legacy params.

**Done when:** these utility controllers no longer contribute backend-public-api findings.

### P2-FRONTEND-RUNTIME: Runtime Fallback Removal

**Files:**

- Modify high-count files first:
  - `web-admin/app/framework/meta/rendering/pages/FormPageContent.tsx`
  - `web-admin/app/framework/meta/rendering/pages/DetailPageContent.tsx`
  - `web-admin/app/framework/meta/hooks/useActionHandler.ts`
  - `web-admin/app/routes/inbox/index.tsx`
  - `web-admin/app/framework/smart/components/view/CalendarView.tsx`
- Modify shared helpers:
  - `web-admin/app/framework/meta/runtime/publicRecordId.ts` or current helper location
  - `web-admin/app/routes/_shared/dynamic-route-utils.tsx`
  - `web-admin/app/shared/services/emailService.ts`
  - `web-admin/app/shared/services/inboxService.ts`
- Test: focused Vitest tests and Playwright/E2E where flows navigate to record detail

Current findings: 359 frontend-runtime across 102 files.

**Steps:**

- [ ] Make one canonical helper return pid only, with explicit failure for public runtime rows that lack pid.
- [ ] Replace `row.pid || row.id`, `recordPid || recordId`, and route-param ambiguity in high-reuse runtime files.
- [ ] Keep URL param names compatible only where route matching requires it; the value must be pid and variable names should say so in new code.
- [ ] Remove local fallback copies from smart views, dashboards, inbox, email, form/detail, route utilities, and action runtime.
- [ ] Add tests that fail if `id` fallback is used in public navigation/action payloads.

**Done when:** frontend-runtime findings are reduced package by package and every remaining accepted finding is either internal-only or a deliberate route compatibility alias.

### P2-NAMEDQUERY-EXPORT: Public Field Allowlist

**Files:**

- Modify: NamedQuery/export definition and execution files found by `rg "NamedQuery|export" platform/src/main/java plugins web-admin`
- Modify: validator script `scripts/validate-public-record-id-contracts.mjs`
- Test: NamedQuery/export integration tests with SQL selecting `id`

Current findings: 0, but this is still a future regression risk.

**Steps:**

- [ ] Add metadata that declares which query result fields are public.
- [ ] Reject or sanitize output aliases named `id`, `record_id`, `tenant_id`, `created_by`, `updated_by` unless internal/admin.
- [ ] Add plugin fixture tests proving new NamedQuery/export resources cannot reintroduce fake public ids.

**Done when:** NamedQuery/export remains at 0 without relying only on source-pattern inventory.

### P2-OPENAPI-LIVE-FIXTURES: Contract Coverage

**Files:**

- Modify: OpenAPI generation/check scripts if present
- Modify: `scripts/validate-public-record-id-contracts.mjs`
- Add: live response fixtures under the existing test fixture convention

**Steps:**

- [ ] Generate or capture representative public API responses for dynamic list/detail, share, email, inbox, automation, agent audit, mobile, autofill, capability.
- [ ] Add a contract check that fails on public response fields `id`, `recordId`, `targetRecordId`, `boundRecordId` unless explicitly allowlisted.
- [ ] Add OpenAPI/schema scanning so public DTO drift is caught before runtime.

**Done when:** a controller can no longer pass by renaming source variables while still emitting legacy JSON.

### P2-TELEMETRY: Legacy Alias Removal Readiness

**Files:**

- Modify: public request binding/normalization helpers
- Modify: metrics/logging configuration
- Add: dashboard or report for legacy alias usage if an observability convention exists

**Steps:**

- [ ] Keep static/runtime scanners proving no public legacy alias paths are accepted: `recordId`, `recordIds`, `targetRecordId`, `boundRecordId`, `triggerRecordId`.
- [ ] If an internal cleanup path strips legacy keys, test that it does not emit or route by those keys.
- [ ] Define the merge gate as pid-only fixtures plus negative legacy-alias tests, not a release-window telemetry gate.

**Done when:** pid-only fixtures and negative legacy-alias tests prove public legacy keys are not accepted.

### P3-HARD-REMOVAL: Compatibility Cleanup

**Files:**

- Modify all files still accepted by the public-record baseline after P1/P2.
- Modify: `scripts/public-record-id-baseline.json`
- Modify: docs and examples that still teach legacy fields.

**Steps:**

- [ ] Remove public legacy request aliases.
- [ ] Remove public legacy response fields.
- [ ] Remove or internalize legacy numeric storage reads that are no longer needed.
- [ ] Regenerate baseline so accepted findings are 0 or limited to explicitly internal/admin boundaries.
- [ ] Update enterprise canonical docs and agent rules with the final removal status.

**Done when:** public-record gate passes without broad accepted public findings and compatibility behavior is no longer needed for public clients.

### P-INFRA-ENT: Enterprise Clean Validation Without Overlay

**Files:**

- Modify enterprise tests that resolve `projectRoot/plugins/<name>` directly.
- Modify test bootstrap utilities to use `AURA_REGISTRY_ROOT_PLUGINS`.
- Keep this tracked in enterprise docs/branch, not only OSS backlog.

**Steps:**

- [ ] Inventory every enterprise test that ignores `AURA_REGISTRY_ROOT_PLUGINS`.
- [ ] Move plugin path resolution behind one fixture-root helper.
- [ ] Re-run enterprise full tests from a clean clone without symlink overlay.

**Done when:** enterprise validation no longer needs temporary plugin materialization symlinks.

## Backend Public API Finding Map

| File | Findings | Package |
| --- | ---: | --- |
| `platform/src/main/java/com/auraboot/framework/meta/controller/DynamicController.java` | 24 | P1-DYNAMIC |
| `platform/src/main/java/com/auraboot/framework/permission/controller/RecordShareController.java` | 10 | P1-SHARE |
| `platform/src/main/java/com/auraboot/framework/meta/controller/AutoFillController.java` | 8 | P1-AI-MOBILE-AUTOFILL-CAPABILITY |
| `platform/src/main/java/com/auraboot/framework/meta/ai/AiFieldController.java` | 7 | P1-AI-MOBILE-AUTOFILL-CAPABILITY |
| `platform/src/main/java/com/auraboot/framework/email/controller/EmailMessageController.java` | 6 | P1-EMAIL-IM-INBOX |
| `platform/src/main/java/com/auraboot/framework/meta/controller/RecordCapabilityController.java` | 6 | P1-AI-MOBILE-AUTOFILL-CAPABILITY |
| `platform/src/main/java/com/auraboot/framework/mobile/controller/MobileSearchController.java` | 6 | P1-AI-MOBILE-AUTOFILL-CAPABILITY |
| `platform/src/main/java/com/auraboot/framework/automation/controller/AutomationController.java` | 4 | P1-AUTOMATION-AGENT |
| `platform/src/main/java/com/auraboot/framework/meta/controller/config/ChangeLogController.java` | 3 | P1-AI-MOBILE-AUTOFILL-CAPABILITY |
| `platform/src/main/java/com/auraboot/framework/meta/controller/config/SodController.java` | 3 | P1-AI-MOBILE-AUTOFILL-CAPABILITY |
| `platform/src/main/java/com/auraboot/framework/permission/controller/PermissionMatrixController.java` | 3 | P1-AI-MOBILE-AUTOFILL-CAPABILITY |
| `platform/src/main/java/com/auraboot/framework/agent/controller/AgentRunAuditController.java` | 2 | P1-AUTOMATION-AGENT |
| `platform/src/main/java/com/auraboot/framework/agent/controller/AgentRunQuerySupport.java` | 2 | P1-AUTOMATION-AGENT |
| `platform/src/main/java/com/auraboot/framework/agent/controller/AiActionAuditController.java` | 2 | P1-AUTOMATION-AGENT |
| `platform/src/main/java/com/auraboot/framework/email/controller/EmailSequenceController.java` | 2 | P1-EMAIL-IM-INBOX |
| `platform/src/main/java/com/auraboot/framework/im/controller/ImConversationController.java` | 2 | P1-EMAIL-IM-INBOX |
| `platform/src/main/java/com/auraboot/framework/agent/controller/AgentSseController.java` | 1 | P1-AUTOMATION-AGENT |

## Frontend Runtime Finding Map

| Package | Findings | First targets |
| --- | ---: | --- |
| `framework/meta/rendering/pages` | 78 | form/detail/list page identity and draft keys |
| `framework/smart/components/view` | 37 | calendar/gantt/timeline/tree/gallery record navigation |
| `framework/meta/hooks` | 31 | action handling, DSL form, tree/document flow |
| `framework/meta/rendering/blocks` | 27 | BPM, field history, embedded list, record list |
| `routes/p.*` | 18 | dynamic route params that are pid-valued but id-named |
| `plugins/core-dashboard` | 17 | workbench widgets linking to records |
| `framework/meta/runtime` | 15 | ActionRegistry, passthrough action, reference create |
| `ui/smart` | 15 | decision/event policy/quoteops blocks |
| `routes/inbox` | 12 | inbox deep links and meta display |
| `framework/smart/components` | 9 | preview and renderer identity handling |
| `plugins/core-aurabot` | 9 | page context and agent run detail links |
| `framework/smart/hooks` | 6 | autofill and kanban persistence |
| `ui/email` | 6 | email timeline record links |
| `shared/services` | 5 | email/inbox service DTO aliases |
| `ui/shared` | 1 | record share dialog |
| Other low-count files | 73 | route/project-management/designer/decision utility cleanup |

## Verification Gates

Run these before claiming any package is complete:

```bash
scripts/check-public-record-id-contracts.sh
scripts/check-docs-governance.sh
scripts/db/check-schema-drift.sh --edition oss
env PG_HOST=localhost PG_PORT=5432 PG_USER=ghj PG_PASSWORD= scripts/check-schema-sql.sh --local
```

For backend packages:

```bash
./gradlew --no-daemon :test --tests '<targeted test class>' --console=plain
AURA_ENV=test IMPORT_TEST_FIXTURES=true AURA_REGISTRY_ROOT_PLUGINS=/path/to/plugins SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/<db>?charSet=UTF8 ./gradlew --no-daemon cleanTest test --console=plain
```

For frontend packages:

```bash
pnpm --dir web-admin check
pnpm --dir web-admin test:unit:run
```

For enterprise:

```bash
./gradlew --no-daemon publishToMavenLocal -Dmaven.repo.local=/tmp/<repo>/platform/.m2/repository --console=plain
AURA_ENV=test IMPORT_TEST_FIXTURES=true AURA_CORE_ROOT=/path/to/oss AURA_REGISTRY_ROOT_PLUGINS=/path/to/enterprise/plugins SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/<db>?charSet=UTF8 ./gradlew --no-daemon cleanTest test -Dmaven.repo.local=/tmp/<repo>/platform/.m2/repository --console=plain
```

## Merge Criteria

- Public-record gate has 0 new findings and the package's intended findings are removed from the accepted baseline.
- Schema SQL and schema drift gates pass when storage changes are included.
- Targeted integration/unit tests cover pid-only input, legacy alias input, and seeded legacy storage rows where relevant.
- OSS backend full tests pass.
- OSS frontend `check` and unit tests pass when frontend runtime changed.
- Enterprise backend full tests pass against the OSS artifact.
- PR description states whether the package is API-only, storage-alias, or true data migration.

## SOT Updates

This document is an active execution plan, not a long-term standard. The durable rules already live in:

- `/Users/ghj/work/auraboot/auraboot-enterprise/docs/standards/core/data-and-api.md`
- `/Users/ghj/work/auraboot/auraboot-enterprise/docs/standards/meta/id-pid-cross-module-reference-policy.md`

When P3 hard removal finishes, update those canonical enterprise files with the final alias-removal state and mark this plan complete.
