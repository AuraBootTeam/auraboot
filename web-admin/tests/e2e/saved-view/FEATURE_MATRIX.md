# SavedView E2E Feature Matrix

当前 baseline：`docs/plans/2026-06/2026-06-23-saved-view-endgame-baseline.md`。

历史 backlog 来源：`docs/backlog/2026-06-23-saved-view-post-pr-follow-up-gaps.md`, WP2。

2026-06-23 重置：本矩阵必须区分“平台能力证据”和“enterprise 长期 UX 平齐”。
已有 Covered 行只在该行列出的具体证据范围内有效，不能推出完整
SavedView 管理/新建/共享/诊断链路已达到 release-complete。

2026-06-23 scope 收口：当前 release 只验收 Personal-only。团队/全员、
协作者、共享保存 diff、team/global quota、共享 audit 仅保留历史证据和
后续路线，不进入当前完成判定。

This file separates user-path browser evidence from API/setup evidence. A passing spec count is not a completion claim unless the relevant feature row below has user-visible browser evidence and, where applicable, backend/API evidence.

## Current Scope Matrix

| Feature point | User path evidence | Backend/API evidence | Current specs | Status |
| --- | --- | --- | --- | --- |
| Selector entry beside page title | Browser opens list page, uses `view-selector-trigger`, switches visible views | Accessible/default views API used as setup/readback | `saved-view-management.spec.ts`, `saved-view-table.spec.ts`, `saved-view-quick-filters.spec.ts` | Covered |
| Create personal view | Browser create/manage path creates named view and sees it in selector | `/api/views` readback validates persisted config | `saved-view-management.spec.ts` | Covered |
| Manage view metadata | Browser rename/delete/default/duplicate paths drive visible controls | API readback validates saved metadata | `saved-view-management.spec.ts` `SV-PER-002` | Covered for Personal-only management actions; API remains readback/setup evidence only |
| Personal dirty/save state | Browser changes personal view settings, sees dirty state, saves current personal view or saves as a new personal view | `/api/views/{pid}` update/readback validates persisted personal config | `saved-view-management.spec.ts` `SV-PER-003`, screenshot `04-personal-draft-save.png` | Covered |
| Shared draft and admin shared save | Historical viewer/admin browser paths exercise local draft/copy/save confirmation | API write interception verifies shared save boundary | `saved-view-shared-draft-actions.spec.ts` | Out of current scope; roadmap evidence only |
| Team/global collaborator productization | Historical browser/component/backend evidence exists | Backend validates supported principal/permission and readback confirms collaborator ACL | `saved-view-follow-up-golden.spec.ts` `SV-FU-001`, `ViewManagePanel.test.tsx`, `SavedViewServiceImplTest` | Out of current scope; roadmap evidence only |
| Audit panel | Historical shared-view audit evidence exists | SavedView audit DTO hides internal ids; service audit metadata summary records collaborator changes | `saved-view-follow-up-golden.spec.ts` `SV-FU-001`, `ViewManagePanel.test.tsx`, backend controller/service tests | Out of current scope; roadmap evidence only |
| Quick filters as daily chips | Browser clicks `my_records`, `created_today`, `modified_this_week`, observes active state and reload | List API response observed as reload evidence | `saved-view-quick-filters.spec.ts` | Covered for base quick filters |
| Quick preset save-as-personal | Browser saves active preset and URL switches to personal view | `/api/views` create or existing saved copy readback | `saved-view-quick-filters.spec.ts`, `saved-view-management.spec.ts` `SV-PER-004` | Covered for current Personal-only save-as-personal path |
| Quick preset lifecycle states | Browser verifies saved badge, edited state, reset affordance, reset action, and reset readback | `/api/views/{pid}` update/readback proves preset-origin filters are restored | `saved-view-follow-up-golden.spec.ts` `SV-FU-003`, `PresetViewBar.test.tsx`, `quickFilterPresets.test.ts` | Covered for saved/edited/reset lifecycle; rename/delete use normal management rows |
| Personal quota UX | Browser sees personal count/limit `n/10`; when personal limit is reached, new personal view is disabled with cleanup guidance | Backend count limit tests cover personal 10 | `saved-view-management.spec.ts` `SV-PER-005`, `ViewManagePanel.test.tsx`, `SavedViewServiceImplTest`, screenshot `07-personal-quota.png` | Covered |
| Table view state | Column visibility, width, sort, filters, system fields, row height, conditional format | SavedView API readback validates viewConfig | `saved-view-table.spec.ts`, `saved-view-column-settings.spec.ts`, `saved-view-row-height.spec.ts`, `saved-view-system-fields.spec.ts`, `saved-view-conditional-format.spec.ts` | Partial: historical specs use API setup/readback heavily |
| Kanban view | Browser renders board, status columns, card interactions in the historical kanban spec | SavedView API validates config; WP1 rejects incompatible `groupByField` mappings such as date fields | `saved-view-kanban.spec.ts`, `saved-view-kanban-grouping.spec.ts`, `SavedViewServiceImplTest` | Covered for current semantic contract: boolean/reference/status/text-like fields accepted, date field rejected; historical drag/render depth remains outside WP1-WP5 closure |
| Calendar view | Browser renders events, switches calendar modes | SavedView API validates date mapping | `saved-view-calendar.spec.ts` | Covered for render path |
| Gallery view | Browser blocks missing image field and renders cards when image field exists | SavedView create validation/readback | `saved-view-gallery.spec.ts` | Covered for P1/P2 scoped path |
| Gantt view | Browser renders timeline and zoom controls | SavedView API validates start/end dates | `saved-view-gantt.spec.ts` | Covered for render path |
| Tree view | Browser blocks missing hierarchy field and saves parent mapping | SavedView create validation/readback | `saved-view-tree.spec.ts` | Covered for P1/P2 scoped path |
| Timeline view | Browser enters `e2et_order` from sidebar, switches/render-checks timeline | API create/readback validates start/resource mapping; invalid type rejected | `saved-view-timeline.spec.ts` | Covered for WP1/WP2 targeted path |
| Form view | Browser renders form view and persists config | SavedView API readback | `saved-view-form-view.spec.ts` | Covered for current historical render path; older direct-route smoke is classified in redline inventory |
| Lookup/reference rendering | Browser verifies reference display/null handling where fixtures exist | Dynamic API readback | `saved-view-lookup-field.spec.ts` | Fixture-dependent; skips must not count as complete coverage |
| Record comments/activity | API-level comment/activity contract around records | Record comment APIs | `saved-view-record-comment.spec.ts` | API contract only; excluded from SavedView UI completion claim |
| Formula field APIs | Formula function registry API | Formula API | `saved-view-formula.spec.ts` | API contract only; excluded from SavedView UI completion claim |
| AI recommendation hints | Recommendation dots/badges/text | N/A | `saved-view-ux-optimization.spec.ts` skipped AIR rows | Deferred product idea; not a P0/P1/P2 completion claim |

