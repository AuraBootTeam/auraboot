---
type: backlog
status: active
created: 2026-06-22
updated: 2026-06-24
relates_to:
  - docs/backlog/2026-06-22-saved-view-feishu-parity-gaps.md
---

# Platform Public Record PID-Only Migration Gap List

## 背景

SavedView Feishu parity 已经补齐 SavedView、role、member、user-role 等局部 public-id gap。平台级动态记录 public contract 的目标仍然是 pid-only: 动态列表、详情、子表、评论、审计、watch/follow、命令运行时、DSL renderer、NamedQuery/export 等路径都不能把内部 numeric id 当作公共契约。

这不是单个 DTO 字段重命名。动态记录以 `Map<String, Object>` 返回，平台 renderer、命令运行时、audit/history tabs、watch/follow、NamedQuery/export、sub-table 都消费同一 record identity。只改一个 endpoint 会留下旁路泄漏，或打断现有 DSL 页面。

## 最新状态

本轮以 OSS 最新 `origin/main` 为准重新验证并推进首批 hardening:

- OSS base HEAD: `d22d10e4` (`Merge pull request #1041 from AuraBootTeam/codex/saved-view-quota-cleanup-e2e`)。
- Active OSS branch: `codex/public-record-dual-id-hardening`。
- Active enterprise docs branch: `codex/public-record-dual-id-standard` on enterprise base `185696611` (`Merge pull request #656 from AuraBootTeam/codex/worktree-discipline-saved-view`)。
- OSS repair PRs already on `main`: #1035 SavedView semantic fixture, #1036 audit actor pid query, #1037 legacy user-role pid deprecation signal.
- Enterprise validation branch `codex/public-record-dual-id-standard` adds the canonical dual-id agent rule and fixes the enterprise test-fixtures page golden drift that blocked latest-main full validation.
- 当前 hardening slice 已把 DSL/config 旧占位与 NamedQuery/export SQL 泄漏从基线中清零，补齐评论/activity 的 pid-only 公共契约，补齐对应 runtime/集成测试，并把双 id 规范沉淀到 enterprise agent 文档。后续仍需继续收敛 backend public API、dynamic read boundary、frontend runtime 三类 accepted baseline。
- 当前 stacked slice `codex/watch-field-history-pid` 已补齐 watch/follow 与 field history 的 `recordPid` 写入、查询、通知路由和 schema 迁移；公开边界不再新增 `recordId` alias，public-id gate baseline 从 541 收敛到 529。
- Canonical agent rule 落点: enterprise `AGENTS.md` 高频红线表已指向 `docs/standards/core/data-and-api.md` §Public Record 双 id 规范和 `docs/agent-rules/public-record-dual-id-contract.md`。反复违背的根因不是策略不清,而是旧 `recordId` 命名惯性、`Map<String,Object>` 绕过 DTO 类型系统、DSL/SQL/NamedQuery 属于数据配置编译器看不到、前端局部 fallback 复制旧写法、以及测试/示例继续教旧契约；当前规则要求遇到这些关键词先跑 inventory gate 和 targeted pid-only 测试。
- 一次性到位后续计划已落到 `docs/plans/2026-06/2026-06-23-public-record-pid-endgame-follow-up-plan.md`。该计划把剩余 529 项按 backend public API / dynamic read boundary / frontend runtime 分包，并明确区分 API-only、storage-alias、true data migration 三类任务；结论是后续不只是改接口，Inbox 和 IM 等记录关联表需要上线前完成 pid 列与 model-aware backfill，否则未来会产生生产数据迁移。
- 2026-06-23 当前执行口径已刷新到 `docs/backlog/2026-06-23-public-record-pid-only-remaining-tasks.md`: 本地分支在补齐 inbox/IM/email/share/utility/automation/agent/dynamic/frontend 后 public-record inventory 已清零，其中 backend-public-api 0、dynamic-read-boundary 0、frontend-runtime 0。后续验证仍以该文档为最新任务清单。
- 2026-06-24 final refresh: current branch has hard-mode pid-only implementation green across static source scan, live OpenAPI scoped + component scan, OSS backend full, frontend `check` + full unit, schema SQL/drift, clean OSS artifact publish, enterprise targeted, enterprise full, and enterprise canonical docs governance. Remaining repository work is PR topology/stage/commit/push, not implementation.

## Inventory Evidence

Final OSS public-record-id contract gate on current hard-mode branch:

| Category | Count |
| --- | ---: |
| backend-public-api | 0 |
| dsl-config | 0 |
| dynamic-read-boundary | 0 |
| frontend-runtime | 0 |
| named-query-export | 0 |
| **Total** | **0** |

