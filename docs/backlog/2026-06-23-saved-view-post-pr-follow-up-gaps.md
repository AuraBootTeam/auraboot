---
type: backlog
status: active
created: 2026-06-23
relates_to:
  - docs/backlog/2026-06-22-saved-view-feishu-parity-gaps.md
  - docs/plans/2026-06/2026-06-22-saved-view-feishu-parity-requirements.md
  - docs/retro/2026-06-23-saved-view-p2-remaining-validation.md
  - docs/assets/mockups/saved-view-vnext-mockup.html
  - web-admin/tests/e2e/saved-view/FEATURE_MATRIX.md
---

# SavedView Post-PR Follow-up Gap Tracker

## Context

SavedView Feishu parity has been split into a stacked delivery:

- `codex/saved-view-feishu-p1`: Feishu-style daily selector, management panel, shared draft, public DTO cleanup, capability entry UX.
- `codex/saved-view-count-limit`: manual view count limits: personal 10, team/global 20.
- `codex/saved-view-p2-remaining`: UserRole pid mutation paths, audit public DTO, per-view collaborator ACL, timeline start/resource gate, quick-filter preset save-as-personal.

This document tracks follow-up gaps that remain after PR `#1028`. It is intentionally not a reopen of the completed P0/P1/P2 work. It separates:

- Product maturity work needed to reach long-term Feishu-level robustness.
- E2E and governance debts found during `e2e-truth` review.

Related PR: `https://github.com/AuraBootTeam/auraboot/pull/1028`

Dynamic business record pid-only migration has been removed from this SavedView tracker because it is owned by another workspace and is not a blocker for the SavedView feature completion decision.

## Scope Boundary And Source Of Truth

本文档现在是 SavedView 飞书体验对齐后的后续任务唯一 gap 总账。它回答三个问题:

- 还有哪些工作是 SavedView 当前产品成熟度必须补齐的。
- 哪些非 SavedView 治理项虽然来自同一轮 review,但不能阻塞 SavedView 分支收口。
- 每个 gap 需要哪些产品行为、代码层、浏览器路径、golden 截图和 `e2e-truth` 证据才能关闭。

边界裁决:

| Lane | Included | Excluded |
| --- | --- | --- |
| Current SavedView follow-up | WP1-WP5:高级视图语义校验、E2E 矩阵治理、协作者产品化、配额 UX/fixture、quick preset 生命周期 | UserRole legacy endpoint removal、audit actorPid public query、OpenAPI pid 清理 |
| Non-blocking platform/public-contract notes | RBAC endpoint deprecation、audit query public contract、docs/OpenAPI cleanup | 不在当前 SavedView 分支里顺手改 audit 公共契约或全仓文档命名 |

当前文档状态是 `active backlog`:WP1-WP5 已在 `codex/saved-view-p2-remaining` 完成实现和目标验证;dynamic record pid-only migration 不再纳入本文档 backlog。

## Follow-up Task Reading Guide

本文档是 SavedView 飞书体验对齐后的后续任务总账。后续窗口先读本节,再进入具体 gap:

- 当前分支只处理 5 个 SavedView 内聚工作包:高级视图语义校验、E2E 覆盖矩阵、协作者产品化、配额 UX/测试夹具、quick filter preset 生命周期。
- 任何 work package 不能只因为代码存在就标记完成;必须同时具备后端/前端单测、浏览器路径、golden 或截图证据、`e2e-truth` 真实性说明。
- 后续开发完成某个 gap 时,需要同步更新三处:对应 `GAP-SV-FU-*` 详情、`Delivery Matrix`、`Verification Matrix`。

本轮当前 worktree 已完成的 5 个包:

| Package | Gap | 已补齐什么 | 完成证据 |
| --- | --- | --- | --- |
| WP1 | GAP-SV-FU-002 | 高级视图语义校验、timeline/kanban 负例、capability reason 对齐 | Backend focused test、target E2E `17 passed`、kanban/lookup changed-field run `7 passed, 5 skipped` |
| WP2 | GAP-SV-FU-003 | `FEATURE_MATRIX.md`、target-scope truth audit、历史目录 debt 分层 | Target files 无 skip/fixme/wait/threshold/direct `/p/`;历史目录保留分类说明 |
| WP3 | GAP-SV-FU-005 + GAP-SV-FU-009 | 协作者分享面板、ACL 校验、审计面板和截图 | Backend ACL tests、component tests、golden screenshots `01/02` |
| WP4 | GAP-SV-FU-007 | personal 10、team/global 20 配额 UX、fixture create/reuse 策略 | Backend quota tests、quota browser path、golden screenshot `03` |
| WP5 | GAP-SV-FU-008 | quick preset provider、saved/edited/reset 生命周期、个人副本复用 | Unit/component tests、target E2E、golden screenshots `04/05` |

后续任务优先级的当前状态:

| Order | Work package | Why now | Stop condition |
| --- | --- | --- | --- |
| 1 | WP2 E2E matrix/truth | 先把证据口径固定,避免后续用 pass count 冒充完成 | done: `FEATURE_MATRIX.md` 已区分 UI path、API setup、contract-only、deferred |
| 2 | WP1 Advanced semantic validation | 阻断 API 创建无法渲染的半成品高级视图 | done: create/update/capability 和目标 E2E/contract 负例已有证据 |
| 3 | WP3 Collaborator productization | 共享视图是飞书对齐的高级能力,必须有权限、审计和管理入口 | done: owner share/audit browser path + backend ACL denial path 已覆盖 |
| 4 | WP4 Quota UX/fixtures | personal 10、team/global 20 是产品规则,不是只在 API 报错时才知道 | done: 用户创建前可见上限,测试 helper 记录 create/reuse |
| 5 | WP5 Quick preset lifecycle | 右侧 quick filters 要保持轻量,保存后的 personal copy 要像成熟 SavedView | done: saved/edited/reset 状态和个人副本复用已闭环 |

非阻塞平台治理项:

| Gap | 外部原因 | 当前分支规则 |
| --- | --- | --- |
| GAP-SV-FU-004 | UserRole legacy ID endpoint 退役需要 telemetry、兼容窗口、OpenAPI 策略 | 当前分支不删除兼容端点 |
| GAP-SV-FU-006 | audit actorPid 查询和 internal actor id 边界属于审计公共契约治理 | 不和 SavedView sharing UI 混在同一 PR |
| GAP-SV-FU-010 | 文档/OpenAPI pid 清理依赖平台迁移命名决策 | 等 migration 决策落地后再统一清理 |

## Origin Decisions

These follow-up gaps come from the Feishu parity analysis and the subsequent PR closeout review:

- SavedView is a daily list-page view switcher, not a separate management page that users must open every day.
- The primary entry stays beside the page title, Feishu-style. Management panels open only for create, rename, share, copy, audit, and advanced config.
- Right-side chips such as `我的记录`, `今日新建`, and `本周修改` stay as quick filters. They are lightweight daily actions, not replacements for SavedView.
- Team/shared views are an advanced capability backed by `ab_team` and `ab_team_member`; team/global ownership and collaborators must be permission-aware.
- SavedView limits are intentionally small: personal 10, team/global 20. UX and tests should respect those limits instead of treating them as edge-only API failures.
- Advanced view types can be offered only when the underlying DSL model/page has enough field semantics to render them. Missing or incompatible fields should block creation before persistence.
- Public contract work must avoid exposing internal ids, but platform-wide pid-only migration is no longer tracked in this SavedView backlog.

