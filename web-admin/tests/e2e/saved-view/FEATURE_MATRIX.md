# SavedView E2E Feature Matrix

Source backlog: `docs/backlog/2026-06-23-saved-view-post-pr-follow-up-gaps.md`, WP2.

This file separates user-path browser evidence from API/setup evidence. A passing spec count is not a completion claim unless the relevant feature row below has user-visible browser evidence and, where applicable, backend/API evidence.

## Current Scope Matrix

| Feature point | User path evidence | Backend/API evidence | Current specs | Status |
| --- | --- | --- | --- | --- |
| Selector entry beside page title | Browser opens list page, uses `view-selector-trigger`, switches visible views | Accessible/default views API used as setup/readback | `saved-view-management.spec.ts`, `saved-view-table.spec.ts`, `saved-view-quick-filters.spec.ts` | Covered |
| Create personal view | Browser create/manage path creates named view and sees it in selector | `/api/views` readback validates persisted config | `saved-view-management.spec.ts` | Covered |
| Manage view metadata | Browser rename/delete/default/duplicate paths drive visible controls | API readback validates saved metadata | `saved-view-management.spec.ts` | Partial: several rows remain API-assisted |
| Shared draft and admin shared save | Viewer/admin browser paths exercise local draft/copy/save confirmation | API write interception verifies shared save boundary | `saved-view-shared-draft-actions.spec.ts` | Covered for P1/P2 scoped path |
| Team/global collaborator productization | Browser opens management panel, adds/removes a user collaborator through Share, and sees permission row | Backend validates supported principal/permission and readback confirms collaborator ACL | `saved-view-follow-up-golden.spec.ts` `SV-FU-001`, `ViewManagePanel.test.tsx`, `SavedViewServiceImplTest` | Covered for owner share/add/remove; save-only no-manage restriction is covered by backend/component evidence |
| Audit panel | Browser opens management audit panel after collaborator update and sees collaborator-change event | SavedView audit DTO hides internal ids; service audit metadata summary records collaborator changes | `saved-view-follow-up-golden.spec.ts` `SV-FU-001`, `ViewManagePanel.test.tsx`, backend controller/service tests | Covered for shared-view audit UI + DTO contract |
| Quick filters as daily chips | Browser clicks `my_records`, `created_today`, `modified_this_week`, observes active state and reload | List API response observed as reload evidence | `saved-view-quick-filters.spec.ts` | Covered for base quick filters |
| Quick preset save-as-personal | Browser saves active preset and URL switches to personal view | `/api/views` create or existing saved copy readback | `saved-view-quick-filters.spec.ts` | Covered for base save/repeat semantics |
| Quick preset lifecycle states | Browser verifies saved badge, edited state, reset affordance, reset action, and reset readback | `/api/views/{pid}` update/readback proves preset-origin filters are restored | `saved-view-follow-up-golden.spec.ts` `SV-FU-003`, `PresetViewBar.test.tsx`, `quickFilterPresets.test.ts` | Covered for saved/edited/reset lifecycle; rename/delete use normal management rows |
| Quota UX | Browser selects a full team scope in create flow and sees count/limit, limit-reached copy, and disabled view type buttons | Backend count limit tests cover personal 10 and team/global 20; team limit setup uses real `/api/views` creates | `saved-view-follow-up-golden.spec.ts` `SV-FU-002`, `ViewManagePanel.test.tsx`, `SavedViewServiceImplTest` | Covered for browser team limit and component personal limit |
| Table view state | Column visibility, width, sort, filters, system fields, row height, conditional format | SavedView API readback validates viewConfig | `saved-view-table.spec.ts`, `saved-view-column-settings.spec.ts`, `saved-view-row-height.spec.ts`, `saved-view-system-fields.spec.ts`, `saved-view-conditional-format.spec.ts` | Partial: historical specs use API setup/readback heavily |
| Kanban view | Browser renders board, status columns, card interactions in the historical kanban spec | SavedView API validates config; WP1 rejects incompatible `groupByField` mappings such as date fields | `saved-view-kanban.spec.ts`, `saved-view-kanban-grouping.spec.ts`, `SavedViewServiceImplTest` | Covered for current semantic contract: boolean/reference/status/text-like fields accepted, date field rejected; historical drag/render depth remains outside WP1-WP5 closure |
| Calendar view | Browser renders events, switches calendar modes | SavedView API validates date mapping | `saved-view-calendar.spec.ts` | Covered for render path |
| Gallery view | Browser blocks missing image field and renders cards when image field exists | SavedView create validation/readback | `saved-view-gallery.spec.ts` | Covered for P1/P2 scoped path |
| Gantt view | Browser renders timeline and zoom controls | SavedView API validates start/end dates | `saved-view-gantt.spec.ts` | Covered for render path |
| Tree view | Browser blocks missing hierarchy field and saves parent mapping | SavedView create validation/readback | `saved-view-tree.spec.ts` | Covered for P1/P2 scoped path |
| Timeline view | Browser enters `e2et_order` from sidebar, switches/render-checks timeline | API create/readback validates start/resource mapping; invalid type rejected | `saved-view-timeline.spec.ts` | Covered for WP1/WP2 targeted path |
| Form view | Browser renders form view and persists config | SavedView API readback | `saved-view-form-view.spec.ts` | Partial: direct route smoke remains in historical rows |
| Lookup/reference rendering | Browser verifies reference display/null handling where fixtures exist | Dynamic API readback | `saved-view-lookup-field.spec.ts` | Fixture-dependent; skips must not count as complete coverage |
| Record comments/activity | API-level comment/activity contract around records | Record comment APIs | `saved-view-record-comment.spec.ts` | API contract only; excluded from SavedView UI completion claim |
| Formula field APIs | Formula function registry API | Formula API | `saved-view-formula.spec.ts` | API contract only; excluded from SavedView UI completion claim |
| AI recommendation hints | Recommendation dots/badges/text | N/A | `saved-view-ux-optimization.spec.ts` skipped AIR rows | Deferred product idea; not a P0/P1/P2 completion claim |