Gate result:

```text
scripts/check-public-record-id-contracts.sh
Summary: 0 finding(s), 0 accepted, 0 new.
Inventory tests: 7/7 passing.
OpenAPI scanner tests: 4/4 passing.
```

Historical pre-endgame combined OSS + enterprise canonical inventory was run
without a baseline and showed the original 529 findings:

```text
node scripts/validate-public-record-id-contracts.mjs \
  --include-enterprise \
  --enterprise=/Users/ghj/work/auraboot/.worktrees/enterprise-public-record-dual-id-standard \
  --no-baseline \
  --quiet
Summary: 529 finding(s), 0 accepted, 529 new. Baseline: none FAILED.
```

That combined run is retained as historical inventory context only.
`--no-baseline` reports every finding as new, so it is not a passing gate and
should not be used as a merge blocker by itself.

## Current PR Scope Implemented

- Added a public-record contract inventory gate and CI workflow. Current OSS baseline is 529 accepted findings and fails on newly introduced leaks.
- Added `PublicRecordSanitizer` and applied it to dynamic list, namedQuery list, detail, create, update, batch create/update, custom query and relation response boundaries.
- Added `targetRecordPid` as the public command request field and hid public `targetRecordId` from JSON input/output.
- Added frontend `publicRecordId` helpers and adopted them in high-reuse dynamic runtime paths: list row selection/navigation/inline edit, list table row keys, tree row keys, form command submit, action handler, ActionRegistry, sub-table row actions/inline edit, smart calendar/gallery/timeline/gantt views and kanban persistence.
- Added `recordPid` placeholder support for detail API endpoints, sub-table API dataSource URLs/params, command sideEffect field mappings, batch sideEffect field mappings, and `start_process` postActions; legacy `${recordId}` placeholders no longer resolve in public config.
- Migrated OSS DSL configs from `${recordId}` / `{recordId}` to `${recordPid}` / `{recordPid}` where the value is the public pid.
- Removed current NamedQuery/export SQL findings: showcase summary now selects `pid`; workflow approval history no longer exposes SmartEngine task internal `id` as a fake `pid`.
- Tightened the inventory gate so SQL filters such as `WHERE tenant_id = ...` do not count unless internal fields are selected into public query output.
- Added `commentPid` to record comments, changed comment edit/delete HTTP paths to pid-only, hid raw comment/actor ids from comment and activity responses, and added tenant-scoped activity lookup.
- Added record-comment pid schema migration, schema.sql alignment, and generated OSS schema snapshot.
- Added `ab_watch.record_pid` and `ab_field_change_log.record_pid` migrations, changed watch public controller methods to pid-only path variables, changed notification watcher resolution to `recordPid`, and mapped field-history public responses to DTOs that hide internal ids.
- Added pid-only record-adjacent storage and public contracts for inbox, IM, email links/enrollments, automation logs/debug sessions, agent/AI audit surfaces, record share, mobile/autofill/capability/change-log/SOD/permission-matrix routes, and high-use frontend runtime paths.
- Added OpenAPI component-schema coverage so public-record legacy keys in reusable DTO schemas are caught even when the path-level response only references a component.
- Changed `/api/permissions/audit` from returning the internal entity to `PermissionAuditLogDTO`, resolving public `recordPid` from the internal audit row only when it is model-aware and tenant-scoped.
- Changed public fixture/seed DTOs from `recordIds` to `recordPids`, and changed explicit FIELD_MAP inserts to return generated `recordPid`.
- Repaired latest-main regressions discovered during full verification: SavedView semantic fixture drift, audit actor pid lookup, and legacy user-role endpoint deprecation behavior.
- Enterprise branch `codex/public-record-dual-id-standard` adds the canonical dual-id agent rule and repairs `plugins/test-fixtures` page labels/required flags so latest-main enterprise full validation can pass under current page golden rules.

The remaining tables below are historical gap accounting. Current executable
status lives in `docs/backlog/2026-06-23-public-record-pid-only-remaining-tasks.md`.

## Verification Status

Fresh 2026-06-24 checks on current hard-mode branch:

- OSS public-record-id contract gate:
  `scripts/check-public-record-id-contracts.sh`.
  Result: `Summary: 0 finding(s), 0 accepted, 0 new`; inventory tests 7/7 passing; OpenAPI scanner tests 4/4 passing.
- OSS live OpenAPI public-record contract scan:
  `node scripts/check-public-record-openapi-contract.mjs --input /tmp/aura-public-record-proof/oss-v15-openapi.json --json`.
  Result: `scopedPathCount=145`, `componentSchemaCount=1308`, `findingCount=0`.