## 2026-06-23 Scope Decision

本轮后续任务不再扩大当前 `codex/saved-view-p2-remaining` 分支范围。Dynamic record pid-only migration 已移出本文档,由独立窗口处理。

当前窗口只承接 SavedView 产品成熟度和测试治理,按 5 个 work package 收口:

| Work package | Included gaps | Scope | Product outcome |
| --- | --- | --- | --- |
| WP1 Advanced view semantic validation | GAP-SV-FU-002 | SavedView backend/frontend/E2E | API 和 UI 都不能创建字段不存在或类型不匹配的高级视图 |
| WP2 E2E redline and coverage matrix | GAP-SV-FU-003 | SavedView E2E/test docs | 把 pass count 拆成 feature/action 证据,移除或隔离直接路由和 API-only 假覆盖 |
| WP3 Collaborator productization | GAP-SV-FU-005 + GAP-SV-FU-009 | SavedView sharing UI/API/ACL | 从 raw `viewConfig.meta` 升级为可审计、可验证、可扩展的协作者管理体验 |
| WP4 Quota UX and test fixtures | GAP-SV-FU-007 | SavedView quota UI + E2E helpers | 用户提前知道 personal 10/team-global 20 上限,测试不再污染长生命周期 runtime |
| WP5 Quick preset lifecycle | GAP-SV-FU-008 | Quick filter preset UX/provider | 系统 preset、已保存个人副本、已编辑副本有明确生命周期 |

非阻塞平台治理项:

| Gap | Reason for exclusion from current branch | Source of truth |
| --- | --- | --- |
| GAP-SV-FU-004 ID-based UserRole mutation endpoint retirement | pid/code 契约治理,需要兼容窗口、运行时 telemetry 和 OpenAPI deprecation 策略 | 本文档保留 backlog,开发单独分支 |
| GAP-SV-FU-006 Audit actor public query contract | `actorPid`/internal actor id 边界属于审计公共契约治理,应和 pid migration 统一口径 | 本文档保留 backlog,开发单独分支 |
| GAP-SV-FU-010 Documentation/OpenAPI pid cleanup | 依赖 pid migration 完成后的全局命名和 OpenAPI 决策 | 跟随 pid migration 后置收口 |

## Backlog Operating Model

本文件是 SavedView 飞书体验对齐后的后续 gap 总账,不是单次 PR 的临时 handover。后续窗口如果继续开发,以本文档为范围边界:

- `Current SavedView follow-up` 行可以在 SavedView 分支内继续实现和验证。
- Non-blocking platform notes 只记录依赖和影响面,不要在当前 SavedView 分支里顺手处理。
- `Status: open` 表示尚未满足验收标准;如果存在半成品代码,也不能改成 DONE,必须等测试矩阵和 `e2e-truth` 证据齐全。
- 完成一个 gap 后,在对应 gap、Delivery Matrix、Verification Matrix 三处同步更新状态和证据。

状态约定:

| Status | Meaning |
| --- | --- |
| `open` | 已确认存在产品/测试/契约缺口,尚未开工或没有可合并实现 |
| `in_progress` | 当前分支有部分实现或测试,但验收矩阵尚未闭合 |
| `blocked_external` | 本分支不做;依赖 audit public contract 或 OpenAPI 迁移分支 |
| `done` | 代码、文档、测试、E2E truth 证据均已落地 |

## Executive Summary

| Priority | Gap | Scope owner | Current state | Next action |
| --- | --- | --- | --- | --- |
| P0 | Advanced view field semantic validation | Current SavedView follow-up | Done in current branch; backend semantic validation, frontend contract updates, and target E2E evidence are present | Keep as regression-covered behavior; do not expand into platform pid migration |
| P0 | E2E redline cleanup for SavedView historical specs | Current SavedView follow-up | Done for WP1-WP5 target scope; historical API-heavy specs are classified rather than claimed as complete UI coverage | Keep `FEATURE_MATRIX.md` updated whenever SavedView E2E changes |
| P1 | Team collaborator management UI/API + ACL validation | Current SavedView follow-up | Done in current branch; Share panel, ACL validator, audit evidence, and golden screenshots are present | Future expansion to team/role principals should be a separate contract change |
| P1 | View quota UX and test isolation | Current SavedView follow-up | Done in current branch; count/limit UI, personal/team/global limits, and quota-safe helper evidence are present | Keep long-lived runtime tests on create-or-reuse policy |
| P2 | Quick filter preset lifecycle | Current SavedView follow-up | Done in current branch; provider registry, saved/edited/reset UI, and browser evidence are present | Plugin-contributed preset examples remain optional future expansion |
| External P1 | ID-based UserRole mutation endpoint retirement | Separate governance branch | PID endpoints exist; old ID endpoints are only `@Deprecated` | Add deprecation telemetry, docs, compatibility window, then remove/admin-gate legacy endpoints |
| External P1 | Audit actor public query contract | Separate audit/public-contract branch | Audit responses are public DTOs; `/by-actor` still uses `actorId` query | Add `actorPid` query path/alias and restrict full internal audit DTOs to admin/verification endpoints |
| External P2 | Documentation/OpenAPI pid cleanup | Follows pid migration | Code paths changed faster than public docs | Update API docs, examples, and generated schema language to pid-first contracts after migration decisions land |

## Backlog Index

| Gap | Priority | Package | Owner lane | Status | Dependencies | Exit evidence |
| --- | --- | --- | --- | --- | --- | --- |
| GAP-SV-FU-002 | P0 | WP1 | SavedView backend/frontend/E2E | done | model/page field metadata availability | Backend negative tests, frontend reason types, E2E invalid mapping evidence |
| GAP-SV-FU-003 | P0 | WP2 | SavedView E2E governance | done | existing historical specs and fixture routes | `FEATURE_MATRIX.md`, direct-route audit, e2e-truth notes |
| GAP-SV-FU-004 | External P1 | UserRole pid endpoint retirement | RBAC/API governance | blocked_external | legacy caller telemetry and compatibility window | Deprecation telemetry, docs, zero-usage evidence before removal |
| GAP-SV-FU-005 | P1 | WP3 | SavedView sharing UX/API | done | collaborator validator, audit event shape | Share panel E2E, backend ACL tests, audit row evidence |
| GAP-SV-FU-006 | External P1 | Audit public actor query | Audit/API governance | blocked_external | actorPid query contract and admin/public split | `actorPid` public query tests and docs |
| GAP-SV-FU-007 | P1 | WP4 | SavedView quota UX/tests | done | manual view limits from count-limit branch | Limit UI tests, quota-safe fixture helper, reuse evidence |
| GAP-SV-FU-008 | P2 | WP5 | Quick preset lifecycle | done | preset origin metadata and provider extraction | Provider registry tests, save/repeat/reset E2E; rename/delete covered by normal management rows |
| GAP-SV-FU-009 | P1 | WP3 | Collaborator ACL contract | done | principal lookup and tenant/team membership rules | Invalid ACL backend tests and documented DTO |
| GAP-SV-FU-010 | External P2 | Docs/OpenAPI pid cleanup | API docs governance | blocked_external | pid migration naming decisions | Search audit, OpenAPI deprecation alignment |

