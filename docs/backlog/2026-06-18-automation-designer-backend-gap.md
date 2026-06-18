---
type: backlog
status: closed
created: 2026-06-18
---
<!-- no-precipitation: gap tracker for a single closed engagement; the durable, reusable lessons are
     carried by the companion retro docs/retro/2026-06-18-automation-designer-backend-gap-retro.md
     (§Durable lessons). This file is the per-gap audit trail, not a reusable lesson. -->

# Automation Designer ↔ Backend — coverage / UX / front-back-linkage gap analysis (2026-06-18)

/ aura-endgame P1–P4. Goal (owner): analyze the Automation **designer ↔ backend linkage**, current
test coverage, page UX interactivity — every component / every property / every action point / every
visual feedback — output a complete plan + gaps, then close them to golden and report.

## TL;DR — this is a mature, already-merged, comprehensively golden feature

Automation was driven to a documented "golden goal COMPLETE" on 2026-06-07 and **all the work is merged
to `main`** (squash commits `9d1da6087` #438, `243cd79ea` #436, `6291592a8` #437, `fcae43677` #452,
`c9e018561` #453, `3d7f7cb0d` #488, `8330ab63b` #490, `47bfdaaf4` #498, `c88d00db3` #557,
`7e0d07125` #562, `a9fecf383` #576, `7d32d2d24` #628). `gh pr list --search automation` = `[]`
(no open PRs → 收口 done).

Independent re-count (§15, sample≠count): **95 Playwright E2E** (10 specs) · **386 backend `@Test`**
(42 files) · **48 frontend unit** (9 files). The designer-golden suite drives the **real @xyflow
designer** (drag → property-panel config → connect → save → reload → enable → fire real trigger →
assert AutomationLog status + node-status overlay + DB side-effect) for **15/15 in-scope node types**
with a happy/sad/edge/corner matrix (30 cases, documented 3× flake-clean, `--retries=0`), backend IT
**81.3%**. The earlier Explore sweep's "no E2E for scheduled / execute-command / send-webhook /
start-process / NOT" claims were **phantom gaps** (refuted — all present: `N-SCHEDULED`,
`N-EXECUTE-COMMAND`(+SAD), `N-SEND-WEBHOOK-OUTBOUND`(+SAD), `N-START-PROCESS`, `N-TRIGGER-BPM-EVENT`).

So this engagement is **gap-closure + verification on a near-golden feature**, not a build. The honest,
evidence-confirmed remaining gaps are narrow and listed below.

## System inventory (verified, file:line-backed)

### Frontend — designer (hand-written TSX on the Flow Designer SDK, @xyflow/react)
- Routes: `/automations` (list) + `/automation/:id` (editor) — `plugins/core-automation/pages/*`.
- Components: `AutomationList`, `AutomationEditPageImpl`, `AutomationEditor`, `AutomationDebugger`
  (+ `DebugToolbar`/`DebugVariablePanel`/`DebugLogPanel`), `ExecutionLogDialog`, `TemplateGallery`,
  `TemplatePreviewDialog`; SDK: `FlowDesigner`/`FlowCanvas`/`FlowPalette`/`FlowPropertyPanel`/
  `DefaultFlowNode`/`PropertyField`; shared `DesignerToolbar`.
- Node types (19): **8 triggers** (record-create/update, field-change, state-change, scheduled,
  webhook, bpm-event, inactivity) · **8 actions** (update-record, create-record, send-notification,
  execute-command, call-api, send-webhook, start-process, llm-call) · **3 controls** (condition,
  delay, loop).
- Property controls (12): text, textarea, number, boolean, select, multiselect, json, expression,
  model-select, field-select, process-select, command-select, rule-binding (composite).
- Action points: **List** — Create, Import, New-from-Template, Toggle enable/disable, View Logs,
  Export, Edit, Delete, **Duplicate**(endpoint). **Editor** — Save (validation-gated), Test Run,
  Debug, Export, Undo, Redo, drag-from-palette, select node, connect edge, delete node, update config.
  **Debug** — Step, Continue, Stop, Restart, Toggle Breakpoint, SSE events stream.
- Visual feedback: node category colours (trigger green / action blue / control yellow), selection
  ring, **5 runtime status rings+badges** (pending/running/completed/failed/skipped), list status
  badge (enabled/disabled), empty state, dirty "Unsaved" / "Saving…" toolbar badges, save-blocked +
  field-level validation error, log-dialog 5 status badges, debug action-list states (pending/success/
  failed/current/breakpoint).