- OSS compile gates:
  `./gradlew --no-daemon :compileJava --console=plain` and `./gradlew --no-daemon :compileTestJava --console=plain`.
  Result: both `BUILD SUCCESSFUL`.
- OSS targeted pid-only backend tests:
  `TestFixtureControllerRouteInboxTest`, `TestSeedControllerIntegrationTest`, `PermissionAuditServiceIntegrationTest`, `OtDeviceServiceIntegrationTest`, and `Invariant*` tests on DB `aura_public_record_pid_targeted_20260624_v15`.
  Result: `BUILD SUCCESSFUL in 1m 55s`.
- OSS backend full test:
  DB `aura_public_record_pid_oss_full_20260624_v16`, initialized from `platform/src/main/resources/database/schema.sql`.
  Command used `AURA_ENV=test IMPORT_TEST_FIXTURES=true AURA_REGISTRY_ROOT_PLUGINS=/Users/ghj/work/auraboot/.worktrees/oss-watch-field-history-pid/plugins SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/aura_public_record_pid_oss_full_20260624_v16?charSet=UTF8 ./gradlew --no-daemon cleanTest test -Dspring.test.context.cache.maxSize=8 --console=plain`.
  Result: `BUILD SUCCESSFUL in 20m 49s`; XML summary `1510` files / `12021` tests / `67` skipped / `0` failures / `0` errors.
- OSS frontend static gate:
  `pnpm_config_verify_deps_before_run=false PATH=/tmp/auraboot-pnpm9-shim:$PATH pnpm --dir web-admin check`.
  Result: typecheck, plugin routes, datetime display, and design-token checks passed; plugin routes `17 ok / 0 fail`; datetime `1839 files / 0 violations`; design-token burn-down `palette=1197`, `i18n=109`.
- OSS frontend full unit:
  `pnpm_config_verify_deps_before_run=false PATH=/tmp/auraboot-pnpm9-shim:$PATH pnpm --dir web-admin test:unit:run`.
  Result: `477` test files / `4885` tests passed / `0` failed. Note: pnpm 11 emits a stderr deprecation warning that breaks CLI tests expecting empty stderr, so final unit evidence uses pnpm `9.15.9`, which satisfies the repository `pnpm >=9` engine.
- OSS schema SQL gate:
  `PATH=/opt/homebrew/Cellar/postgresql@17/17.6/bin:$PATH env PG_HOST=localhost PG_PORT=5432 PG_USER=ghj PG_PASSWORD= scripts/check-schema-sql.sh --local`.
  Result: `schema.sql` has `10674` lines, `312` expected `CREATE TABLE` statements, and applies cleanly with `313` tables created.
- OSS schema drift gate:
  `PATH=/opt/homebrew/Cellar/postgresql@17/17.6/bin:$PATH scripts/db/check-schema-drift.sh --edition oss`.
  Result: committed snapshot matches Flyway result at `v20260623001000`.
- Clean OSS artifact publish for enterprise validation:
  `./gradlew --no-daemon publishToMavenLocal -Dmaven.repo.local=/tmp/auraboot-oss-public-record-pid-v15-m2/repository --console=plain` from `platform/`.
  Result: `BUILD SUCCESSFUL`.
- Enterprise canonical docs governance:
  `./scripts/check-docs-governance.sh` on branch `codex/public-record-dual-id-standard`.
  Result: `profile=full checked=1924 doc(s); 0 errors, 0 warnings`.
- Enterprise compile gates against the clean OSS artifact:
  `./gradlew --no-daemon :compileJava :compileTestJava -Dmaven.repo.local=/tmp/auraboot-oss-public-record-pid-v15-m2/repository --console=plain`.
  Result: `BUILD SUCCESSFUL`.
- Enterprise targeted pid consumer tests:
  `AssetCommandExecutionTest` on DB `aura_public_record_pid_enterprise_asset_20260624_v3` passed 43 tests; `QrInspectionClosureIT` on DB `aura_public_record_pid_enterprise_qr_20260624_v4` passed.
- Enterprise backend full test:
  DB `aura_public_record_pid_enterprise_full_20260624_v5`, using `AURA_CORE_ROOT=/Users/ghj/work/auraboot/.worktrees/oss-watch-field-history-pid`, `AURA_REGISTRY_ROOT_PLUGINS=/Users/ghj/work/auraboot/plugins`, and the clean OSS Maven repo `/tmp/auraboot-oss-public-record-pid-v15-m2/repository`.
  Result: `BUILD SUCCESSFUL in 6m 52s`; XML summary `167` files / `987` tests / `48` skipped / `0` failures / `0` errors.

Historical passing checks on current OSS hardening branch:

- OSS public-record-id contract gate:
  `scripts/check-public-record-id-contracts.sh`.
  Current stacked watch/field result: 529 findings, 529 accepted, 0 new; Node test runner 4/4 passing.
- OSS watch/follow + field history targeted tests:
  `./gradlew --no-daemon :test --tests 'com.auraboot.framework.integration.WatchServiceIntegrationTest' --tests 'com.auraboot.framework.notification.routing.DefaultRecipientResolverTest' --tests 'com.auraboot.framework.meta.service.impl.FieldChangeAuditServiceIntegrationTest' --console=plain`.
  Result: 50/50 tests passed after applying the local test DB migration `platform/src/main/resources/db/migration/core/V20260622005000__watch_field_change_record_pid.sql`.
- OSS frontend targeted unit tests:
  `pnpm --dir web-admin test:unit:run app/framework/meta/rendering/pages/__tests__/DetailPageContent.test.ts app/framework/meta/rendering/blocks/__tests__/SubTableViewer.test.tsx`.
  Result: 47/47 tests passed.
- OSS backend targeted unit tests:
  `./gradlew :test --tests 'com.auraboot.framework.meta.service.impl.CommandSideEffectSpelTest' --tests 'com.auraboot.framework.meta.service.impl.CommandSideEffectBatchTest' --tests 'com.auraboot.framework.meta.service.impl.pipeline.phases.PostExecutionPhaseStartProcessTest'`.
  Result: 50/50 tests passed.
- OSS comment/activity integration test:
  `AURA_ENV=test IMPORT_TEST_FIXTURES=true SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/aura_record_comment_pid_test?charSet=UTF8 ./gradlew --no-daemon cleanTest :test --tests com.auraboot.framework.meta.service.RecordCommentServiceIntegrationTest --console=plain`.
  Result: 8/8 tests passed.
- OSS frontend typecheck:
  `pnpm --dir web-admin typecheck`.
  Result: passed.
- OSS frontend static gate:
  `pnpm --dir web-admin check`.
  Result: `typecheck`, `verify:plugin-routes`, `check:datetime-display`, and `check:design-tokens` all passed. Plugin routes: 17 ok / 0 failed. Datetime display: 1839 files scanned / 0 violations. Design tokens: palette 1197 under baseline 1211, i18n 109 under baseline 110.
- OSS frontend unit tests:
  `pnpm --dir web-admin test:unit:run`.
  Result: 477 files / 4886 tests passed.
- OSS schema gate:
  `env PG_HOST=localhost PG_PORT=5432 PG_USER=ghj PG_PASSWORD= scripts/check-schema-sql.sh --local`.
  Result: `schema.sql` has 10631 lines, 311 expected `CREATE TABLE` statements, and applies cleanly with 312 tables created.
- OSS schema drift gate:
  `scripts/db/check-schema-drift.sh --edition oss`.
  Result: committed snapshot matches the Flyway result.
- OSS backend full test for current stacked watch/field slice:
  `AURA_ENV=test IMPORT_TEST_FIXTURES=true AURA_REGISTRY_ROOT_PLUGINS=/Users/ghj/work/auraboot/.worktrees/oss-watch-field-history-pid/plugins SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/aura_watch_field_history_pid_full_test?charSet=UTF8 ./gradlew --no-daemon cleanTest test --console=plain`.
  Result: process exit 0. Log scan: 11954 `PASSED`, 46 `SKIPPED`, no failure pattern (`FAILED`, `BUILD FAILED`, `There were failing tests`, `Compilation failed`, `FAILURE:`). Log: `/tmp/auraboot-verify-logs/oss-watch-field-history-pid-full.log`.
- OSS core publish for current enterprise validation:
  `./gradlew --no-daemon publishToMavenLocal -Dmaven.repo.local=/tmp/auraboot-oss-watch-field-history-pid/platform/.m2/repository --console=plain` from `platform/`.
  Result: `BUILD SUCCESSFUL`.
- OSS backend full test:
  `AURA_ENV=test IMPORT_TEST_FIXTURES=true AURA_REGISTRY_ROOT_PLUGINS=/tmp/auraboot-oss-verify-v4/plugins SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/aura_public_record_pid_hardening_full_test?charSet=UTF8 ./gradlew --no-daemon cleanTest test --console=plain`.
  Result: `BUILD SUCCESSFUL` in 20m55s. XML summary: 1503 files / 11994 tests / 0 failures / 0 errors / 43 skipped. Log: `/tmp/auraboot-verify-logs/oss-backend-full-hardening-migrated.log`.
