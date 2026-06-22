---
type: backlog
status: active
created: 2026-06-23
relates_to:
  - docs/backlog/2026-06-22-saved-view-feishu-parity-gaps.md
  - docs/plans/2026-06/2026-06-22-saved-view-feishu-parity-requirements.md
  - docs/assets/mockups/saved-view-vnext-mockup.html
  - https://github.com/AuraBootTeam/auraboot/pull/1028
---

# SavedView Post-PR Follow-up Gap Tracker

## Context

SavedView Feishu parity has been split into a stacked delivery:

- `codex/saved-view-feishu-p1`: Feishu-style daily selector, management panel, shared draft, public DTO cleanup, capability entry UX.
- `codex/saved-view-count-limit`: manual view count limits: personal 10, team/global 20.
- `codex/saved-view-p2-remaining`: UserRole pid mutation paths, audit public DTO, per-view collaborator ACL, timeline start/resource gate, quick-filter preset save-as-personal.

This document tracks follow-up gaps that remain after PR `#1028`. It is intentionally not a reopen of the completed P0/P1/P2 work. It separates:

- Platform migrations that must run as independent branches.
- Product maturity work needed to reach long-term Feishu-level robustness.
- E2E and governance debts found during `e2e-truth` review.

Dynamic business record pid-only migration is already delegated to another workspace:

`/Users/ghj/work/auraboot/.worktrees/oss-saved-view-feishu-p1/docs/backlog/2026-06-22-platform-public-record-pid-only-migration.md`

## Executive Summary

| Priority | Gap | Current state | Next action |
| --- | --- | --- | --- |
| P0 | Platform dynamic record pid-only migration | Out of scope for `#1028`; separate backlog exists | Continue in delegated workspace; keep this branch free of platform-wide record contract changes |
| P0 | Advanced view field semantic validation | Backend validates required mapping presence; it does not fully prove field existence/data type for every view type | Add server-side metadata validation for mapped fields and mirror it in UI/E2E |
| P0 | E2E redline cleanup for SavedView historical specs | Target specs pass, but timeline still has 2 direct `/p/` route checks and the SavedView directory has historical API-heavy specs | Add menu-navigation coverage and feature/action matrix before claiming full SavedView historical coverage |
| P1 | ID-based UserRole mutation endpoint retirement | PID endpoints exist; old ID endpoints are only `@Deprecated` | Add deprecation telemetry, docs, compatibility window, then remove/admin-gate legacy endpoints |
| P1 | Team collaborator management UI/API | Backend supports `viewConfig.meta.collaborators`; no first-class sharing UI/API yet | Build Share panel with user picker, validation, audit, and explicit permission updates |
| P1 | Audit query public contract | Audit responses are public DTOs; `/by-actor` still uses `actorId` query | Add `actorPid` query path/alias and restrict full internal audit DTOs to admin/verification endpoints |
| P1 | View quota UX and test isolation | Backend limits work; E2E had to reuse old views when long-lived DB hit personal limit | Add UI empty/limit state and quota-aware fixture helpers |
| P2 | Quick filter preset lifecycle | Save-as-personal exists; repeated save is idempotent | Add saved-state affordance, update/reset flow, and preset provider registry |
| P2 | Collaborator ACL expressiveness | ACL supports user principal with `view/save/manage` string permission | Add permission enum validation, role/team principals, and migration path if needed |
| P2 | Documentation/OpenAPI cleanup | Code paths changed faster than public docs | Update API docs, examples, and generated schema language to pid-first contracts |

## Gap Details

### GAP-SV-FU-001: Platform Dynamic Record PID-Only Migration

Status: delegated.

Why it remains:

- Dynamic list/detail/sub-table/comment/watch/history/export APIs can still expose or accept internal record ids through generic record maps or legacy naming.
- This impacts renderer identity, command runtime, NamedQuery/DataSource, export, comments, watch/follow, field history, and public cursor design.
- It is too broad for SavedView PR scope and already has a dedicated backlog.

Required outcome:

- Public dynamic record responses expose `pid`, not top-level internal `id`.
- Public request bodies prefer `recordPid`, `recordPids`, `targetRecordPid`, `commentPid`.
- Legacy `recordId/targetRecordId` is compatibility-only with telemetry and sunset plan.
- NamedQuery/DataSource/export cannot bypass the public-record sanitizer.

Source of truth:

- External backlog: `2026-06-22-platform-public-record-pid-only-migration.md`.

### GAP-SV-FU-002: Advanced View Field Semantic Validation

Status: open.

Current state:

- `SavedViewServiceImpl.validateViewTypeConfig` blocks missing required config fields.
- Frontend capability logic suggests mappings from field metadata.
- Timeline was tightened to require `timelineStartField + timelineResourceField`, with `timelineEndField` optional.

Gap:

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

Status: open.

Current evidence from `#1028`:

- Scoped target run passed: quick-filter + timeline `13/13`.
- `e2e-truth` grep found no skip/fixme, threshold, retry, or `waitForTimeout` in the two target specs.
- `saved-view-timeline.spec.ts` still has 2 direct `page.goto('/p/e2et_order')` route checks.
- Historical SavedView specs include API-heavy setup/verification paths and earlier skipped AI/fixture-condition tests.