## Current Branch Evidence Snapshot

This snapshot describes the dirty `codex/saved-view-p2-remaining` worktree after the WP1-WP5 follow-up pass. It is completion evidence for the current SavedView scope and a recovery map for the remaining external lanes.

| Package | Evidence present in branch | Closing evidence |
| --- | --- | --- |
| WP1 Advanced view semantic validation | Backend DTO/service/test changes for `modelCode` capability checks and `UNKNOWN_FIELD` / `INCOMPATIBLE_FIELD_TYPE`; frontend service/type updates | Backend focused test, target browser run `17 passed`, kanban/lookup changed-field run `7 passed, 5 skipped`, target truth audit |
| WP2 E2E redline and coverage matrix | `web-admin/tests/e2e/saved-view/FEATURE_MATRIX.md`; timeline spec menu-navigation cleanup and semantic negative API case; shared helper introduced | Target truth audit clean for WP1-WP5 files; historical API-heavy/skipped/direct-route rows classified as debt, not counted as completion |
| WP3 Collaborator productization | Backend collaborator ACL validation and tests; management-panel collaborator UI and component tests; tenant-member based collaborator search service | `SV-FU-001` browser path and screenshots `01/02`; backend invalid ACL and save-only denial tests |
| WP4 Quota UX and fixtures | Management-panel quota count/limit UI and component test; `createOrReuseSavedView` E2E helper | `SV-FU-002` browser path, screenshot `03`, backend personal 10/team-global 20 tests, helper reuse output |
| WP5 Quick preset lifecycle | Preset provider registry, saved/edited/reset state UI, reset/update handler, i18n keys, and unit/component tests | `SV-FU-003` browser path and screenshots `04/05`; provider/unit tests and quick-filter target E2E |

## Remaining Gap Summary After Current Coding Pass

This section is the handoff-level summary after the current coding and verification pass. The only remaining active work is outside the SavedView branch boundary.

| Package | Implemented in current branch | Closing status |
| --- | --- | --- |
| WP1 Advanced view semantic validation | Backend semantic validator uses model metadata and returns stable `UNKNOWN_FIELD` / `INCOMPATIBLE_FIELD_TYPE`; service tests cover unknown and incompatible mapped fields; frontend request/types understand reason codes | done for SavedView scope; malicious/manual invalid mapping remains covered as API contract evidence |
| WP2 E2E redline and coverage matrix | `web-admin/tests/e2e/saved-view/FEATURE_MATRIX.md` exists; timeline spec uses shared create/reuse helper and includes semantic negative API coverage | done for WP1-WP5 target scope; full historical SavedView directory remains classified debt |
| WP3 Collaborator productization | Share panel exists in `ViewManagePanel`; tenant-member search service and component tests cover add/remove; backend validates user principal pid, permission, tenant membership, and audits collaborator changes | done for current user-principal collaborator contract; future team/role principals require a separate contract branch |
| WP4 Quota UX and fixtures | Create/manage flow shows current count/limit and disables create when limit is reached; component test covers limit state; E2E helper can create or reuse matching SavedViews | done for personal 10 and team/global 20; long-lived runtime policy is create-or-reuse, not destructive cleanup |
| WP5 Quick preset lifecycle | Preset definitions are provider-based; duplicate provider/key behavior has tests; system preset chip can show saved/edited state; reset-to-system handler and i18n keys are wired; unit/component tests cover state derivation | done for save/repeat/personal copy/edited/reset lifecycle; rename/delete are normal management-path behavior in the matrix |
| Non-blocking platform governance notes | UserRole legacy endpoint retirement, audit actorPid query, and OpenAPI cleanup are documented here with owner boundaries | Keep out of this branch; open separate branches for telemetry/docs cleanup |

## Latest Verification Fallout And Follow-up Tasks

本节记录最近一次 SavedView targeted E2E 暴露的具体后续任务。它们不是新的产品范围扩张,而是完成 WP1-WP5 前必须收口的测试夹具/契约/可观测性 gap。

Run context:

- Runtime: `saved-view-p2-e2e-79`.
- Setup import: `tests/api/setup/01-multi-role-users.spec.ts`, `03-import-test-fixtures.spec.ts`, `04-import-oss-plugins.spec.ts` passed.
- Target command:
  `PW_PROFILE=fast PW_WORKERS=1 pnpm playwright test -c playwright.noweb.config.ts tests/e2e/saved-view/saved-view-quick-filters.spec.ts tests/e2e/saved-view/saved-view-timeline.spec.ts tests/e2e/saved-view/saved-view-follow-up-golden.spec.ts --project=chromium --no-deps --reporter=line`
- Checkpoint A result: quick-filter suite passed, follow-up golden had 2 red tests, timeline had 3 red tests; total target result was `12 passed / 5 failed`.
- Checkpoint B result after fixture/helper fixes: timeline passed and helper reuse became observable; remaining red tests are `SV-FU-001`, `SV-FU-002`, and `SV-FU-003`; total target result was `14 passed / 3 failed`.
- Checkpoint C focused follow-up result after direct-backend fixture setup and preset-copy stabilization: `saved-view-follow-up-golden.spec.ts` passed `3/3`. This proved the three golden flows could pass in isolation; at that point WP1-WP5 still required the combined targeted SavedView run and `e2e-truth` audit, which were completed in Checkpoint D.
- Checkpoint D final combined result: quick-filter + timeline + follow-up-golden target run passed `17/17`; changed-field kanban/lookup run passed `7 passed, 5 skipped`; target-scope `e2e-truth` grep found no skip/fixme/wait/direct-route/threshold redline in the WP1-WP5 files.