- OSS core publish for enterprise validation:
  `./gradlew --no-daemon publishToMavenLocal -Dmaven.repo.local=/tmp/auraboot-oss-verify-v4/platform/.m2/repository --console=plain`.
  Result: passed.
- Enterprise docs governance:
  `node scripts/check-docs-governance.mjs`.
  Result: `profile=full checked=1924 doc(s); 0 errors, 0 warnings`.
- Enterprise targeted i18n fixture verification:
  `AURA_ENV=test IMPORT_TEST_FIXTURES=true AURA_CORE_ROOT=/tmp/auraboot-oss-verify-v4 AURA_REGISTRY_ROOT_PLUGINS=/Users/ghj/work/auraboot/.worktrees/enterprise-public-record-dual-id-standard/plugins SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/aura_public_record_pid_ent_hardening_i18n_test?charSet=UTF8 ./gradlew --no-daemon cleanTest test -Dmaven.repo.local=/tmp/auraboot-oss-verify-v4/platform/.m2/repository --tests com.auraboot.framework.i18n.I18nPluginImportTest --console=plain`.
  Result: 3/3 tests passed. Log: `/tmp/auraboot-verify-logs/enterprise-i18n-after-fixture-fix.log`.
- Enterprise targeted CRM overwrite verification for current stacked watch/field slice:
  `AURA_ENV=test IMPORT_TEST_FIXTURES=true AURA_REGISTRY_ROOT_PLUGINS=/Users/ghj/work/auraboot/.worktrees/enterprise-public-record-dual-id-standard/plugins SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/aura_enterprise_watch_field_history_pid_full_test?charSet=UTF8 ./gradlew --no-daemon test -Dmaven.repo.local=/tmp/auraboot-oss-watch-field-history-pid/platform/.m2/repository --tests com.auraboot.framework.plugin.integration.PluginOverwriteUpgradeIntegrationTest --console=plain` from enterprise `platform/`.
  Result: `BUILD SUCCESSFUL` in 59s. The preservation scenario passed; two precondition scenarios skipped by test design. Log: `/tmp/auraboot-verify-logs/enterprise-watch-field-history-pid-crm-overwrite.log`.
- Enterprise backend full test for current stacked watch/field slice:
  `AURA_ENV=test IMPORT_TEST_FIXTURES=true AURA_REGISTRY_ROOT_PLUGINS=/Users/ghj/work/auraboot/.worktrees/enterprise-public-record-dual-id-standard/plugins SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/aura_enterprise_watch_field_history_pid_full_test?charSet=UTF8 ./gradlew --no-daemon cleanTest test -Dmaven.repo.local=/tmp/auraboot-oss-watch-field-history-pid/platform/.m2/repository --console=plain` from enterprise `platform/`.
  Result: `BUILD SUCCESSFUL` in 7m7s. Log scan: 938 `PASSED`, 58 `SKIPPED`, no failure pattern. Log: `/tmp/auraboot-verify-logs/enterprise-watch-field-history-pid-full-overlay.log`.
- Enterprise backend full test:
  `AURA_ENV=test IMPORT_TEST_FIXTURES=true AURA_CORE_ROOT=/tmp/auraboot-oss-verify-v4 AURA_REGISTRY_ROOT_PLUGINS=/Users/ghj/work/auraboot/.worktrees/enterprise-public-record-dual-id-standard/plugins SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/aura_public_record_pid_ent_hardening_full_test?charSet=UTF8 ./gradlew --no-daemon cleanTest test -Dmaven.repo.local=/tmp/auraboot-oss-verify-v4/platform/.m2/repository --console=plain`.
  Result: `BUILD SUCCESSFUL` in 6m7s. XML summary: 166 files / 986 tests / 0 failures / 0 errors / 48 skipped. Log: `/tmp/auraboot-verify-logs/enterprise-backend-full-dual-id-standard-overlay-fixed.log`.

Current enterprise full validation still needs a temporary plugin materialization overlay because several tests resolve `projectRoot/plugins/<name>` directly:

- `plugins/asset-management` and `plugins/project-management` came from `/Users/ghj/work/auraboot/plugins` to preserve enterprise plugin ids expected by historical integration tests.
- `plugins/crm-starter`, `plugins/templates/crm-quick-start`, `plugins/templates/golden-path`, and `plugins/templates/hr-essentials` came from the current OSS worktree plugins to provide CRM overwrite and template catalog fixtures.
- `plugins/product-catalog` and `plugins/crm` came from `/Users/ghj/work/auraboot/plugins` to satisfy CRM overwrite dependencies.
- The overlay symlinks were removed after verification and are not part of the branch diff.

