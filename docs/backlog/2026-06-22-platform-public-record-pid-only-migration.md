---
type: backlog
status: active
created: 2026-06-22
updated: 2026-06-23
relates_to:
  - docs/backlog/2026-06-22-saved-view-feishu-parity-gaps.md
---

# Platform Public Record PID-Only Migration Gap List

## 背景

SavedView Feishu parity 已经补齐 SavedView、role、member、user-role 等局部 public-id gap。剩余问题是平台级动态记录 public contract 仍未收敛到 pid-only:动态列表、详情、子表、评论、审计、watch/follow、命令运行时、DSL renderer、NamedQuery/export 等路径仍共享 record identity 形态。

这不是单个 DTO 字段重命名。动态记录以 `Map<String, Object>` 返回，平台 renderer、命令运行时、audit/history tabs、watch/follow、NamedQuery/export、sub-table 都消费同一 record identity。只改一个 endpoint 会留下旁路泄漏，或打断现有 DSL 页面。

## Inventory Evidence

基于最新 `origin/main`(`0773f9af2`) 新建 worktree `codex/public-record-pid-migration` 后重新扫描。

OSS baseline:

| Category | Count |
| --- | ---: |
| dynamic-read-boundary | 79 |
| frontend-runtime | 380 |
| backend-public-api | 109 |
| dsl-config | 19 |
| named-query-export | 24 |
| **Total** | **611** |

OSS + enterprise plugin config scan:

| Category | Count |
| --- | ---: |
| dynamic-read-boundary | 79 |
| frontend-runtime | 380 |
| backend-public-api | 109 |
| dsl-config | 19 |
| named-query-export | 30 |
| **Total** | **617** |

Enterprise scan in this phase only covers plugin config JSON, not enterprise backend/frontend source.

## Current PR Scope Implemented

- Added a public-record contract inventory gate and CI workflow. Current OSS baseline is 611 findings and fails on newly introduced leaks.
- Added `PublicRecordSanitizer` and applied it to dynamic list, namedQuery list, detail, create, update, batch create/update, custom query and relation response boundaries.
- Added `targetRecordPid` as the pid-first command request alias while keeping `targetRecordId` compatible during the migration window.
- Added frontend `publicRecordId` helpers and adopted them in high-reuse dynamic runtime paths: list row selection/navigation/inline edit, list table row keys, tree row keys, form command submit, action handler, ActionRegistry, sub-table row actions/inline edit, smart calendar/gallery/timeline/gantt views and kanban persistence.
- Regenerated the inventory baseline after the implemented reductions. Remaining gaps below are still tracked and should be closed in follow-up phases before removing compatibility aliases.

## Verification Status

Passing checks in the final repair pass:

- OSS backend full test:
  `AURA_ENV=test IMPORT_TEST_FIXTURES=true ./gradlew --no-daemon test`
  over a freshly recreated `aura_public_record_pid_oss_test` database after OSS Flyway migrate.
  XML summary: 1486 files / 11882 tests / 0 failures / 0 errors / 43 skipped.
- OSS frontend gate:
  `web-admin pnpm run check`.
  This ran `typecheck`, plugin route verification, datetime display check, and design-token check.
- OSS schema gate:
  `PG_HOST=localhost PG_PORT=5432 PG_USER=ghj PG_PASSWORD='' scripts/check-schema-sql.sh --local`.
  Result: `schema.sql` applies cleanly; 311 tables created.
- OSS public-record-id contract gate:
  `scripts/check-public-record-id-contracts.sh`.
  Result: 611 findings, 611 accepted, 0 new; Node test runner 4/4 passing.
- Enterprise backend full test:
  `AURA_ENV=test IMPORT_TEST_FIXTURES=true ./gradlew --no-daemon test -Dmaven.repo.local=<oss-worktree>/platform/.m2/repository`
  over a freshly recreated `aura_public_record_pid_ent_test` database after enterprise Flyway migrate with the OSS core worktree.
  XML summary: 166 files / 986 tests / 0 failures / 0 errors / 48 skipped.

Inventory-only evidence:

- `node scripts/validate-public-record-id-contracts.mjs --include-enterprise --enterprise=/Users/ghj/work/auraboot/auraboot-enterprise --no-baseline --quiet` reported 617 OSS + enterprise plugin config findings. This is intentionally not a passing gate because `--no-baseline` reports the full inventory.

The merge gate is no longer blocked by the previously observed OSS backend, `web-admin`, schema, public-record-id contract, or enterprise backend failures.