| ID | Work package | Failing path | Observed failure | Likely root cause | Required fix | Exit evidence |
| --- | --- | --- | --- | --- | --- | --- |
| SV-VERIFY-001 | WP3 | `SV-FU-001: team owner manages collaborators and sees audit evidence` | `POST /api/admin/users` returned `400 Bad parameter`, `displayName` length must be 1-50 | E2E fixture uses `SavedView Collaborator ${uniqueId(...)}`; generated value can exceed backend validation limit | Shorten generated display name while keeping email/pid unique | `SV-FU-001` reaches share panel, adds collaborator, sees audit row, captures collaborator screenshots |
| SV-VERIFY-002 | WP3/WP4 | `SV-FU-002: team quota limit is visible before creating another shared view` | `POST /api/org/teams/{teamPid}/members` returned `422 User not found` for a rounded numeric user id | Playwright code converts backend Long user ids to JS `Number`, losing precision beyond `Number.MAX_SAFE_INTEGER` | Treat user ids as strings in E2E helpers, or move team-member fixture API to pid-based contract; do not parse Long ids as JS numbers | Team quota E2E creates 20 team views, UI shows `20/20`, create is disabled, screenshot captured |
| SV-VERIFY-003 | WP1/WP4 | `TL-001`, `TL-002`, `TL-004` | `createOrReuseSavedView` returned empty `pid` | Helper hides create response body on failure and does not expose whether failure is quota, semantic validation, pageKey mismatch, or response shape drift | Make helper log/throw structured create failure context when a test expects success; keep graceful empty return only for explicit negative cases | Timeline success tests fail with actionable API body if create is rejected; no silent empty pid on positive paths |
| SV-VERIFY-004 | WP1/WP4 | Timeline API success paths | Timeline create request currently omits `pageKey` while helper matching is page-aware | Existing/reusable views may be page-scoped; create/reuse behavior can diverge from `e2et_order_list` page semantics and quota matching | Pass `pageKey: e2et_order_list` in positive timeline creation helper, or document why model-only timeline is intended | Timeline create/reuse is deterministic across long-lived runtimes; matrix records model/page scope |
| SV-VERIFY-005 | WP2 | Target run reportability | `results.json` did not expose a useful failure summary; error contexts exist only under Playwright artifacts | Current report handoff requires manually opening artifact folders; future window may miss exact red reasons | Add validation report section after rerun with failing/passing test names, artifact paths, and root-cause classification | Final validation report links screenshots/artifacts and updates `FEATURE_MATRIX.md` rows |
| SV-VERIFY-006 | WP3/WP4 | `SV-FU-001` after display-name fix | Team member add still returns `422 User not found`, now for an apparently string-preserved user id | Either the test still serializes a rounded Long somewhere, or the team-member API contract is too ID-centric for Playwright fixtures | Debug raw `/api/admin/users` response versus team-member request payload; prefer pid-based team member fixture support if API contract work is needed | Collaborator flow can add a provisioned user without JS Long precision loss |
| SV-VERIFY-007 | WP4 | `SV-FU-002` team quota browser path | Team scope option is not visible in the SavedView create combobox after member add fails | Team quota UI evidence depends on a valid team membership fixture and refreshed accessible team scopes | Fix membership fixture first, then assert the team scope option and `20/20` limit state from the browser | Team quota test proves UI limit state without relying on stale or invisible team data |
| SV-VERIFY-008 | WP5 | `SV-FU-003` quick preset lifecycle | Clicking save-as-personal on `modified_this_week` leaves URL at `?preset=modified_this_week`; no `view=` appears within timeout | Either save-as-personal failed under quota/existing-copy conditions, or the UI no longer switches to the saved personal view deterministically | Instrument the save response and existing-copy lookup; make the browser flow quota-safe and assert saved-copy switching or documented fallback | Quick preset lifecycle E2E captures saved, edited, and reset states from the real list-page entry |

Immediate next action order after Checkpoint D:

1. Do not reopen WP1-WP5 in this branch unless a regression appears; use the commands in the validation report as the repeatable gate.
2. Keep historical `tests/e2e/saved-view` debt classified in `FEATURE_MATRIX.md`; convert old direct-route/API-heavy rows opportunistically when those feature areas are next touched.
3. Do not reopen WP1-WP5 for non-blocking platform/public-contract notes such as UserRole legacy endpoint retirement, audit actorPid public query, and OpenAPI/docs cleanup.

Current status after Checkpoint D:

| Verification task | Status | What changed | Closing evidence |
| --- | --- | --- | --- |
| SV-VERIFY-006 | resolved | E2E fixture bypasses BFF JSON Long rounding by using backend-direct setup with bearer auth for admin user/team-member setup | Combined target rerun passed `17/17`; collaborator screenshots `01/02` captured |
| SV-VERIFY-007 | resolved | Team scope becomes visible after valid membership setup; quota flow can reach `20/20` browser state | Combined target rerun passed; screenshot `03-team-quota-limit.png` captured |
| SV-VERIFY-008 | resolved | Test ensures a modified-this-week personal preset copy exists and waits for saved state before save-as-personal | Combined target rerun passed; screenshots `04/05` captured for edited/reset states |

Do not delete the red rows above. They are useful root-cause history for future E2E failures. Close them by adding a validation report entry, not by erasing the failure record.

## Open Task Register

The table below is the actionable backlog. `Evidence owner` means where the closing proof must land, not a person.

| Task ID | Gap | Work | Evidence owner | Current status |
| --- | --- | --- | --- | --- |
| SV-WP1-A | GAP-SV-FU-002 | Keep backend semantic validator shared by create/update/capability check and stable reason codes | `SavedViewServiceImplTest`, capability DTO/service tests | done |
| SV-WP1-B | GAP-SV-FU-002 | Add/keep browser blocked-submit path for incompatible advanced mapping | SavedView E2E + feature matrix | done |
| SV-WP1-C | GAP-SV-FU-002 | Preserve malicious/manual invalid mapping API negative evidence | feature matrix + validation report | done |
| SV-WP2-A | GAP-SV-FU-003 | Update `FEATURE_MATRIX.md` after final target run with UI/API/setup/deferred labels | `web-admin/tests/e2e/saved-view/FEATURE_MATRIX.md` | done |
| SV-WP2-B | GAP-SV-FU-003 | Run grep/truth audit for skips, fixmes, `waitForTimeout`, direct `/p/`, retry/threshold patterns | validation report | done |
| SV-WP2-C | GAP-SV-FU-003 | Separate historical API-heavy specs from current-scope browser completion claims | feature matrix + final response | done |
| SV-WP3-A | GAP-SV-FU-005/GAP-SV-FU-009 | Keep Share/Collaborators panel for team/global views with owner/manage/save-only affordance | component tests + E2E screenshot | done |
| SV-WP3-B | GAP-SV-FU-005/GAP-SV-FU-009 | Validate collaborator principal pid, tenant/team membership, permission enum, and meta tamper denial | backend service tests | done |
| SV-WP3-C | GAP-SV-FU-005/GAP-SV-FU-009 | Decide whether generic `viewConfig.meta` update is acceptable long term or needs a dedicated collaborator sub-command | backlog/API doc decision | done: current branch keeps validated generic update; future dedicated sub-command is optional contract hardening |
| SV-WP3-D | GAP-SV-FU-005/GAP-SV-FU-009 | Prove audit row appears after collaborator change from browser management panel | E2E screenshot + audit DTO tests | done |
| SV-WP4-A | GAP-SV-FU-007 | Preserve personal 10 and team/global 20 backend limit tests, including autosave exclusion | backend service tests | done |
| SV-WP4-B | GAP-SV-FU-007 | Show quota count/limit and disabled/explained create state in UI | component test + E2E screenshot | done |
| SV-WP4-C | GAP-SV-FU-007 | Use `createOrReuseSavedView` for quota-safe long-runtime specs and record reuse vs create | helper + target run output | done |
| SV-WP5-A | GAP-SV-FU-008 | Keep quick preset provider registry and conflict/duplicate tests | unit tests | done |
| SV-WP5-B | GAP-SV-FU-008 | Prove saved/edited/reset lifecycle from real list-page entry | follow-up golden E2E + screenshot | done |
| SV-WP5-C | GAP-SV-FU-008 | Tie normal rename/delete management paths back to personal preset copy lifecycle in the matrix | feature matrix | done |
| SV-EXT-B | GAP-SV-FU-004 | Add UserRole legacy endpoint telemetry/deprecation window before removal | separate RBAC/API branch | blocked_external |
| SV-EXT-C | GAP-SV-FU-006 | Add public `actorPid` audit query and document internal/admin split | separate audit branch | blocked_external |
| SV-EXT-D | GAP-SV-FU-010 | Clean docs/OpenAPI pid language after migration naming settles | follow-up docs branch | blocked_external |