Historical full-stack checks from the prior merged OSS/enterprise validation round:
- OSS schema gate:
  `env PG_HOST=localhost PG_PORT=5432 PG_USER=ghj PG_PASSWORD= scripts/check-schema-sql.sh --local`.
  Result: `schema.sql` has 10628 lines, 311 expected `CREATE TABLE` statements, and applies cleanly with 312 tables created.
- OSS frontend gate:
  `web-admin pnpm run check`.
  Result: `typecheck`, `verify:plugin-routes`, `check:datetime-display`, and `check:design-tokens` all passed. Plugin routes: 17 ok / 0 failed. Datetime display: 1839 files scanned / 0 violations. Design tokens: palette 1197 under baseline 1211, i18n 110/110.
- OSS targeted SavedView verification:
  `AURA_ENV=test IMPORT_TEST_FIXTURES=true AURA_REGISTRY_ROOT_PLUGINS=/tmp/auraboot-oss-verify-v4/plugins SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/aura_public_record_pid_latest_v4_targeted_test?charSet=UTF8 ./gradlew --no-daemon cleanTest :test --tests com.auraboot.framework.integration.view.SavedViewEnhancedIntegrationTest --console=plain`.
  Result: 8/8 tests passed.
- OSS backend full test:
  `AURA_ENV=test IMPORT_TEST_FIXTURES=true AURA_REGISTRY_ROOT_PLUGINS=/tmp/auraboot-oss-verify-v4/plugins SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/aura_public_record_pid_latest_v4_full_test?charSet=UTF8 ./gradlew --no-daemon cleanTest test --console=plain`.
  Result: `BUILD SUCCESSFUL` in 24m59s. XML summary: 1503 files / 11991 tests / 0 failures / 0 errors / 43 skipped. Log: `/tmp/auraboot-verify-logs/oss-backend-full-v4.log`.
- OSS core publish for enterprise validation:
  `./gradlew --no-daemon publishToMavenLocal -Dmaven.repo.local=/tmp/auraboot-oss-verify-v4/platform/.m2/repository --console=plain`.
  Result: passed.

Passing checks on enterprise PR #650 head `a284f2858dd231c68d397d48d568f891ae54c78e`:

- Enterprise Flyway migrate:
  `/tmp/auraboot-oss-verify-v4/scripts/db/flyway-migrate.sh --edition enterprise --enterprise-root /tmp/auraboot-enterprise-verify-pr650`.
  Result: enterprise schema migrated to `v20260622003000`.
- Enterprise targeted plugin/I18n verification:
  I18n target passed 3/3 after restoring the OSS `project-management` template plugin; asset/plugin/template target passed 71/71 with workspace `asset-management`.
- Enterprise backend full test:
  `AURA_ENV=test IMPORT_TEST_FIXTURES=true AURA_CORE_ROOT=/tmp/auraboot-oss-verify-v4 AURA_REGISTRY_ROOT_PLUGINS=/tmp/auraboot-enterprise-verify-pr650/plugins SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/aura_public_record_pid_ent_pr650_full2_test?charSet=UTF8 ./gradlew --no-daemon cleanTest test -Dmaven.repo.local=/tmp/auraboot-oss-verify-v4/platform/.m2/repository --console=plain`.
  Result: `BUILD SUCCESSFUL` in 7m14s. XML summary: 166 files / 986 tests / 0 failures / 0 errors / 48 skipped. Log: `/tmp/auraboot-verify-logs/enterprise-backend-full-pr650-v2.log`.

Enterprise clean-clone validation needed an explicit plugin materialization overlay because several tests still resolve `projectRoot/plugins/<name>` directly instead of only using `AURA_REGISTRY_ROOT_PLUGINS`:

- `plugins/asset-management` came from `/Users/ghj/work/auraboot/plugins`.
- `plugins/project-management` came from OSS `/tmp/auraboot-oss-verify-v4/plugins/project-management`, preserving plugin id `com.auraboot.template.project-management`.

## Repair Gap List From Final Verification

These gaps were discovered while turning the original inventory into mergeable OSS/enterprise changes:

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
12. SavedView enhanced integration tests used stale cross-tenant/device semantic fields after semantic field validation tightened. Fixed with a dedicated `saved_view_enhanced_test` fixture and typed semantic fields in OSS PR #1035.
13. Audit trail actor lookup needed actor pid support on latest main. Fixed by adding actor pid query support in OSS PR #1036.
14. Legacy user-role endpoints needed explicit deprecation signaling and pid contract behavior. Fixed in OSS PR #1037.
15. Enterprise clean-clone full validation lacked a stable plugin materialization contract. Current validation uses an explicit overlay, but tests should stop hardcoding `projectRoot/plugins/<name>` and consistently respect `AURA_REGISTRY_ROOT_PLUGINS`.
16. Enterprise `plugins/test-fixtures` pages lagged the current page golden validator: raw `create`/`edit`/`delete`/`save` labels and missing page-level `required` flags broke `I18nPluginImportTest`. Fixed the fixture pages with localized labels/action-column labels and explicit required fields.