### Backend — engine
- Tables: `ab_automation` (trigger_type/trigger_config JSONB/trigger_condition SpEL/actions JSONB/
  flow_config JSONB/enabled, tenant-interceptor-exempt), `ab_automation_log` (status PENDING/RUNNING/
  SUCCESS/FAILED/SKIPPED + action_results JSONB), `ab_automation_node_execution` (G5 per-node overlay).
- Controllers: `AutomationController` (16 endpoints incl. CRUD + enable/disable/toggle/**duplicate**/
  validate/logs/trigger/cleanup, all `@RequirePermission`) + `AutomationWebhookController` (JWT-exempt,
  HMAC/token fail-closed).
- Pipeline: trigger fires (event bridge AFTER_COMMIT / scheduler / webhook) → load → SpEL condition
  (+optional decision/rule binding M4) → AutomationLog PENDING → `AutomationFlowCompiler` flowConfig→
  BPMN → SmartEngine (MEMORY storage) → `AutomationActionServiceTaskDelegate` → `CompositeActionExecutor`
  → 11 executors (10 action + ControlNode for condition/delay/loop) → node-execution + action-results.
- Debug: `DebugSessionService(Impl)` state-machine (createSession/step/continueExecution/stop/restart/
  getContext/updateBreakpoints/subscribeEvents) + `DebugEventPublisher` (SSE). REST under
  `/api/automation/debug/sessions/*` + `/api/automation/{id}/debug/sessions`.

## Coverage matrix (verified)

| Surface | Status | Evidence |
|---|---|---|
| 15/15 in-scope node types — real-UI golden happy + 4-path | ✅ golden | `automation-designer-golden.spec.ts` 34 tests; matrix in `2026-06-06-...node-coverage-gap-and-plan.md` |
| Layer-B behavioral (real engine+DB, API-built) | ✅ | `automation-golden.spec.ts` 18 |
| Property pickers (model/field/command/process-select, expression, json) | ✅ exercised | designer-golden grep: command-select 5, field-select 4, model-select 4, process-select 2, expression 15, json 21 |
| Save validation gate / required field error / dangerous SpEL | ✅ | designer-golden S1/S2 |
| Persistence roundtrip (reload re-render) | ✅ | designer-golden H2 |
| Runtime status badges via ?logId | ✅ | designer-golden H3 |
| Lifecycle enable/disable/re-enable via real toggle | ✅ | N-CORNER-LIFECYCLE |
| ExecutionLogDialog (view logs) | ✅ E2E+unit | deep/management/llm-call specs + `ExecutionLogDialog.error.test.tsx` |
| Backend executors (all 11) | ✅ IT | 10 executor IT files + ControlNodeExecutorTest(23) |
| Backend IT coverage | ✅ 81.3% | coverage-measurement doc |
| SmartEngine nodes (start-process/bpm-event/delay) | ✅ now real | un-fixme'd; SmartEngine 4.0.0 direct (memory: stub deleted); `N-START-PROCESS`/`N-TRIGGER-BPM-EVENT`/`N-DELAY` present — **needs live re-verify** |

## GAPS — evidence-confirmed, to close

| # | Gap | Evidence | Verify status |
|---|-----|----------|---------------|
| ~~G1~~ | `DebugSessionServiceImplTest` reported 3 red (2026-06-07) | **RESOLVED — phantom/stale**: live run `tests="27" failures="0" errors="0" skipped="0"` (authoritative XML). Fixed when the tenant-ownership guard landed. **NOT a current gap** (2nd phantom caught via §15 re-verify) | ✅ verified green |
| **G2** | **Debug-session UI has 0 E2E** (step/continue/stop/restart/breakpoint/variable+log panels, SSE) | `grep debug/sessions\|startDebug\|breakpoint` in specs = ∅ | ✅ confirmed gap |
| **G3** | `automation-golden` `action-send-webhook` is a **stale test** (old `eventType` model, not the FINDING-10 URL-POST) | spec L1436-1506; stabilization doc follow-up | ✅ confirmed gap |
| **G4** | **Duplicate** action point — **0 E2E** (endpoint `POST /{pid}/duplicate` exists) | `grep /duplicate` specs = ∅ | ✅ confirmed gap |
| **G5** | **Undo/Redo** action point — **0 E2E** (toolbar buttons + canUndo/canRedo disabled states) | `grep undo\|redo` specs = ∅ | ✅ confirmed gap |
| **G6** | rule-binding composite widget + visual-feedback completeness — verify depth, fill if thin | designer-golden rule-binding=0 (only `rule-binding-designer-host.spec.ts` 1 test) | 🟡 verify |

Out of scope / not gaps: the 30-case node golden, picker coverage, IT 81.3% — already golden.

## RESOLUTION (2026-06-18, all closed)

