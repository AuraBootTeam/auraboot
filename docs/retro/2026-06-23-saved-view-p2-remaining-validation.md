---
type: retro
status: active
created: 2026-06-23
relates_to:
  - docs/backlog/2026-06-23-saved-view-post-pr-follow-up-gaps.md
  - docs/backlog/2026-06-22-saved-view-feishu-parity-gaps.md
  - web-admin/tests/e2e/saved-view/FEATURE_MATRIX.md
---

# SavedView P2 Remaining Validation Report

## Scope

本报告收口 `codex/saved-view-p2-remaining` 当前分支的 SavedView 后续任务:

- WP1:高级视图语义校验。
- WP2:E2E 覆盖矩阵和 `e2e-truth` 口径。
- WP3:协作者分享产品化和 ACL。
- WP4:personal 10、team/global 20 配额 UX 和测试夹具。
- WP5:quick filter preset 保存、个人副本、edited/reset 生命周期。

不在本报告内完成的外部任务:

- Dynamic record/list/detail/sub-table/comment/export 的 pid-only migration。
- UserRole legacy id endpoint retirement。
- Audit actorPid public query。
- OpenAPI/docs pid naming cleanup。

## Requirement Matrix

| Work package | Product requirement | Closing evidence |
| --- | --- | --- |
| WP1 | API/UI 不能创建字段不存在或类型不匹配的高级视图 | `SavedViewServiceImplTest`; timeline target E2E; kanban date-group negative contract |
| WP2 | 完成报告按 feature/action 溯源,不是只报 pass count | `FEATURE_MATRIX.md`; target-scope truth audit; historical directory classification |
| WP3 | owner/manage 可分享,save-only 不能越权管理,协作者变更可审计 | backend ACL tests; `SV-FU-001`; screenshots `01/02` |
| WP4 | personal 10、team/global 20 在 UI 和测试夹具中都有明确行为 | backend quota tests; `SV-FU-002`; screenshot `03`; create-or-reuse helper output |
| WP5 | system preset、个人副本、已编辑副本生命周期清晰 | unit/component tests; `SV-FU-003`; screenshots `04/05` |

## Verification Commands

| Area | Command | Result |
| --- | --- | --- |
| Backend focused test | `cd platform && ./gradlew :test --tests com.auraboot.framework.view.service.impl.SavedViewServiceImplTest` | `BUILD SUCCESSFUL` |
| Frontend typecheck | `cd web-admin && pnpm typecheck` | PASS |
| Frontend unit/component | `cd web-admin && pnpm test:unit:run app/framework/meta/rendering/pages/__tests__/ListPageContent.test.ts app/framework/meta/rendering/pages/list/__tests__/quickFilterPresets.test.ts app/framework/meta/rendering/pages/list/__tests__/PresetViewBar.test.tsx app/framework/meta/rendering/pages/list/__tests__/dsl-list-i18n-resources.test.ts app/framework/smart/components/view/__tests__/ViewManagePanel.test.tsx app/shared/services/__tests__/savedViewService.test.ts` | 6 files / 129 tests passed |
| Target browser run | `PW_PROFILE=fast PW_WORKERS=1 pnpm playwright test -c playwright.noweb.config.ts tests/e2e/saved-view/saved-view-quick-filters.spec.ts tests/e2e/saved-view/saved-view-timeline.spec.ts tests/e2e/saved-view/saved-view-follow-up-golden.spec.ts --project=chromium --no-deps --reporter=line` | 17 passed |
| Changed-field E2E | `PW_PROFILE=fast PW_WORKERS=1 pnpm playwright test -c playwright.noweb.config.ts tests/e2e/saved-view/saved-view-kanban-grouping.spec.ts tests/e2e/saved-view/saved-view-lookup-field.spec.ts --project=chromium --no-deps --reporter=line` | 7 passed, 5 skipped |
| Golden files | `file web-admin/test-results/saved-view-follow-up-golden/*.png` | 5 PNGs, each 1280x720 |

The 5 skipped rows in the changed-field E2E run are historical lookup fixture-condition skips. They are not used as WP1-WP5 completion evidence.

## PR Closeout Fresh Run

2026-06-23 PR 收口前在新隔离 runtime `saved-view-p2-pr-82` 重新跑了一轮验证:

| Area | Result |
| --- | --- |
| Docs governance | `node scripts/check-docs-governance.mjs --changed docs/backlog/2026-06-23-saved-view-post-pr-follow-up-gaps.md docs/retro/2026-06-23-saved-view-p2-remaining-validation.md` passed with 0 errors / 0 warnings |
| Whitespace guard | `git diff --check` passed |
| Backend focused test | `SavedViewServiceImplTest` passed, `BUILD SUCCESSFUL` |
| Frontend typecheck | `pnpm typecheck` passed |
| Frontend unit/component | 6 files / 129 tests passed |
| Runtime setup | `IMPORT_TEST_FIXTURES=true` fixture import passed; required OSS plugin import passed with test-fixtures gate explicitly enabled |
| Target browser run | quick-filter + timeline + follow-up-golden command passed `17/17` |
| Changed-field E2E | kanban-grouping + lookup-field command passed `7 passed, 5 skipped` |
| Target truth audit | No target-scope skip/fixme/wait/direct-route/threshold redline patterns found |

The first PR-closeout runtime attempt used an existing expired allocation and a normal `exec_command` background stack, which allowed the tool process cleanup to terminate backend/frontend after stack startup. The successful rerun kept the stack session alive until E2E completed.

## PR Closeout Code Review Correction

提交前 code review 发现 SavedView 协作者搜索不应走 `/api/admin/users/search`:该路径受 `/api/admin/**` tenant-admin guard 保护,会把普通 owner/manage 协作者管理误收窄成管理员能力。当前分支已改为使用租户级 `POST /api/tenant/members/search`,并只把 `user.pid` 映射为 collaborator ACL 的 `principalPid`。

追加复验:

| Area | Result |
| --- | --- |
| Focused service test | `pnpm test:unit:run app/shared/services/__tests__/savedViewService.test.ts` passed `6/6` |
| Frontend typecheck | `pnpm typecheck` passed |
| Target browser rerun | quick-filter + timeline + follow-up-golden command passed `17/17` after endpoint correction |

## Golden Evidence

| Screenshot | Meaning |
| --- | --- |
| `web-admin/test-results/saved-view-follow-up-golden/01-collaborator-share-panel.png` | Owner share panel and collaborator row |
| `web-admin/test-results/saved-view-follow-up-golden/02-collaborator-audit-panel.png` | Collaborator-change audit evidence |
| `web-admin/test-results/saved-view-follow-up-golden/03-team-quota-limit.png` | Team scope at `20/20` limit |
| `web-admin/test-results/saved-view-follow-up-golden/04-preset-edited-state.png` | Personal preset copy in edited state |
| `web-admin/test-results/saved-view-follow-up-golden/05-preset-reset-state.png` | Preset reset state after reverting to system definition |

## E2E Truth Audit

Target files audited:

- `web-admin/tests/e2e/saved-view/saved-view-follow-up-golden.spec.ts`
- `web-admin/tests/e2e/saved-view/saved-view-quick-filters.spec.ts`
- `web-admin/tests/e2e/saved-view/saved-view-timeline.spec.ts`
- `web-admin/tests/e2e/saved-view/saved-view-kanban-grouping.spec.ts`

Audit command checked:

- `test.skip`
- `test.fixme`
- `skip(true`
- `waitForTimeout`
- `retries:`
- `toBeLessThanOrEqual`
- direct `/p/` feature navigation
- `toBeGreaterThanOrEqual`

Target result:

- No target-scope redline patterns were found.
- Request/browser split:
  - `saved-view-follow-up-golden.spec.ts`: `click/fill=14`, `request=8`.
  - `saved-view-quick-filters.spec.ts`: `click/fill=8`, `request=0`.
  - `saved-view-timeline.spec.ts`: `click/fill=1`, `request=2`.
  - `saved-view-kanban-grouping.spec.ts`: `click/fill=0`, `request=2`.

Full historical directory classification:

- Historical `tests/e2e/saved-view` still contains direct `/p/` smoke rows, fixture-dependent skips, threshold-style assertions, and API-heavy contract specs.
- Those rows are documented in `FEATURE_MATRIX.md`.
- Therefore the allowed claim is: WP1-WP5 target SavedView follow-up scope is complete with browser/API/golden evidence. The branch must not claim full historical SavedView UI coverage is 100%.

## Closing Decision

WP1-WP5 are closed for the current SavedView branch. The remaining active backlog is external platform/public-contract work:

- Platform dynamic record pid-only migration.
- UserRole legacy endpoint telemetry/deprecation/removal.
- Audit `actorPid` public query.
- Documentation/OpenAPI pid cleanup after migration naming settles.