## Personal-only Golden Screenshot Set

All screenshots below are current release evidence for the Personal-only baseline:

| Screenshot | User path proven |
| --- | --- |
| `web-admin/test-results/saved-view-personal-golden/01-data-view.png` | List page with title-side selector and toolbar quick filters, without duplicate chip row |
| `web-admin/test-results/saved-view-personal-golden/02-personal-selector.png` | Personal-only selector dropdown with search/create/manage entry |
| `web-admin/test-results/saved-view-personal-golden/03-personal-management.png` | Personal management center with search and quota summary |
| `web-admin/test-results/saved-view-personal-golden/04-personal-draft-save.png` | Dirty state with save current / save as new / discard actions |
| `web-admin/test-results/saved-view-personal-golden/05-capability-blocked.png` | Blocked advanced view cannot be saved and shows Chinese reason |
| `web-admin/test-results/saved-view-personal-golden/06-capability-degraded-create.png` | Degraded advanced view can be configured with clear limitation |
| `web-admin/test-results/saved-view-personal-golden/07-personal-quota.png` | Personal quota `10/10` disables new personal view creation with cleanup guidance |

## Roadmap Rows Excluded From Current Release

| Roadmap feature | Reason excluded now | Re-entry condition |
| --- | --- | --- |
| Team/global scope selector | Current user request is Personal-only | New scope document explicitly reintroduces `ab_team` / global permissions |
| Shared save diff confirmation | Depends on team/global source views | Team/global mockup and E2E matrix are reopened |
| Collaborator management | Depends on shared view ACL and principal lookup | ACL product contract is approved for a separate release |
| Shared audit UI | Depends on shared save/collaborator actions | Roadmap release includes audit user path and screenshots |
| Team/global quota 20 | Not needed for personal view release | Team/global create flow is in scope again |

