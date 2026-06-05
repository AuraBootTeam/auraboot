# Session Handover — 2026-06-05 · automation-designer-golden-e2e

## Session Summary
Designed + began building the **automation designer end-to-end golden E2E** (real-browser drag-drop user journey + full 18-node-type coverage). Branch `feat/automation-designer-golden-e2e` (pushed). **Phase 0 COMPLETE + verified** (and the golden smoke already found+fixed 2 real FlowCanvas bugs). **Phase 1 IMPLEMENTED but E2E-UNVERIFIED** — the GA E2E stack degraded (502) mid-verification and was torn down; a fresh session with a clean stack must verify Phase 1 + review its product changes before continuing.

> Prior context (DONE, do not re-do): this session also closed the 2026-05-23 automation designer-runtime review — 8 PRs merged to OSS main (#414/#415/#416/#417/#420/#424/#426 + bpm-event/derivation P0). Status recorded in `auraboot/docs/internal/backlog/2026-05-23-automation-designer-runtime-review.md` §5. The golden E2E below is the only OPEN line.

## The work line: branch `feat/automation-designer-golden-e2e`
- Spec: `docs/superpowers/specs/2026-06-05-automation-designer-golden-e2e-design.md`
- Plan (executable, per-case checkboxes + full Phase-0/Phase-1 status): `docs/superpowers/plans/2026-06-05-automation-designer-golden-e2e.md` ← **read this first**
- Commits (origin has them): spec → plan → testids (7a4d632fc) → harness (db26f9923) → Phase-0 smoke + 2 FlowCanvas fixes (e1d16259c) → Phase-0 cleanup (b06cca22c) → Phase-1 H1-H3 (e9fe77af4) → Phase-1 S1/S2/E5 (f0d4edf49) → Phase-1 honest-status (d8103d0c8).

## Tasks Completed (verified)
- **Phase 0 (infra + harness) — DONE, main-loop verified.** Drag mechanism = HTML5 native drag (`application/flow-node`). Stable testids on palette/nodes/handles/prop-fields/save. Shared harness `web-admin/tests/e2e/_helpers/flow-designer-harness.ts` (drag smoke-verified 2/2 by me). Palette-coverage test: 18 types / 110 assertions. **Golden smoke found + fixed 2 real shared-FlowCanvas bugs** (off-screen node drop: `screenToFlowPosition` called outside provider + `fitView` re-fit on empty canvas) — commit e1d16259c, regression 254 flow-sdk/bpmn tests pass.

## Tasks In Progress (NOT verified — must close before trusting)
- **Phase 1 (Layer A real drag journey) — IMPLEMENTED but E2E-UNVERIFIED.** A subagent built `web-admin/tests/e2e/automation/automation-designer-golden.spec.ts` (6 cases: H1 full journey, H2 reload, H3 G5 badges, S1 required-gate, S2 dangerous-SpEL, E5 edit-refire) + tuned the harness + made **4 product changes it claims are golden-found bugs** — these are UNREVIEWED:
  - `platform/.../automation/service/impl/AutomationServiceImpl.java` (backend, 13 lines)
  - `web-admin/app/framework/smart/automation/components/AutomationEditPageImpl.tsx`
  - `web-admin/app/plugins/core-designer/components/flow-designer-sdk/core/FlowDesigner.tsx`
  - `web-admin/app/shared/designer/PropertyFieldRenderer.tsx` (PB-4 field-error)
  - Committed e9fe77af4 + f0d4edf49. Subagent run was cut off by API ECONNRESET (final report lost).
  - **Known issues:** (1) `tsc --noEmit` fails — `automation-designer-golden.spec.ts:341` `Property 'request' does not exist on type 'Request'`. (2) backend change NOT in the (now-removed) running stack — needs rebuild. (3) 6 cases never confirmed to pass (stack 502'd before I could run them). Frontend unit regression DID pass (231 tests) — the shared-component changes don't break existing units.

## Next Steps (to close Phase 1, then Phases 2-4)
1. **Free disk first** (was ~23Gi; §2.3.1 wants ≥30GB — prune or stop an idle stack you own; `docker image prune -a` reclaims ~6GB of unused tagged images).
2. `cd` into a worktree on `feat/automation-designer-golden-e2e`; bring up a CLEAN GA E2E stack: `scripts/docker-ga-e2e-up.sh` then `scripts/docker-ga-e2e-bootstrap.sh` (the up rebuilds backend → picks up the AutomationServiceImpl change; bootstrap seeds admin + test-fixtures/e2et_order).
3. **Fix** the spec tsc error (`automation-designer-golden.spec.ts:341`).
4. **Run** Layer A: `PLAYWRIGHT_BASE_URL=http://localhost:5174 BACKEND_URL=http://localhost:6444 BE_PORT=6444 BFF_PORT=3501 PW_SKIP_WEBSERVER=1 PW_STORAGE_DIR=tests/storage/ga npx playwright test tests/e2e/automation/automation-designer-golden.spec.ts --no-deps` (regenerate `tests/storage/ga/admin.json` via `... npx playwright test auth.setup --no-deps` if expired). Confirm all 6 cases pass; screenshot-review the happy journey.
5. **Review the 4 product changes** (§14/§20 — real fix vs make-it-pass hack), esp. the backend + shared-SDK ones; back out any hack.
6. Then Phases 2-4 per the plan (Layer B behavioral matrix, full 18-node back coverage, acceptance + `/e2e-truth`).

## Key Decisions
| Decision | Chosen | Rationale |
|---|---|---|
| Test architecture | Layered: Layer A real-drag journey + Layer B API-setup behavioral matrix | Real-pointer fidelity where it matters (§2.2 interaction layer); behavioral matrix not dragged down by 18 slow drag sequences |
| Scope/order | automation designer first (full happy/sad/edge/corner + 18 nodes), bpmn slice after (reuse harness) | Backend just verified, lowest risk; harness is @xyflow-generic for bpmn reuse |
| Skips | delay-runtime (timer suspended #8) + scheduled-realtime (heavy) = HONEST skip | §2 no-fake-pass; counted skip not pass |

## Pitfalls & Lessons
1. **Stack env degraded under load → 502 (env-invalid).** A ~176-min single subagent run + my verification runs, on a stack brought up at **25Gi disk (below the §2.3.1 30GB gate — accepted risk)**, degraded the docker host↔container port layer to a persistent 502 that survived a container restart. **Lesson:** don't accept sub-30GB stack bring-ups for long E2E sessions; don't let a single subagent run ~3h — checkpoint it. The disk risk I flagged and the user accepted did bite (late, as resource degradation, not an immediate hang).
2. **Subagent committed product code + a non-compiling spec under "make it green" pressure.** The 4 product changes + the tsc-failing spec are exactly why §14/§20 verify-don't-trust applies — none are trusted until reviewed + the cases actually run green.
3. **A stack brought up from a worktree mounts THAT worktree at `/repo`** (verified via `docker inspect`) — so frontend source edits are live via vite HMR, no HMR-cp dance needed. (An earlier "rsync from canonical" reading was a misread of an unrelated process.) Backend, however, is built in-container at up time → backend changes need a rebuild.

## Current State
- **Git:** branch `feat/automation-designer-golden-e2e` pushed (tip d8103d0c8), working tree clean. origin/main far ahead (other sessions).
- **Stack:** GA E2E stack **torn down** (`docker-ga-e2e-down.sh`, was 502/broken). Fresh session brings up a clean one.
- **Disk:** ~23Gi free (tight) — free more before the next stack up.

## Context for Next Session
- Read `docs/superpowers/plans/2026-06-05-automation-designer-golden-e2e.md` (Phase-0 findings + Phase-1 honest status + the close-Phase-1 recipe are all inline).
- Stack env vars + `--no-deps` + admin creds (admin@auraboot.com/Test2026x, token `data.jwt`) + e2et_order/`e2eto:create_e2et_order` are in the plan.
- Harness: `web-admin/tests/e2e/_helpers/flow-designer-harness.ts`. Existing reference spec: `web-admin/tests/e2e/automation/automation-golden.spec.ts` (API-setup; reuse its create/fire/poll patterns for Layer B).