Gap:

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

Status: open.

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

Status: open.

Current state:

- Backend stores `viewConfig.meta.collaborators`.
- Permission model supports user principal permissions:
  - `view`
  - `save`
  - `manage`
- Save-only collaborator can update view config/default but cannot rename, delete, manage, share, or change managed meta.
- Manage-level updates can edit metadata and collaborators through the generic view update path.

Gap:

- There is no first-class Share/Collaborators UI.
- There is no dedicated collaborator API with principal validation, permission enum validation, or audit-specific event shape.
- Current storage location is flexible but easy to misuse if future clients write raw `viewConfig.meta`.

Target behavior:

- View management panel has a Share section for team views.
- Manage users can add/remove collaborators by user pid, set permission, and see effective access.
- Save-only users cannot alter collaborators, lock flags, plugin ownership, or other managed meta.
- Backend exposes dedicated collaborator mutation endpoints or a validated update sub-command.

Acceptance criteria:

- Add collaborator UI E2E:
  - owner adds save collaborator.
  - collaborator can save config.
  - collaborator cannot rename/delete/share.
  - owner upgrades collaborator to manage.
  - audit records collaborator change.
- Add backend tests for invalid principal, invalid permission, cross-tenant user, and raw meta tampering.

### GAP-SV-FU-006: Audit Query Public Contract and Admin Split

Status: open.

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

Status: open.

Current state:

- Backend limits:
  - personal: 10 manual views per user/model/page.
  - team/global: 20 manual views per scope/model/page.
  - implicit autosave is excluded.
- During target E2E reruns, long-lived DB hit personal 10 view limit; timeline spec now reuses same-config views.
- Quick preset save-as-personal is idempotent when the preset was already saved.

Gap:

- User-facing quota state needs a clearer UX than only API error handling.
- E2E specs that create SavedViews can exhaust quota in long-lived runtimes.
- There is no shared fixture helper for quota-safe view creation/reuse.

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

Status: open.

Current state:

- Built-in quick filters are rendered as system preset entries.
- Active preset can be saved as a personal SavedView.
- Saved personal view records `viewConfig.meta.originPresetKey`.
- Repeated save switches to existing personal preset view instead of creating duplicates.

Gap:

- UI does not yet show that a system preset has already been saved to personal views.
- There is no reset/update-from-preset flow.
- Preset definitions are local provider logic, not yet a platform-level preset registry.

Target behavior:

- System preset row indicates saved state.
- Saved personal preset can be opened, renamed, deleted, or reset to current built-in preset definition.
- Future plugins can register preset providers without editing central list-page code.

Acceptance criteria:

- UI differentiates unsaved preset, saved personal preset, and edited personal copy.
- E2E covers save, repeat save, switch, rename personal copy, and delete personal preset.
- Unit tests cover provider registry resolution and conflict handling.

### GAP-SV-FU-009: Collaborator ACL Validation and Extensibility

Status: open.

Current state:

- `CollaboratorAcl` is a flexible object in `ViewConfig.Meta`.
- Current implementation accepts user principal and string permission.

Gap:

- Permission values are not yet a strict enum at API boundary.
- Principal existence and tenant membership are not validated through a dedicated path.
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

Status: open.

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

## Proposed Execution Order

| Order | Work package | Suggested branch | Reason |
| --- | --- | --- | --- |
| 1 | Advanced view semantic validator | `codex/saved-view-advanced-field-validation` | Prevents invalid API-created advanced views; contained SavedView scope |
| 2 | E2E redline cleanup and coverage matrix | `codex/saved-view-e2e-coverage-hardening` | Converts current truth notes into durable guardrails |
| 3 | UserRole legacy endpoint retirement telemetry | `codex/user-role-pid-endpoint-deprecation` | Low-risk governance step before removal |
| 4 | Team collaborator UI/API | `codex/saved-view-collaborators-productized` | Product maturity; depends on settled ACL semantics |
| 5 | Audit actorPid public query | `codex/audit-public-actor-pid-query` | Small public contract cleanup |
| 6 | View quota UX and E2E helper | `codex/saved-view-quota-ux-fixtures` | Improves user experience and long-run test stability |
| 7 | Quick preset lifecycle | `codex/saved-view-preset-lifecycle` | Incremental UX maturity after base save-as-personal is stable |
| parallel | Platform dynamic record pid-only migration | delegated branch/workspace | Large platform migration, must stay separate |

## Reporting Rules

When reporting future progress from this backlog:

- Do not say "SavedView 100% complete" unless feature/action coverage matrix and `e2e-truth` both pass with no product gaps.
- Separate UI browser evidence from API/setup evidence.
- Treat platform dynamic record pid-only work as an independent migration, not a SavedView-only cleanup.
- If legacy ID paths remain for compatibility, report them as compatibility debt with telemetry/removal status.
- If E2E uses direct `/p/` navigation or API-created records, state why and what user-path evidence pairs with it.