## Redline Inventory

| Redline | Current treatment |
| --- | --- |
| `saved-view-timeline.spec.ts` direct `/p/e2et_order` route checks | Removed in WP2; targeted timeline render checks now enter via sidebar link from `/` |
| Historical direct `/p/` route checks in older specs | Still present in table/form/system/lookup/row-height/button-field specs; treat as smoke or historical debt until replaced with menu/sidebar paths. New follow-up golden specs use sidebar entry for feature flows |
| API-heavy setup/readback | Allowed as setup/contract evidence only. It must not be counted as user-flow evidence unless paired with browser interaction |
| `test.skip` in lookup/comment historical specs | Fixture-dependent skips; do not count skipped rows as coverage |
| `test.skip` in AI recommendation specs | Deferred product idea; must have backlog before becoming a completion requirement |
| Threshold-style `toBeGreaterThanOrEqual` assertions | Some are business lower-bound/existence assertions; any baseline-drift threshold must be justified in the owning spec before completion claims |
| Long-lived personal quota pollution | Specs that create personal views must reuse deterministic fixtures or clean their own prefix. `saved-view-form-view.spec.ts` now cleans only `FV_` views before/after each test and throws API response bodies on create failure, so repeated runs do not silently fill the personal limit |

## Final Verification Snapshot

| Scope | Command / evidence | Result | Completion claim |
| --- | --- | --- | --- |
| Backend SavedView service | `cd platform && ./gradlew :test --tests com.auraboot.framework.view.service.impl.SavedViewServiceImplTest` | Historical `BUILD SUCCESSFUL` | Covers semantic validator and personal quota; ACL/audit/team-global evidence is roadmap-only for current release |
| Frontend typecheck | `cd web-admin && pnpm typecheck` | PASS | Covers SavedView public TS contracts and component wiring |
| Frontend unit/component | `cd web-admin && pnpm test:unit:run app/framework/smart/components/view/__tests__/ViewManagePanel.test.tsx app/framework/smart/components/view/__tests__/ViewSelector.test.tsx app/framework/smart/hooks/__tests__/useSavedViews.test.tsx app/framework/smart/utils/__tests__/savedViewPersistence.test.ts app/framework/smart/utils/__tests__/savedViewCapability.test.ts app/framework/meta/rendering/pages/list/__tests__/dsl-list-i18n-resources.test.ts` | 6 files / 198 tests passed | Covers personal selector/management panel, quota, i18n keys, persistence and capability rules |
| Personal-only golden browser run | `PW_PROFILE=fast PW_WORKERS=1 PW_SKIP_WEBSERVER=1 PLAYWRIGHT_BASE_URL=http://127.0.0.1:5183 BACKEND_URL=http://127.0.0.1:6483 BE_PORT=6483 BFF_PORT=6183 NO_PROXY=localhost,127.0.0.1 pnpm playwright test -c playwright.noweb.config.ts tests/e2e/saved-view/saved-view-management.spec.ts tests/e2e/showcase/view-management.spec.ts --project=chromium --no-deps --reporter=line` | 6 passed | Covers current Personal-only selector, management, dirty save, quick-filter save-as-personal, capability gate and quota screenshots |
| Affected advanced/browser regression run A | Same env as above, running `saved-view-gallery.spec.ts`, `saved-view-tree.spec.ts`, `saved-view-follow-up-golden.spec.ts`, `ga-showcase-saved-view-deep.spec.ts` | 5 passed | Covers gallery/tree blocked paths and historical follow-up smoke after Personal-only changes |
| Affected advanced/browser regression run B | Same env as above, running `saved-view-kanban.spec.ts`, `saved-view-calendar.spec.ts`, `saved-view-gantt.spec.ts`, `saved-view-timeline.spec.ts`, `saved-view-form-view.spec.ts`, `saved-view-ux-optimization.spec.ts` | 35 passed, 3 skipped | Covers advanced view render/history regressions; 3 skipped AIR recommendation rows are deferred product idea, not current scope |
| Golden screenshots | `web-admin/test-results/saved-view-personal-golden/*.png` | 7 PNG screenshots | Current Personal-only golden evidence; historical collaborator/audit/team quota screenshots are roadmap-only |

