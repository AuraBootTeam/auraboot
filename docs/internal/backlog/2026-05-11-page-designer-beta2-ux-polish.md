# Page Designer Beta.2 UX Polish Backlog

Tracking issue: [#149](https://github.com/AuraBootTeam/auraboot/issues/149)
Milestone: `v0.1.0-beta.2`
Status: ready for beta.2 implementation planning

## Scope

This backlog captures first-run Page Designer friction found after the public
beta launch. It is intentionally product-facing: it does not replace the E2E
suite, and it does not change the Page DSL contract.

Existing evidence:

- Guide: `docs/guides/page-designer.md`
- Concept reference: `docs/core-concepts/pages-and-layouts.md`
- Screenshot: `docs/assets/screenshots/page-designer.png`
- Main E2E chain: `web-admin/tests/e2e/page-designer/page-designer-full-lifecycle.spec.ts`
- Focused E2E coverage: `web-admin/tests/e2e/page-designer/*`
- Designer UX smoke: `web-admin/tests/e2e/designer-ux-improvements.spec.ts`

## Prioritized Friction Points

| Priority | Friction | Evidence | Beta.2 target | Verification |
|----------|----------|----------|---------------|--------------|
| P0 | First-run entry and docs drifted: `/page-designer` now redirects to `/p/page_schema`, while older docs imply a dedicated Page Designer list. | `web-admin/app/plugins/core-designer/pages/page-designer.tsx` redirects for compatibility; `page-designer-full-lifecycle.spec.ts` drives the sidebar path. | Make the page schema list first-run action explicit and keep docs/screenshots aligned with the redirect. | Screenshot before/after at `docs/assets/screenshots/page-designer.png`; menu-driven E2E opens a new page from the visible entry. |
| P0 | "Drag-drop Page Designer" is now multiple editor modes. List/detail pages use structured config panels; form pages use the block canvas. | `DesignerRouter.tsx` routes `list` to `ListConfigPanel`, `detail` to `DetailConfigPanel`, and `form` to `BlocksDesigner`. | Explain editor modes in docs and UI empty states so users know why list/detail creation differs from form-page block editing. | Docs update plus E2E that opens one list, one detail, and one form page and verifies the expected editor shell. |
| P1 | New users do not get an obvious template path for list/form/detail CRUD sets. | `docs/guides/page-designer.md` explains manual setup; `studio/page-templates.spec.ts` covers template creation separately. | Promote CRUD templates from the Page Designer start state and make the selected model/page kind visible before editor entry. | `web-admin/tests/e2e/studio/page-templates.spec.ts` covers template creation; add a first-run screenshot or clip in PR evidence. |
| P1 | Save/publish state feedback is overloaded across toolbar save, auto-save status, publish, and form canvas save. | `PageDesignerEditorImpl.tsx` and `BlocksDesigner.tsx` both expose save-related controls. | Show persistent saved/published status, dirty state, and last save time in the toolbar; clarify publish as the runtime release step. | E2E asserts dirty -> saved -> published states without relying only on toast timing. |
| P1 | Mobile preview exists in the lifecycle path but is not yet a parity contract. | The lifecycle test clicks the Mobile preview when present and expects `375 x 812`. | Make mobile/tablet preview modes first-class acceptance criteria for every template. | Add screenshot evidence for desktop and mobile preview for each shipped template. |
| P2 | English first-run polish and terminology are uneven. | List/detail config panels still include mixed locale labels; `smart-components.spec.ts` documents Chinese hardcoding checks. | Normalize labels and tooltips around list config, detail config, block library, canvas, and properties. | Visual review plus i18n check that labels are localized and not raw keys. |

## Beta.2 Work Items

1. Create a Page Designer first-run entry point from the page schema list.
   Suggested follow-up issue: `Page Designer: add visible first-run create/template entry`.

2. Promote CRUD template selection before entering the blank canvas.
   Suggested follow-up issue: `Page Designer: make CRUD templates the default onboarding path`.

3. Add persistent save/publish/dirty-state feedback to the toolbar.
   Suggested follow-up issue: `Page Designer: expose saved and published state in toolbar`.

4. Treat mobile preview as a required template verification step.
   Suggested follow-up issue: `Page Designer: add mobile preview evidence for beta templates`.

5. Normalize Page Designer terminology and locale coverage across docs,
   tooltips, and tests.
   Suggested follow-up issue: `Page Designer: align editor labels and remove mixed-locale first-run copy`.

All five items belong to the `v0.1.0-beta.2` milestone unless implementation
requires a DSL contract change. If a DSL contract change is needed, split that
work into a separate design issue before implementation.

## Definition of Done

- At least one before/after screenshot or short clip is attached to the PR for each changed user-facing flow.
- The menu-driven lifecycle test still opens Page Designer without deep-linking.
- New or changed user-facing text is localized.
- Empty, loading, success, and error states are covered for any new first-run UI.