## Complete Gap List

Status legend:

- `DONE`: fixed and verified in the current OSS main or enterprise PR evidence above.
- `PARTIAL`: the highest-risk path is covered, but docs, schema, negative tests, or remaining baseline findings still exist.
- `OPEN`: not implemented in this phase; remains tracked before hard-mode pid-only closure.

| # | Gap | Status |
| ---: | --- | --- |
| 1 | Dynamic list responses lack a public sanitizer. `DynamicController#list` returns `PaginationResult<Map<String,Object>>`, so raw rows can expose `id`, `tenant_id`, `created_by`, `updated_by`. | DONE |
| 2 | Dynamic detail responses lack a public sanitizer. `getById` returns raw `Map<String,Object>` and needs a pid-only public boundary. | DONE |
| 3 | Dynamic create/update/batch responses lack a sanitizer. Saved rows can echo internal fields back to browser clients. | DONE |
| 4 | Dynamic relation and sub-table responses lack a sanitizer. Parent/child identity has not converged to pid for public flows. | PARTIAL |
| 5 | Dynamic custom query responses bypass list/detail sanitization. `executeCustomQuery` returns `List<Map<String,Object>>`. | DONE |
| 6 | Export/download has no proven shared public-field rule with screen reads. Export must hide the same fields list/detail hide. | PARTIAL |
| 7 | Cursor pagination can leak internal ids. Current keyset cursor logic derives `nextCursor` from the last row's `id`; public cursor should be opaque or pid-safe. | OPEN |
| 8 | Public API paths and parameters still expose `recordId`. Dynamic, auto-fill, AI fill, record capability, comments, watch, field history, email, IM, automation and agent audit surfaces still publish `recordId` naming. | PARTIAL |
| 9 | `recordId` semantics are mixed. In many paths it already carries pid; in adjacent tables it can still mean numeric id. There is no single normalize boundary. | PARTIAL |
| 10 | Batch/request bodies lack pid-named fields. Public request contracts need `recordPid`, `recordPids`, `targetRecordPid`, `commentPid`, and legacy public fields must be rejected or stripped rather than treated as aliases. | PARTIAL |
| 11 | `CommandExecuteRequest` lacked pid-first canonical execution semantics. Current hard-mode implementation serializes/accepts public `targetRecordPid` and hides `targetRecordId`. | DONE |
| 12 | Command executor still needs final scan coverage to prove all handler/public envelopes use `targetRecordPid`; internal Java names may remain only behind pid-semantic boundaries. | PARTIAL |
| 13 | DSL command side effects and post actions still used legacy record-id placeholders. Current OSS `dsl-config` baseline is 0 after migration to `${recordPid}`. | DONE |
| 14 | DSL `detailEndpoint` templates migrated from `{recordId}` to `{recordPid}` where the value is public record pid. | DONE |
| 15 | NamedQuery/DataSource is the major bypass surface. OSS public NamedQuery/export SQL findings are now 0, but a general public field allowlist is still needed for hard-mode closure. | PARTIAL |
| 16 | NamedQuery lacks public field allowlist metadata. The platform cannot distinguish public business ids from internal ids at query result boundary. | OPEN |
| 17 | Showcase/workflow-demo contain explicit internal-id mappings. Examples include `SELECT id...` and `CAST(t.id AS VARCHAR) AS pid`. | DONE |
| 18 | Comments only partially use public record identity. Record association uses `recordPid`, but comment edit/delete still needs `commentPid`, and response DTOs must hide internal comment and actor ids. | DONE |
| 19 | Watch/follow still uses numeric storage. `WatchController` accepted `Long recordId`; `ab_watch` needed `record_pid` or a resolver/backfill layer. | DONE |
| 20 | Field history still uses numeric storage. `FieldChangeAuditController` accepted `Long recordId`; `ab_field_change_log` needed pid-facing lookup/backfill. | DONE |
| 21 | Change log naming remains legacy. `ab_data_change_log.record_id` is string-like, but public DTO/API naming and actor id exposure still need cleanup. | PARTIAL |
| 22 | Record share is mixed id/pid. It already has `record_id` and `record_pid`, but public controller paths still expose `recordId`. | PARTIAL |
| 23 | Email, IM and inbox record links still use `recordId`. These record-adjacent surfaces must emit and accept pid-facing identifiers. | PARTIAL |
| 24 | Automation/manual trigger public contracts are moving to `recordPid`; legacy `recordId` must not remain an accepted public alias. | PARTIAL |
| 25 | Agent audit/run/action surfaces are moving to pid-only public contracts; internal Java names may remain only when JSON/API output is explicitly `recordPid`/`targetRecordPid`. | PARTIAL |
| 26 | Frontend runtime still relies on `row.pid || row.id`. Latest helper adoption and placeholder cleanup reduced the baseline to 359 frontend-runtime findings, but residual dynamic runtime paths still need cleanup. | PARTIAL |
| 27 | Frontend lacks a canonical `getRecordPid(row)` helper. Selection, navigation, draft keys, row actions, form edit state and sub-table parent/child identity resolve record ids in many local ways. | DONE |
| 28 | Route params still use `recordId`. URL compatibility may remain, but the semantic value should be documented and implemented as record pid. | OPEN |
| 29 | Tests and docs still need a final scan so they teach only `targetRecordPid` and `recordPid`; legacy strings may remain only in scanner fixtures and negative tests. | PARTIAL |
| 30 | Governance gate does not yet cover OpenAPI/response schemas. Current inventory covers source/config patterns, not generated OpenAPI schemas or live API response fixtures. | OPEN |
| 31 | Deprecation telemetry is no longer part of the development-stage plan; the merge gate is hard-mode rejection/stripping plus static/OpenAPI/runtime negative tests. | WONT_DO |
| 32 | Physical schema migrations are missing for record-adjacent tables. `commentPid`, `ab_watch.record_pid`, and `ab_field_change_log.record_pid` are implemented. | DONE |
| 33 | SavedView enhanced IT fixture drift blocked latest-main verification. | DONE |
| 34 | Audit actor lookup did not support actor pid query paths. | DONE |
| 35 | Legacy user-role endpoints did not explicitly signal pid-only deprecation behavior. | DONE |
| 36 | Enterprise clean-clone validation requires a plugin materialization overlay because some tests still resolve `projectRoot/plugins/<name>` directly. | OPEN |
| 37 | Enterprise test-fixtures page configs violated current page golden rules, blocking latest-main i18n/plugin validation. | DONE |

