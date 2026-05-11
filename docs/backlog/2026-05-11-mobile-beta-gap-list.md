# Mobile Beta Gap List

Tracking issue: [#148](https://github.com/AuraBootTeam/auraboot/issues/148)
Milestone: `v0.1.0-beta.2`
Status: published beta gap inventory

## Scope

The public beta is desktop-first. This list inventories desktop workflows that
either have no mobile parity, only seed-level coverage, or no public mobile
acceptance evidence. It is a product gap list, not a claim that the desktop
workflow is broken.

Existing evidence:

- Release note known issue: `docs/releases/v0.1.0-beta.1.md`
- Platform visibility model: `docs/core-concepts/permissions.md`
- Mobile DTO deprecation notes: `docs/mobile/legacy-field-deprecation.md`
- Cross-platform seed bridge: `web-admin/tests/e2e/mobile-seed/cross-platform-seed.spec.ts`
- Desktop E2E inventory: `web-admin/tests/e2e/`
- Playwright projects: `web-admin/playwright.config.ts`

Coverage snapshot:

- The OSS Playwright projects are Desktop Chrome based; there is no dedicated
  Pixel/iPhone project in `web-admin/playwright.config.ts`.
- No native `iosApp` or `android` source/test directories are present in the
  OSS worktree.
- Mobile mentions in E2E are mostly login field names, viewport comments, or
  seed handoff notes. They do not form a mobile parity suite.

## Gap Inventory

| Gap | Desktop workflow | Current mobile state | Follow-up issue title | Milestone link |
|-----|------------------|----------------------|-----------------------|----------------|
| M-000 | Mobile Playwright project / acceptance harness. | Desktop Chrome projects exist; no dedicated mobile browser project is configured. | `Mobile: add OSS mobile Playwright smoke project` | `v0.1.0-beta.2` |
| M-001 | App shell navigation: sidebar, header, inbox, command palette, AuraBot entry. | Mobile sidebar/header code exists, but no narrow viewport workflow coverage proves menu open/close or nested navigation. | `Mobile: cover app shell navigation at 375px` | `v0.1.0-beta.2` |
| M-002 | Dynamic list/form/detail CRUD rendered from Page Designer pages. | Desktop has broad Page Designer and dynamic page E2E coverage; mobile has no equivalent public CRUD traversal evidence. | `Mobile: verify dynamic CRUD list/form/detail parity` | `v0.1.0-beta.2` |
| M-003 | List runtime toolbar/table behavior. | Toolbar/search/filter/table interactions are desktop-oriented and only indirectly responsive. | `Mobile: validate list toolbar wrapping and row actions` | `v0.1.0-beta.2` |
| M-004 | Page Designer preview and generated templates. | Mobile preview is smoke-clicked in one lifecycle test, but generated templates do not have mobile acceptance screenshots. | `Mobile: add Page Designer template preview evidence` | `v0.1.0-beta.2` |
| M-005 | Command pipeline operations from list/detail pages. | Desktop command execution is covered by E2E and k6 smoke; mobile command execution paths are not published. | `Mobile: cover command execution from generated pages` | `v0.1.0-beta.2` |
| M-006 | BPM task center and approval forms. | Desktop BPM and approval specs are extensive; mobile coverage is limited to references and seed handoff comments. | `Mobile: publish BPM approval inbox parity plan` | `v0.1.0-beta.2` |
| M-007 | Dashboards, reports, charts, and designer previews. | Desktop dashboard/report designers and viewers have E2E coverage; mobile chart density, drilldown, and navigation are unverified. | `Mobile: define dashboard and report viewer acceptance` | `v0.1.0-beta.2` |
| M-008 | AuraBot / AI workflows, including chat and generated artifacts. | Desktop AI specs exist; OSS docs state no independent mobile BFF for chat, so mobile UX parity remains undefined. | `Mobile: define AuraBot chat parity contract` | `v0.1.0-beta.2` |
| M-009 | Admin/security workflows: permissions, role-scoped menus, login channels. | Desktop admin/security specs exist; mobile-specific menu filtering and admin flows need acceptance evidence. | `Mobile: verify menu visibility and admin-safe flows` | `v0.1.0-beta.2` |
| M-010 | Cross-platform seeded record validation. | Seed bridge exists, but one web visibility assertion is `fixme` and mobile client execution is outside the public repo evidence. | `Mobile: make cross-platform seeded record verification executable` | `v0.1.0-beta.2` |

## Recommended Beta.2 Order

1. M-000 and M-001 first, because the team needs a real mobile acceptance
   harness before it can honestly claim parity.
2. M-002 and M-005 next, because generated CRUD and command execution are the
   core low-code runtime promise.
3. M-006 next, because approval flows are a high-value mobile use case.
4. M-003, M-004, M-007, and M-010 after the core runtime path is stable.
5. M-008 and M-009 as product-definition work if native mobile delivery is in
   scope for beta.2.

## Definition of Done

- Each mobile gap is either closed by an executable test or moved to a dedicated GitHub issue.
- Every mobile acceptance path starts from a real menu or mobile shell entry, not only an API call.
- Cross-platform seed data uses a shared `testRunId` so desktop and mobile evidence can be tied to the same run.
- Any intentionally unsupported mobile workflow is documented as a product limitation, not left as unknown coverage.