## Current Work Package Contracts

### WP1 Advanced View Semantic Validation

Product intent:

- Users should never see an advanced view that was created successfully but cannot render because the mapped fields do not exist or have incompatible types.
- API callers should receive deterministic validation errors before invalid config is persisted.

Implementation contract:

- Add one backend semantic validator for advanced view mappings, shared by create/update and capability check reason taxonomy where possible.
- Validate field existence against the model/page field metadata used by the DSL list page.
- Validate type families for kanban, calendar, gallery, gantt, tree, and timeline.
- Keep frontend capability gate aligned with backend accepted type families.

Test contract:

- Backend service/controller negative tests for unknown field code and wrong field type.
- Frontend unit tests for type-family decision helpers.
- E2E negative path that proves the UI cannot submit an incompatible advanced mapping, plus API contract evidence for malicious/manual payloads.

### WP2 SavedView E2E Redline And Coverage Matrix

Product intent:

- Future SavedView completion reports must be evidence-based, not pass-count-based.
- User workflows must be validated from product entry points, with API calls limited to setup or explicit contract checks.

Implementation contract:

- Add or maintain a SavedView feature/action matrix that lists selector, create, manage, share, copy, default, audit, quick presets, advanced config, and view-type coverage.
- Reclassify direct `/p/` checks as smoke/loadability specs or replace them with sidebar/menu navigation.
- Document API-created SavedViews as setup/contract evidence, not UI-flow evidence.

Test contract:

- `tests/e2e/saved-view` has no hidden skip/fixme product gaps.
- Direct route usage is documented with reason and paired with a real user-path assertion.
- `e2e-truth` review can trace each completion claim to a feature/action row.

### WP3 Collaborator Productization

Product intent:

- Team/shared SavedViews should have Feishu-grade sharing affordance: owner/manage users can grant access, users can understand their effective permission, and permission failures are clear.
- Raw collaborator metadata should not become an unvalidated public mutation contract.

Implementation contract:

- Add a Share/Collaborators section in the management panel for team views.
- Introduce a validated collaborator mutation path or strict sub-command around `viewConfig.meta.collaborators`.
- Validate supported principal types, principal existence, tenant/team membership, and permission enum values.
- Emit audit events for collaborator changes.

Test contract:

- Backend tests cover invalid permission, invalid principal, cross-tenant principal, save-only meta tampering, and manage-level collaborator update.
- E2E covers owner adds save collaborator, collaborator saves config, collaborator cannot rename/delete/share, owner upgrades collaborator to manage, and audit row appears.

### WP4 Quota UX And Quota-Aware Fixtures

Product intent:

- Users should understand manual SavedView limits before failed submission: personal 10, team/global 20.
- Long-lived E2E runtimes should not fail randomly after accumulating historical views.

Implementation contract:

- Surface current count/limit in create/manage flow.
- Disable or explain create action when the relevant limit is reached.
- Provide a deterministic `createOrReuseSavedView` helper that matches model/page/scope/config and records reuse vs create in test output.

Test contract:

- Backend tests keep enforcing personal/team/global limits and implicit autosave exclusion.
- UI/component or E2E test covers personal limit reached messaging.
- E2E helper test covers reuse behavior without deleting audit-relevant data.

### WP5 Quick Filter Preset Lifecycle

Product intent:

- Quick filters remain daily lightweight chips, while saved personal copies behave like normal SavedViews and show clear saved/edited/reset states.

Implementation contract:

- Mark system presets that already have a personal saved copy.
- Support open/switch, rename/delete personal copy, and reset/update-from-system-preset behavior.
- Extract preset provider registration so future plugins can contribute presets without editing central list-page code.

Test contract:

- Unit tests cover provider registry lookup, duplicate/conflict resolution, and origin preset mapping.
- E2E covers save, repeat save, switch to personal preset, rename personal copy, delete personal preset, and reset/update behavior.

## Implementation Task Breakdown

### WP1 Task List: Advanced View Semantic Validation

| Task | Layer | Required work | Evidence |
| --- | --- | --- | --- |
| WP1-T1 | Backend | Validate mapped field existence for kanban, calendar, gallery, gantt, tree, timeline on create/update | `SavedViewServiceImplTest` unknown-field cases |
| WP1-T2 | Backend | Validate type families for date, image, groupable/resource, hierarchy parent, and title/display fields | wrong-type negative tests with stable reason codes |
| WP1-T3 | Backend/API | Reuse the semantic validator in capability check reason taxonomy | capability-check test returns `UNKNOWN_FIELD` / `INCOMPATIBLE_FIELD_TYPE` |
| WP1-T4 | Frontend | Extend capability request/response types and UI blocked/degraded reasons | unit tests for reason rendering/type handling |
| WP1-T5 | E2E | Add malicious/manual invalid mapping API case and one browser blocked-submit case | SavedView feature matrix row plus e2e output |

Current branch note: backend DTO/service/test and frontend type/service-test changes are complete for the current SavedView scope; rerun the verification matrix before merge if these files change again.

### WP2 Task List: E2E Redline And Coverage Matrix

| Task | Layer | Required work | Evidence |
| --- | --- | --- | --- |
| WP2-T1 | Test docs | Maintain `web-admin/tests/e2e/saved-view/FEATURE_MATRIX.md` as the feature/action source of truth | matrix covers selector/create/manage/share/copy/default/audit/quick preset/view types |
| WP2-T2 | E2E | Replace timeline direct `/p/e2et_order` feature checks with sidebar/menu navigation or mark as smoke-only | grep audit for direct route usage and comments |
| WP2-T3 | E2E | Classify API setup/readback rows as setup/contract evidence, not UI coverage | matrix row evidence labels |
| WP2-T4 | E2E truth | Re-run skip/fixme/threshold/retry/direct-route audit for SavedView scope | e2e-truth section in final validation report |
| WP2-T5 | Reporting | Future completion reports separate UI path evidence from API/setup evidence | Delivery Matrix updated with exact command outputs |

Current branch note: `FEATURE_MATRIX.md` and timeline direct-route cleanup are complete for the WP1-WP5 target scope; keep historical route/API-heavy debt classified when future specs are edited.

### WP3 Task List: Collaborator Productization And ACL