## Repair Gap List From Final Verification

These gaps were discovered while turning the original inventory into a mergeable PR:

1. NL modeling generated v4 page schemas could still contain raw non-ASCII labels such as table column `label` values instead of `$i18n:*` keys. Fixed by normalizing page title/description and nested block text into generated i18n resources.
2. `RecordCapabilityIntegrationTest` seeded dynamic records without audit columns, while the current schema creates `created_by` and `updated_by` as varchar system columns. Fixed test fixtures to seed varchar audit values.
3. `AutomationRunStreamControllerIntegrationTest` asserted SSE body contents before async chunk delivery was observable, causing a race. Fixed by polling the response body for expected chunk events before publishing completion.
4. `DashboardGenerationLiveIT` used automatic tool choice while asserting a required tool call, so the live model could validly answer in text. Fixed by requiring tool choice and adding a diagnostic response summary.
5. Enterprise asset plugin install expectations counted only 12 page resources after the dashboard page was also imported. Fixed the assertion to require 12 pages plus 1 dashboard page resource.
6. Enterprise OSS-to-enterprise CRM overwrite upgrade tests assumed a single plugin root and missed platform permissions and command handlers needed by imported resources. Fixed path resolution, fixture permissions, handler registration, and table/column assertions.
7. Enterprise `InsightType` only recognized the new `_common` CRM model codes, while some mobile/AI callers still send legacy CRM codes. Fixed by keeping `_common` primary codes and accepting legacy aliases.
8. Enterprise mobile config tests expected old CRM pinned model codes. Fixed expected pinned models to `crm_lead_common`, `crm_account_common`, and `crm_opportunity_common`.
9. Enterprise FX revaluation test was double-applying enterprise schema SQL on top of Flyway-managed schema. Fixed the redundant SQL fixture.
10. Enterprise lead normalization tests still targeted the old CRM lead table and lacked the minimal required current columns. Fixed the table target and fixture setup.
11. Enterprise template controller tests referenced template ids that no longer match the current template catalog. Fixed list/preview/install expectations to current template ids.

## Complete Gap List

1. Dynamic list responses lack a public sanitizer. `DynamicController#list` returns `PaginationResult<Map<String,Object>>`, so raw rows can expose `id`, `tenant_id`, `created_by`, `updated_by`.

2. Dynamic detail responses lack a public sanitizer. `getById` returns raw `Map<String,Object>` and needs a pid-only public boundary.

3. Dynamic create/update/batch responses lack a sanitizer. Saved rows can echo internal fields back to browser clients.

4. Dynamic relation and sub-table responses lack a sanitizer. Parent/child identity has not converged to pid for public flows.

5. Dynamic custom query responses bypass list/detail sanitization. `executeCustomQuery` returns `List<Map<String,Object>>`.

6. Export/download has no proven shared public-field rule with screen reads. Export must hide the same fields list/detail hide.

7. Cursor pagination can leak internal ids. Current keyset cursor logic derives `nextCursor` from the last row's `id`; public cursor should be opaque or pid-safe.

8. Public API paths and parameters still expose `recordId`. Dynamic, auto-fill, AI fill, record capability, comments, watch, field history, email, IM, automation and agent audit surfaces still publish `recordId` naming.

9. `recordId` semantics are mixed. In many paths it already carries pid; in adjacent tables it can still mean numeric id. There is no single normalize boundary.

10. Batch/request bodies lack pid-named fields. Public request contracts need `recordPid`, `recordPids`, `targetRecordPid`, `commentPid`, while legacy fields remain aliases for one compatibility window.

11. `CommandExecuteRequest` lacks pid-first canonical execution semantics. `targetRecordId` remains the dominant public field.

12. Command executor has no single target identity normalization layer. Handlers still interpret `targetRecordId` independently.

13. DSL command side effects and post actions still use `${recordId}`. Current OSS config baseline has 19 DSL/config hits.

14. DSL `detailEndpoint` templates still use `{recordId}`. These should migrate to `{recordPid}` or explicitly document pid semantics.

15. NamedQuery/DataSource is the major bypass surface. OSS has 24 hits; enterprise plugin config adds 6 more. Some SQL selects internal `id`, `tenant_id`, actor ids or aliases numeric ids as public fields.

16. NamedQuery lacks public field allowlist metadata. The platform cannot distinguish public business ids from internal ids at query result boundary.

17. Showcase/workflow-demo contain explicit internal-id mappings. Examples include `SELECT id...` and `CAST(t.id AS VARCHAR) AS pid`.