## Target Scope Truth Audit

Target files audited for the current Personal-only claim:

- `saved-view-management.spec.ts`
- `showcase/view-management.spec.ts`
- `saved-view-gallery.spec.ts`
- `saved-view-tree.spec.ts`
- `saved-view-follow-up-golden.spec.ts`
- `saved-view-kanban.spec.ts`
- `saved-view-calendar.spec.ts`
- `saved-view-gantt.spec.ts`
- `saved-view-timeline.spec.ts`
- `saved-view-form-view.spec.ts`
- `saved-view-ux-optimization.spec.ts`

Audit result:

- Core Personal-only redline grep found no `test.skip`, `test.fixme`, `skip(true)`, `waitForTimeout`, direct `/p/` feature navigation, `timeout > 5000`, `retries:`, `toBeLessThanOrEqual`, `toBeGreaterThanOrEqual`, `threshold`, or `baseline` in:
  - `saved-view-management.spec.ts`
  - `showcase/view-management.spec.ts`
- Core request/browser interaction split:
  - `saved-view-management.spec.ts`: `ui=31`, `request=2`. API calls are setup/readback/cleanup; user actions are browser-driven.
  - `showcase/view-management.spec.ts`: `ui=1`, `request=4`. This is retained as a smoke guard only; the full Personal-only proof is `saved-view-management.spec.ts`.
- Affected historical specs have no retry/threshold/direct-route/waitForTimeout redlines in the audited set. The only skipped rows are the three AI recommendation tests in `saved-view-ux-optimization.spec.ts`; they are explicitly out of current Personal-only scope.
- Closeout diff audit found no newly introduced `timeout > 5000` in the affected advanced specs after tightening `saved-view-form-view.spec.ts`; existing long waits in historical specs remain historical debt and cannot be used as Personal-only completion proof.
- Several historical advanced view specs remain API-heavy (`kanban`, `calendar`, `gantt`, `timeline`, `form`, `ux-optimization`). They are regression/contract evidence, not the basis for the current Personal-only UI completion claim.

Interpretation:

- The previous WP1-WP5 team/global/collaborator/audit completion claim is historical and roadmap-only for this release.
- The current completion claim is limited to Personal-only rows in this matrix.
- Full historical `tests/e2e/saved-view` still contains direct `/p/` smoke rows, fixture-dependent skips, and API-heavy specs. Those rows remain classified above and must not be used as a blanket "all SavedView historical UI coverage complete" claim.

## Completion Rule

Before claiming SavedView E2E coverage completion:

- Every delivered feature row must be `Covered` with browser evidence, or explicitly marked out of current scope with a backlog link.
- API-only rows remain contract evidence and cannot raise UI coverage.
- `e2e-truth` must be run after this matrix is updated for the final delivered scope.