| Task | Layer | Required work | Evidence |
| --- | --- | --- | --- |
| WP3-T1 | Backend | Add strict collaborator ACL validation for supported principal types, pid presence, permission enum, and principal existence | invalid principal/permission/missing user tests |
| WP3-T2 | Backend | Prevent save-only collaborators from mutating managed meta while preserving legitimate config saves | raw meta tamper test |
| WP3-T3 | Backend/API | Add collaborator mutation endpoint or validated update sub-command; avoid exposing raw `viewConfig.meta` as an unguarded public contract | controller/service contract tests |
| WP3-T4 | Frontend | Add Share/Collaborators section in the management panel for team/global views | component tests for owner/manage/save-only states |
| WP3-T5 | E2E | Owner grants save, collaborator saves config but cannot rename/delete/share, owner upgrades to manage, audit row appears | permission-sensitive browser test |
| WP3-T6 | Docs | Document collaborator DTO, current `user` principal support, and future team/role expansion rules | backlog/status update or API docs |

### WP4 Task List: Quota UX And Quota-Safe Fixtures

| Task | Layer | Required work | Evidence |
| --- | --- | --- | --- |
| WP4-T1 | Backend | Keep personal 10 and team/global 20 manual-view limits covered, including duplicate/copy paths and implicit autosave exclusion | focused service/controller tests |
| WP4-T2 | Frontend | Show current count/limit in create/manage flow and disable/explain create when limit reached | component test for limit reached state |
| WP4-T3 | E2E helper | Introduce `createOrReuseSavedView` with model/page/scope/config matching and explicit reuse logging | helper test or target spec evidence |
| WP4-T4 | E2E | Cover personal limit reached UX without destructive cleanup of audit-relevant data | browser evidence |
| WP4-T5 | Docs | State when tests reuse persisted runtime data versus requiring fresh DB | feature matrix/test notes |

### WP5 Task List: Quick Filter Preset Lifecycle

| Task | Layer | Required work | Evidence |
| --- | --- | --- | --- |
| WP5-T1 | Frontend | Mark system presets that already have a personal saved copy | component/E2E assertion |
| WP5-T2 | Frontend | Represent unsaved preset, saved personal copy, edited personal copy, and reset/update state | unit tests for state derivation |
| WP5-T3 | Frontend/platform | Extract preset provider registry with conflict/duplicate resolution | provider registry unit tests |
| WP5-T4 | Backend/API | Add/update contract only if reset/update needs persisted preset-origin metadata changes | service/API tests if backend changes |
| WP5-T5 | E2E | Cover save, repeat save, switch to personal copy, rename, delete, and reset/update behavior | browser workflow evidence |

## External Workstream Breakdown

| External gap | Required follow-up | Current branch rule |
| --- | --- | --- |
| GAP-SV-FU-004 UserRole legacy ID endpoint retirement | Add telemetry/warning headers/docs, prove zero first-party legacy callers, then remove or admin-gate deprecated endpoints | Do not remove compatibility endpoints in this branch |
| GAP-SV-FU-006 Audit actor public query | Add `actorPid` public query and document admin/internal actor-id boundary | Do not mix with SavedView collaborator UI unless audit API branch lands first |
| GAP-SV-FU-010 Docs/OpenAPI pid cleanup | Rewrite public examples after pid migration naming decisions settle | Do not mass-rename docs before platform migration contract is final |

## Gap Details

### GAP-SV-FU-002: Advanced View Field Semantic Validation

Status: done.

Current state:

- `SavedViewServiceImpl.validateViewTypeConfig` blocks missing required config fields and rejects unknown/incompatible mapped field codes with stable reason codes.
- Frontend capability logic suggests mappings from field metadata and understands backend capability reason taxonomy.
- Timeline requires `timelineStartField + timelineResourceField`, with `timelineEndField` optional.
- Final evidence: backend focused test passed; target browser run passed `17/17`; kanban changed-field negative contract rejects date group fields with `INCOMPATIBLE_FIELD_TYPE`.

Original gap:

- Backend checks field presence in config, but does not fully validate that each mapped field exists on the target model/page and has an acceptable data type.
- Existing E2E can create a timeline with arbitrary field codes if they are non-empty, because the contract currently focuses on persisted config shape.
- This means malformed API callers can still create advanced views that pass create/update validation but degrade at render/runtime.

Target behavior:

- Backend validates field existence against model metadata for all advanced view mappings.
- Backend validates accepted data types:
  - calendar/gantt/timeline date fields must be date/datetime-compatible.
  - timeline resource fields must be groupable/displayable.
  - gallery image field must be image/file/avatar/attachment-compatible.
  - tree parent field must be hierarchy-compatible or explicitly supported.
  - kanban group field must be groupable.
- Error response should return stable missing/invalid reason codes, not only free-text messages.

Acceptance criteria:

- API create/update rejects unknown or incompatible advanced view field codes.
- `checkCapability` and `create/update` share the same semantic validator or reason taxonomy.
- E2E includes negative API cases for invalid field code and wrong field type.
- UI create flow cannot submit incompatible mappings even if manually altered.

### GAP-SV-FU-003: SavedView E2E Redline and Coverage Debt

Status: done for WP1-WP5 target scope; historical directory debt remains classified.

Current evidence from `#1028`:

- Scoped target run passed: quick-filter + timeline `13/13`.
- `e2e-truth` grep found no skip/fixme, threshold, retry, or `waitForTimeout` in the two target specs.
- `saved-view-timeline.spec.ts` still has 2 direct `page.goto('/p/e2et_order')` route checks.
- Historical SavedView specs include API-heavy setup/verification paths and earlier skipped AI/fixture-condition tests.

Original gap:

- Targeted pass is not the same as full SavedView historical E2E maturity.
- Direct route checks are acceptable as smoke/loadability style checks only when documented, but they should not be the main feature-flow evidence.
- API-created SavedViews are useful contract checks, but UI creation/editing flows need separate browser evidence.

Target behavior:

- For user-facing workflows, navigate through the real sidebar/menu or documented product entry.
- Keep API calls for setup/contract verification, but pair them with browser assertions for each feature/action point.
- Build and maintain a feature/action coverage matrix for SavedView.

Acceptance criteria:

- `tests/e2e/saved-view` has an explicit matrix covering selector, create, manage, share, copy, default, audit, quick presets, advanced view config, and each view type.
- Direct `/p/` route checks are either moved to smoke specs or justified in comments.
- No product gap is hidden behind skip/fixme.
- Future completion reports state UI/API split and do not claim 100% coverage from pass count alone.

### GAP-SV-FU-004: ID-Based UserRole Mutation Endpoint Retirement

Status: blocked_external.

Current state:

- New endpoints exist:
  - `/api/user-roles/remove-by-pid`
  - `/api/user-roles/sync-by-pid`
  - `/api/user-roles/batch-assign-by-pid`
  - `/api/user-roles/batch-remove-by-pid`
  - existing `/assign-by-code` and `/assign-by-pid`
- Old ID-based mutation endpoints remain and are marked `@Deprecated`.
- E2E setup now uses `memberPid + rolePids` where touched.