| # | Gap | Resolution | Evidence |
|---|-----|-----------|----------|
| G1 | DebugSessionServiceImplTest 3 red | **Phantom/stale** — already green | live `tests="27" failures="0"` |
| G2 | Debug-session UI 0 E2E + designer-flow debug shows "0 actions" | **Backend fix** `DebugSessionServiceImpl.deriveActionsFromFlow` (derive ordered actions from flowConfig when flat actions[] empty) + **golden** `automation-debug-golden.spec.ts` (step×2 ✓✓ → completed → restart → continue → stop, real backend exec, visual states asserted) | 29/29 unit (27+2 new) green; golden 21 passed; screenshots show ✓ rows + Failed-state + variable/event panels |
| G3 | `automation-golden` send-webhook stale (eventType, asserts "completed") | **Test fix** — rewrote to assert the FINDING-10 url-required contract (url-less node → run fails) + pointer to designer-golden URL-POST happy/sad | golden 21 passed |
| G4 | Duplicate action point missing in UI | **Frontend** — added Duplicate row button → `POST /{pid}/duplicate` + **golden** (duplicate → independent clone, survives source delete) | golden passed |
| G5 | Undo/Redo no browser E2E + first-edit-on-fresh-canvas not undoable | **Frontend fix** `FlowDesigner` always seeds history on mount (empty when no initialData) + **golden** (drag→undo→redo→Ctrl+Z, disabled-state boundaries) | 305 SDK unit pass (no regression); golden passed |
| G6 | rule-binding widget / visual-feedback completeness | **Verified covered** (another phantom) — `rule-binding-designer-host.spec.ts` asserts ruleBinding field + decision block + binding editor + impact preview + test runner | existing spec |

### Code changes (worktree `feat/automation-designer-gap-closure`)
- `platform/.../DebugSessionServiceImpl.java` — `getEffectiveActions` + `deriveActionsFromFlow` + `toAction` (flowConfig→ordered actions for the debugger; flat actions[] path unchanged).
- `platform/.../DebugSessionServiceImplTest.java` — +2 derivation unit tests (27→29).
- `web-admin/.../flow-designer-sdk/core/FlowDesigner.tsx` — always `importData` on mount (seed undo history; clean-slate vs stale singleton). Shared SDK — full vitest suite (305) re-run green.
- `web-admin/.../automation/components/AutomationList.tsx` — Duplicate row button + handler.
- `web-admin/tests/e2e/automation/automation-debug-golden.spec.ts` (new, G2), `automation-action-points-golden.spec.ts` (new, G4+G5), `automation-golden.spec.ts` (G3 send-webhook rewrite).

### Real product defects surfaced + fixed by this gap-closure
1. **Designer-built automations couldn't be debugged** (debugger walked flat actions[], empty for flow-config automations → "0 actions"). Fixed via flowConfig derivation. (G2)
2. **First edit on a fresh designer canvas was not undoable** (history never seeded when no initialData). Fixed in the shared FlowDesigner. (G5)
3. **Duplicate was a dead capability** (endpoint + service method, no UI). Wired. (G4)
4. **A stale send-webhook test** asserted "completed" against a removed code path. Corrected to the real contract. (G3)

## Plan (P5 — close to golden, host-first zero-docker)

- **Infra Phase-0 gate** (§2.1): `scripts/host-e2e-up.sh` (FORCE_HOST, AGENT_LLM_STUB_MODE, SSRF
  allowlist) → host backend + Vite + BFF + seed; confirm reachable before golden claims.
- **G1**: run the test (pure Mockito) → if green, mark resolved with evidence; if red, fix the stub
  mismatch (TDD).
- **G2**: backend IT for the full debug lifecycle (already largely in the unit test) + a real-browser
  golden that opens the debugger, steps through actions, sets a breakpoint, continues, restarts, stops,
  asserting the action-list visual states + variable/log panels + SSE-driven updates. Pair browser
  evidence + backend evidence.
- **G3**: rewrite `automation-golden` send-webhook to the URL-POST (FINDING-10) semantics with a host
  receiver, or fold into the designer-golden outbound case + delete the stale duplicate.
- **G4**: list-page golden — duplicate an automation, assert a new pid clone with the same flow, both
  independently enable/fire.
- **G5**: designer golden — add node → undo (node gone, canRedo enabled) → redo (node back); assert
  disabled-state visual feedback at history boundaries.
- **G6**: drive the rule-binding widget in the designer + assert backend condition binding; confirm
  every visual-feedback state has at least one asserting test.
- **Acceptance**: `/e2e-feature-coverage` matrix clean + `/e2e-truth` 5-dim + full suite 3× flake-clean
  + screenshots +完成前全量复核五项.