18. Comments only partially use public record identity. Record association uses `recordPid`, but comment edit/delete still needs `commentPid`, and response DTOs must hide internal comment and actor ids.

19. Watch/follow still uses numeric storage. `WatchController` accepts `Long recordId`; `ab_watch` needs `record_pid` or a resolver/backfill layer.

20. Field history still uses numeric storage. `FieldChangeAuditController` accepts `Long recordId`; `ab_field_change_log` needs pid-facing lookup/backfill.

21. Change log naming remains legacy. `ab_data_change_log.record_id` is string-like, but public DTO/API naming and actor id exposure still need cleanup.

22. Record share is mixed id/pid. It already has `record_id` and `record_pid`, but public controller paths still expose `recordId`.

23. Email, IM and inbox record links still use `recordId`. These record-adjacent surfaces must emit and accept pid-facing identifiers.

24. Automation/manual trigger still uses `recordId`. Manual trigger and rule context need pid-first aliases and deprecation telemetry.

25. Agent audit/run/action surfaces still expose mixed identity fields. `targetRecordId`, `targetRecordPid`, and `recordId` coexist without a clear public contract.

26. Frontend runtime still relies on `row.pid || row.id`. The 408 frontend hits concentrate in detail/form/list/sub-table/action handler/calendar/gantt/timeline/workbench paths.

27. Frontend lacks a canonical `getRecordPid(row)` helper. Selection, navigation, draft keys, row actions, form edit state and sub-table parent/child identity resolve record ids in many local ways.

28. Route params still use `recordId`. URL compatibility may remain, but the semantic value should be documented and implemented as record pid.

29. Tests and docs still teach `targetRecordId` and `recordId`. New DSL/plugin examples can continue creating legacy configs unless validators and docs are updated.

30. Governance gate does not yet cover OpenAPI/response schemas. Current inventory covers source/config patterns, not generated OpenAPI schemas or live API response fixtures.

31. Deprecation telemetry is missing. Legacy alias usage is not counted, logged, or exposed through metrics, so removal readiness cannot be measured.

32. Physical schema migrations are missing for record-adjacent tables. Required examples: `commentPid`, `ab_watch.record_pid`, `ab_field_change_log.record_pid`.

## Desired Contract

- Public dynamic records expose `pid` as the stable key.
- Top-level internal fields such as `id`, `tenant_id`, `created_by`, `updated_by` and raw actor ids are hidden unless the endpoint is explicitly admin/internal.
- Comment/activity/audit responses expose public identifiers such as `commentPid`, `recordPid`, `actorPid` and display names.
- NamedQuery/export/list/detail responses pass through a public-record sanitizer or explicit public field allowlist.
- New public request fields use `recordPid`, `recordPids`, `targetRecordPid` and `commentPid`.
- Existing `recordId`/`targetRecordId` fields remain compatibility aliases for one window and prefer pid resolution first.
- Runtime row identity uses pid. `row.id`, `$record.id`, and `targetRecordId` remain deprecated compatibility aliases only.

## Phased Implementation

1. Inventory and regression gate: keep `scripts/validate-public-record-id-contracts.mjs` and baseline in CI so new leaks fail.
2. Dynamic read boundary: add sanitizer for list/detail/create/update/batch/custom query/relation/export results and convert public cursor to opaque token.
3. Dynamic write and command boundary: add pid-named request fields, normalize command target identity once at executor boundary, keep legacy fallback with telemetry.
4. Record-adjacent surfaces: comments, watch/follow, field history, change logs, record share, email/IM/inbox links.
5. Frontend and DSL cleanup: canonical record identity helper, pid-first action payloads, DSL examples and validator warnings.
6. Deprecation removal: remove or admin-gate numeric-id fallback after telemetry confirms compatibility window is complete.

## Acceptance Criteria

- Public dynamic list/detail/create/update/batch/custom query/relation/export responses expose `pid` and no top-level internal `id`, `tenant_id`, `created_by`, `updated_by`.
- Cursor pagination no longer exposes internal numeric ids.
- Command execution accepts `targetRecordPid`; legacy `targetRecordId` remains compatible during the migration window.
- Comments expose `commentPid`; edit/delete can be performed without numeric comment id.
- Watch/follow and field history work with record pid.
- NamedQuery/DataSource/export cannot leak internal ids in public renderer contexts.
- Frontend list/detail/form/sub-table/workbench flows use pid for selection, navigation, row actions and draft identity.
- CI has a public-id contract gate with baseline and blocks new public internal-id leaks.