Gap:

- Deprecation is currently source-level only.
- There is no runtime telemetry, warning header, OpenAPI deprecation note, or removal date.
- Internal scripts or older admin clients may still call numeric-id endpoints without visibility.

Target behavior:

- Add deprecation telemetry for old mutation endpoints.
- Emit warning response metadata or logs that include endpoint, caller, tenant, and count.
- Update API docs and setup examples to pid/code paths only.
- Define compatibility window and removal/admin-gate criteria.

Acceptance criteria:

- Dashboard/query can show legacy endpoint usage by tenant/caller.
- CI or API docs gate prevents new first-party code from calling legacy ID endpoints.
- Removal PR has usage evidence showing zero or accepted residual legacy callers.

### GAP-SV-FU-005: Team Collaborator Management Productization

Status: done.

Current state:

- Backend stores `viewConfig.meta.collaborators`.
- Permission model supports user principal permissions:
  - `view`
  - `save`
  - `manage`
- Save-only collaborator can update view config/default but cannot rename, delete, manage, share, or change managed meta.
- Manage-level updates can edit metadata and collaborators through the generic view update path.

Original gap:

- The current branch adds a first Share/Collaborators panel and backend validation, but the browser permission flow is not yet proven.
- Collaborator changes still travel through the generic view update shape instead of a dedicated collaborator command endpoint.
- Current storage location is flexible and guarded in service code, but future clients could still misuse raw `viewConfig.meta` without a clearer public DTO/sub-command contract.

Target behavior:

- View management panel has a Share section for team views.
- Manage users can add/remove collaborators by user pid, set permission, and see effective access.
- Save-only users cannot alter collaborators, lock flags, plugin ownership, or other managed meta.
- Backend exposes dedicated collaborator mutation endpoints or keeps the current generic update path behind a documented validated sub-command contract.

Acceptance criteria:

- Add collaborator UI E2E:
  - owner adds save collaborator.
  - collaborator can save config.
  - collaborator cannot rename/delete/share.
  - owner upgrades collaborator to manage.
  - audit records collaborator change.
- Add backend tests for invalid principal, invalid permission, cross-tenant user, and raw meta tampering.

### GAP-SV-FU-006: Audit Query Public Contract and Admin Split

Status: blocked_external.

Current state:

- `/api/audit/trail`, `/api/audit/by-actor`, and `/api/audit/by-command` return `AuditTrailPublicDTO`.
- Public DTO hides `id`, `tenantId`, `entityId`, `actorId`, actor IP, snapshots, and hash chain internals.
- SavedView audit events expose `sequenceNo` for stable UI keys.

Gap:

- `/api/audit/by-actor` still takes `actorId` as a query input.
- Compliance and verification endpoints still use internal audit entities by design, but the admin/internal boundary is not yet documented clearly enough.
- SavedView management UI may eventually need actor pid/display filters instead of internal actor ids.

Target behavior:

- Add `actorPid` query alias or replacement for public actor audit queries.
- Keep internal actor id available only to admin/internal endpoints where justified.
- Document which audit endpoints are public UI surfaces and which are verification/admin surfaces.

Acceptance criteria:

- Public actor audit can be queried with `actorPid`.
- Response remains public DTO.
- Tests assert internal ids are not exposed in public actor query response.
- OpenAPI/docs mark actor-id path as legacy or admin-only.

### GAP-SV-FU-007: View Quota UX and Quota-Aware Test Fixtures

Status: done.

Current state:

- Backend limits:
  - personal: 10 manual views per user/model/page.
  - team/global: 20 manual views per scope/model/page.
  - implicit autosave is excluded.
- During target E2E reruns, long-lived DB hit personal 10 view limit; timeline spec now reuses same-config views.
- Quick preset save-as-personal is idempotent when the preset was already saved.

Original gap:

- User-facing quota state needs a clearer UX than only API error handling.
- E2E specs that create SavedViews can exhaust quota in long-lived runtimes if they do not use the shared helper.
- A shared quota-safe helper exists in the current branch, but its reuse behavior still needs browser-run evidence and matrix notes.

Target behavior:

- View creation UI shows count and limit before submit.
- When the limit is reached, create actions explain how to delete or reuse views.
- Test helpers use deterministic reuse or cleanup policy without destroying audit evidence unexpectedly.

Acceptance criteria:

- UI test covers personal limit reached state.
- API test covers team/global limit reached state.
- Shared E2E helper supports `createOrReuseSavedView` with config matching.
- Docs state whether tests may reuse persisted runtime data or require fresh DB.

### GAP-SV-FU-008: Quick Filter Preset Lifecycle

Status: done.

Current state:

- Built-in quick filters are rendered as system preset entries.
- Active preset can be saved as a personal SavedView.
- Saved personal view records `viewConfig.meta.originPresetKey`.
- Repeated save switches to existing personal preset view instead of creating duplicates.
- Current branch adds a provider registry, saved/edited chip states, reset-to-system behavior, i18n keys, and unit/component tests.

Original gap:

- Browser E2E has not yet proven saved/edited/reset states from the real list-page entry.
- Rename/delete paths for personal preset copies are covered by normal SavedView management behavior, but not yet tied back to the preset lifecycle matrix.
- Plugin-contributed preset providers have unit coverage, but no plugin-level integration example yet.

Target behavior:

- System preset row indicates saved state.
- Saved personal preset can be opened, renamed, deleted, or reset to current built-in preset definition.
- Future plugins can register preset providers without editing central list-page code.

Acceptance criteria:

- UI differentiates unsaved preset, saved personal preset, and edited personal copy.
- E2E covers save, repeat save, switch, rename personal copy, delete personal preset, and reset/update.
- Unit tests cover provider registry resolution, conflict handling, saved/edited state derivation, and reset affordance.

### GAP-SV-FU-009: Collaborator ACL Validation and Extensibility

Status: done for current `user` principal contract.

Current state:

- `CollaboratorAcl` is a flexible object in `ViewConfig.Meta`.
- Current branch validates `user` principal ACL entries, supported permission values, blank pid, and tenant membership.

Original gap:

- Permission values are service-validated, but the public API contract still accepts string payloads rather than a dedicated enum DTO.
- Principal existence and tenant membership are validated for `user`, but only through the SavedView update path.
- Future team/role/group principals are not modeled.

Target behavior:

- Permission value validation: only `view`, `save`, `manage` unless a migration explicitly adds more.
- Principal type validation with clear supported values.
- Optional expansion:
  - user pid.
  - team pid.
  - role code or role pid.

Acceptance criteria:

- Invalid ACL payload is rejected by backend tests.
- Collaborator DTO is documented.
- Future principal expansion has migration notes and UI rules.

### GAP-SV-FU-010: Documentation and OpenAPI Contract Cleanup

Status: blocked_external.

Current state:

- Implementation and tests have moved toward pid/code contracts.
- Some public docs, OpenAPI descriptions, spec names, and DSL examples still use legacy language like `recordId`, `memberId`, `roleId`, or generic `id`.

Gap:

- Developers may copy old examples and keep extending legacy contracts.
- API consumers cannot easily tell which ID fields are public pid, legacy alias, or internal-only.

Target behavior:

- Public examples use pid/code naming.
- Deprecated endpoints and fields are marked consistently.
- Internal-only admin endpoints are documented separately.

Acceptance criteria:

- Search audit for docs/examples finds no new first-party use of numeric public ids.
- OpenAPI deprecation annotations align with runtime behavior.
- DSL examples use `recordPid`/`targetRecordPid` when the platform migration is ready.

## Delivery Matrix

This matrix is the closing checklist for the 5 current-window work packages. It records the evidence that allows WP1-WP5 to be treated as done for this SavedView branch.

| Work package | User path | Backend/integration evidence | Frontend/unit evidence | Web E2E evidence | Golden/truth evidence | Completion status |
| --- | --- | --- | --- | --- | --- | --- |
| WP1 Advanced view semantic validation | List page -> SavedView selector -> advanced mapping validation | Create/update/capability reject unknown and incompatible mapped fields | Capability/service TS contracts and list-page tests | Timeline browser path plus kanban invalid group field contract evidence | Target truth audit clean; `FEATURE_MATRIX.md` separates UI and API contract evidence | done |
| WP2 E2E redline and coverage matrix | Sidebar/menu -> DSL list page -> SavedView daily workflows | API setup paths documented as setup only | N/A except shared helper/test selectors | Selector, quick preset, timeline, collaborator, quota, and preset lifecycle rows have target user-path assertions | Target files have no skip/fixme/wait/direct-route/threshold redlines; historical directory debt is classified | done |
| WP3 Collaborator productization | Owner/manage user -> management panel -> Share -> add/update/remove collaborator | Validator covers invalid principal/permission/tenant membership/meta tamper; audit metadata emitted | Share panel state and permission affordance tests | `SV-FU-001` owner share/audit browser path passed | Screenshots `01-collaborator-share-panel.png` and `02-collaborator-audit-panel.png` | done |
| WP4 Quota UX and fixtures | SavedView create/manage flow at personal/team/global limits | Limit tests for personal 10, team/global 20, implicit autosave exclusion | Limit display/disabled/create error state tests | `SV-FU-002` team quota browser path passed; helper records create/reuse | Screenshot `03-team-quota-limit.png`; no destructive cleanup required | done |
| WP5 Quick preset lifecycle | Quick filter chip -> save as personal -> switch/reset | Existing-copy lookup/readback and preset-origin update path | Provider registry, saved/edited/reset state tests | `SV-FU-003` saved/edited/reset browser path passed; repeat-save path covered in quick-filter spec | Screenshots `04-preset-edited-state.png` and `05-preset-reset-state.png`; management rename/delete are normal SavedView rows | done |

## Verification Matrix

最终验证按下表完成。命令可以按实际文件名调整,但不能用 pass count 替代功能点说明。

| Area | Command / evidence | Required result | Covers |
| --- | --- | --- | --- |
| Backend SavedView service | `cd platform && ./gradlew :test --tests com.auraboot.framework.view.service.impl.SavedViewServiceImplTest` | `BUILD SUCCESSFUL` | WP1 semantic validation, WP3 ACL, WP4 quota service behavior |
| Backend controller/API | Current branch kept collaborator mutation inside validated SavedView update path; no separate controller endpoint was added | Covered by service tests and browser/API readback | API rejection shape, audit/public DTO compatibility |
| Frontend unit/component | `cd web-admin && pnpm test:unit:run app/framework/meta/rendering/pages/__tests__/ListPageContent.test.ts app/framework/meta/rendering/pages/list/__tests__/quickFilterPresets.test.ts app/framework/meta/rendering/pages/list/__tests__/PresetViewBar.test.tsx app/framework/meta/rendering/pages/list/__tests__/dsl-list-i18n-resources.test.ts app/framework/smart/components/view/__tests__/ViewManagePanel.test.tsx app/shared/services/__tests__/savedViewService.test.ts` | 6 files / 129 tests passed | capability reasons, share panel, quota UX, preset lifecycle |
| Frontend typecheck | `cd web-admin && pnpm typecheck` | PASS | public TS contracts and component wiring |
| E2E feature matrix | Targeted SavedView command for quick-filter + timeline + follow-up golden | `17 passed` | UI workflows from real entry points |
| Changed-field contract E2E | Kanban grouping + lookup-field target command | `7 passed, 5 skipped`; skips are historical lookup fixture conditions | WP1 kanban semantic contract and historical fixture classification |
| E2E truth audit | Target-scope grep/audit for `test.skip`, `test.fixme`, `waitForTimeout`, direct `/p/`, thresholds, retries | No target-scope redline found; historical directory exceptions documented in `FEATURE_MATRIX.md` | prevents false completion claims |
| Golden evidence | `web-admin/test-results/saved-view-follow-up-golden/*.png` | 5 screenshots, each 1280x720 | product-quality verification for share/audit/quota/preset states |

## Current Branch Recovery Notes

如果后续窗口从当前 dirty worktree 接手,先做实时校准,不要直接相信旧摘要:

- Check branch and PR: `git status --short --branch`, `gh pr view 1028`.
- Inspect unstaged changes before editing: `git diff -- docs/backlog/2026-06-23-saved-view-post-pr-follow-up-gaps.md platform/src/main/java/com/auraboot/framework/view/service/impl/SavedViewServiceImpl.java web-admin/tests/e2e/saved-view/saved-view-timeline.spec.ts`.
- Treat WP1-WP5 as complete for current SavedView scope, but rerun the verification matrix before merge or PR update if code changes again.

## Definition Of Done

For any current-window package:

- Product behavior is implemented through the existing SavedView/DSL list-page architecture, not a one-off route or hardcoded demo path.
- Backend rejects invalid or unauthorized mutations before persistence.
- UI shows explicit feedback for loading, empty, validation failure, permission denial, and destructive actions when the package touches those states.
- Tests include focused backend/unit coverage and at least one browser path for every user-visible command.
- Completion report includes command output summary, feature/action coverage matrix, and `e2e-truth` review notes.

## Remaining Execution Order

| Order | Work package | Suggested branch | Reason |
| --- | --- | --- | --- |
| external-2 | UserRole legacy endpoint retirement telemetry | `codex/user-role-pid-endpoint-deprecation` | Governance work tied to pid/code contract migration |
| external-3 | Audit actorPid public query | `codex/audit-public-actor-pid-query` | Audit public-contract cleanup should align with pid migration language |
| external-4 | Documentation/OpenAPI pid cleanup | follows pid migration | Avoids rewriting docs before public-id naming decisions settle |

## Reporting Rules

When reporting future progress from this backlog:

- Do not say "SavedView 100% complete" unless feature/action coverage matrix and `e2e-truth` both pass with no product gaps.
- Separate UI browser evidence from API/setup evidence.
- If legacy ID paths remain for compatibility, report them as compatibility debt with telemetry/removal status.
- If E2E uses direct `/p/` navigation or API-created records, state why and what user-path evidence pairs with it.