## Desired Contract

- Public dynamic records expose `pid` as the stable key.
- Top-level internal fields such as `id`, `tenant_id`, `created_by`, `updated_by` and raw actor ids are hidden unless the endpoint is explicitly admin/internal.
- Comment/activity/audit responses expose public identifiers such as `commentPid`, `recordPid`, `actorPid` and display names.
- NamedQuery/export/list/detail responses pass through a public-record sanitizer or explicit public field allowlist.
- New public request fields use `recordPid`, `recordPids`, `targetRecordPid` and `commentPid`.
- Existing public `recordId`/`targetRecordId` fields are not compatibility aliases in this development branch; they must be removed, rejected, or stripped at public boundaries.
- Runtime row identity uses pid. Public `row.id`, `$record.id`, and `targetRecordId` are legacy patterns that tests/docs must not teach.

## Phased Implementation

1. Inventory and regression gate: keep `scripts/validate-public-record-id-contracts.mjs` and baseline in CI so new leaks fail.
2. Dynamic read boundary: add sanitizer for list/detail/create/update/batch/custom query/relation/export results and convert public cursor to opaque token.
3. Dynamic write and command boundary: add pid-named request fields, normalize command target identity once at executor boundary, and remove public legacy fallback.
4. Record-adjacent surfaces: comments, watch/follow, field history, change logs, record share, email/IM/inbox links.
5. Frontend and DSL cleanup: canonical record identity helper, pid-first action payloads, DSL examples and validator warnings.
6. Hard-mode removal: reject or admin-gate numeric-id fallback now; telemetry is not a prerequisite in development-stage code.

## Acceptance Criteria

- Public dynamic list/detail/create/update/batch/custom query/relation/export responses expose `pid` and no top-level internal `id`, `tenant_id`, `created_by`, `updated_by`.
- Cursor pagination no longer exposes internal numeric ids.
- Command execution accepts `targetRecordPid`; public `targetRecordId` is not serialized or accepted as an alias.
- Comments expose `commentPid` and display actor names; edit/delete can be performed without numeric comment id or raw actor id exposure.
- Watch/follow and field history work with record pid.
- NamedQuery/DataSource/export cannot leak internal ids in public renderer contexts.
- Frontend list/detail/form/sub-table/workbench flows use pid for selection, navigation, row actions and draft identity.
- CI has a public-id contract gate with baseline and blocks new public internal-id leaks.