## Redline Inventory

| Redline | Current treatment |
| --- | --- |
| `saved-view-timeline.spec.ts` direct `/p/e2et_order` route checks | Removed in WP2; targeted timeline render checks now enter via sidebar link from `/` |
| Historical direct `/p/` route checks in older specs | Still present in table/form/system/lookup/row-height/button-field specs; treat as smoke or historical debt until replaced with menu/sidebar paths. New follow-up golden specs use sidebar entry for feature flows |
| API-heavy setup/readback | Allowed as setup/contract evidence only. It must not be counted as user-flow evidence unless paired with browser interaction |
| `test.skip` in lookup/comment historical specs | Fixture-dependent skips; do not count skipped rows as coverage |
| `test.skip` in AI recommendation specs | Deferred product idea; must have backlog before becoming a completion requirement |
| Threshold-style `toBeGreaterThanOrEqual` assertions | Some are business lower-bound/existence assertions; any baseline-drift threshold must be justified in the owning spec before completion claims |

## Final Verification Snapshot

| Scope | Command / evidence | Result | Completion claim |
| --- | --- | --- | --- |
| Backend SavedView service | `cd platform && ./gradlew :test --tests com.auraboot.framework.view.service.impl.SavedViewServiceImplTest` | `BUILD SUCCESSFUL` | Covers WP1 semantic validator, WP3 ACL/audit metadata, WP4 personal/team/global quota |
| Frontend typecheck | `cd web-admin && pnpm typecheck` | PASS | Covers SavedView public TS contracts and component wiring |
| Frontend unit/component | `cd web-admin && pnpm test:unit:run app/framework/meta/rendering/pages/__tests__/ListPageContent.test.ts app/framework/meta/rendering/pages/list/__tests__/quickFilterPresets.test.ts app/framework/meta/rendering/pages/list/__tests__/PresetViewBar.test.tsx app/framework/meta/rendering/pages/list/__tests__/dsl-list-i18n-resources.test.ts app/framework/smart/components/view/__tests__/ViewManagePanel.test.tsx app/shared/services/__tests__/savedViewService.test.ts` | 6 files / 129 tests passed | Covers quick preset registry/lifecycle, share panel, quota display, saved view service contract |
| Targeted SavedView browser run | `PW_PROFILE=fast PW_WORKERS=1 pnpm playwright test -c playwright.noweb.config.ts tests/e2e/saved-view/saved-view-quick-filters.spec.ts tests/e2e/saved-view/saved-view-timeline.spec.ts tests/e2e/saved-view/saved-view-follow-up-golden.spec.ts --project=chromium --no-deps --reporter=line` | 17 passed | Covers WP1/WP3/WP4/WP5 browser paths from real list-page/sidebar entry points |
| Changed-field E2E contract run | `PW_PROFILE=fast PW_WORKERS=1 pnpm playwright test -c playwright.noweb.config.ts tests/e2e/saved-view/saved-view-kanban-grouping.spec.ts tests/e2e/saved-view/saved-view-lookup-field.spec.ts --project=chromium --no-deps --reporter=line` | 7 passed, 5 skipped | Covers current kanban semantic contract; skipped lookup rows are historical fixture-dependent rows and are not counted toward WP1-WP5 completion |
| Golden screenshots | `web-admin/test-results/saved-view-follow-up-golden/*.png` | 5 PNG screenshots, each 1280x720 | Covers collaborator share, audit, team quota, preset edited, preset reset states |

Golden screenshot set:

- `01-collaborator-share-panel.png`
- `02-collaborator-audit-panel.png`
- `03-team-quota-limit.png`
- `04-preset-edited-state.png`
- `05-preset-reset-state.png`

## Target Scope Truth Audit

Target files audited:

- `saved-view-follow-up-golden.spec.ts`
- `saved-view-quick-filters.spec.ts`
- `saved-view-timeline.spec.ts`
- `saved-view-kanban-grouping.spec.ts`

Audit result:

- No `test.skip`, `test.fixme`, `skip(true)`, `waitForTimeout`, `retries:`, `toBeLessThanOrEqual`, direct `/p/` feature navigation, or threshold `toBeGreaterThanOrEqual` patterns were found in the target-scope files.
- Request/browser interaction split:
  - `saved-view-follow-up-golden.spec.ts`: `click/fill=14`, `request=8`
  - `saved-view-quick-filters.spec.ts`: `click/fill=8`, `request=0`
  - `saved-view-timeline.spec.ts`: `click/fill=1`, `request=2`
  - `saved-view-kanban-grouping.spec.ts`: `click/fill=0`, `request=2`

Interpretation:

- The current WP1-WP5 completion claim is allowed for the targeted SavedView follow-up scope.
- Full historical `tests/e2e/saved-view` still contains direct `/p/` smoke rows, fixture-dependent skips, and API-heavy specs. Those rows remain classified above and must not be used as a blanket "all SavedView historical UI coverage complete" claim.

## Completion Rule

Before claiming SavedView E2E coverage completion:

- Every delivered feature row must be `Covered` with browser evidence, or explicitly marked out of current scope with a backlog link.
- API-only rows remain contract evidence and cannot raise UI coverage.
- `e2e-truth` must be run after this matrix is updated for the final delivered scope.
