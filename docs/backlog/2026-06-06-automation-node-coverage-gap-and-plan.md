---
type: backlog
status: active
created: 2026-06-06
---

# Automation node coverage — gap analysis & plan (2026-06-06)

## Goal (owner directive 2026-06-06)
1. Document this gap analysis completely (this file).
2. Solve **everything except the SmartEngine-dependent items**.
3. Coverage must be **real designer-UI driven** (drive the actual @xyflow designer in a
   browser), *then* verify backend logic runs correctly.
4. Backend **integration-test coverage → 80%**.
5. UI automation must meet the **golden standard**: every flow covers **happy / sad / edge /
   corner** paths.

## Two senses of "end-to-end" (the crux)
- **Layer A** (`web-admin/tests/e2e/automation/automation-designer-golden.spec.ts`) — drives the
  **real designer UI** in a browser (drag node → connect → configure in the property panel →
  save → enable → fire → assert side-effect + node-status overlay). This is true front↔back e2e.
- **Layer B** (`web-admin/tests/e2e/automation/automation-golden.spec.ts`) — builds the flowConfig
  via `POST /api/automations`, enables, fires a **real** trigger, asserts **real backend** behavior
  (node-status / AutomationLog / DB side-effect). Real engine + DB + command pipeline, **no mocks**,
  but the designer UI is NOT exercised per node.

**The goal requires Layer A (UI-driven) for each non-SmartEngine node, then the Layer-B backend
assertion.** Today Layer A has only ONE representative journey (H1: record-create → condition →
update-record). That is the central gap.

## Current state — per-node (18 types)

| Node | Layer B (real backend e2e) | Layer A (real UI driven) | golden paths today | Category |
|------|---|---|---|---|
| trigger-record-create | ✅ | ✅ (H1) | happy (+ sad via S1/S2 designer) | **solve** (deepen) |
| trigger-record-update | ✅ | ❌ | happy + "create-does-not-fire" | **solve** (add UI + sad/edge/corner) |
| trigger-field-change | ✅ | ❌ | happy + neg | **solve** |
| trigger-state-change | ✅ (any-transition) | ❌ | happy + neg | **solve** (+ FINDING-4b toStates filter) |
| trigger-webhook | ✅ | ❌ | happy | **solve** (+ sad: bad sig; edge) |
| trigger-scheduled | ❌ (skip) | ❌ | none | **solve (heavy)** — cron-realtime; need a fire path |
| trigger-bpm-event | ❌ (skip) | ❌ | none | **SmartEngine-EXCLUDED** (BPM stub) |
| action-update-record | ✅ | ✅ (H1) | happy + sad (S3 bad field) | **solve** (deepen) |
| action-create-record | ✅ | ❌ | happy | **solve** |
| action-send-notification | ✅ | ❌ | happy | **solve** |
| action-execute-command | ✅ (asserts DENIAL) | ❌ | sad (denied) only | **solve** — add permission-free success path |
| action-call-api | ✅ (real HTTP) | ❌ | happy | **solve** (+ sad: 4xx/timeout; edge) |
| action-send-webhook | ✅ (dispatch only) | ❌ | shallow happy | **solve** — assert real outbound POST (register subscription) |
| action-start-process | ⚠️ executor+unit only | ❌ | none | **SmartEngine-EXCLUDED** (BPM stub) |
| action-llm-call | ⚠️ manual once | ❌ | happy (manual, not in CI) | **solve** — stub-seed → CI-portable + UI |
| control-condition | ✅ | ✅ (H1) | happy + both branches (E2/S5) | **solve** (deepen) |
| control-loop | ❌ (skip; IT-covered) | ❌ | none in e2e | **solve** — needs collection-carrying fixture |
| control-delay | ❌ (skip) | ❌ | none | **SmartEngine-EXCLUDED** (SmartEngine timer suspended) |

**Tally:** Layer-B fire-verified = 12/18. Real-UI-driven = 3 node types (H1 journey only).
SmartEngine-excluded = 3 (start-process, bpm-event, control-delay).
**In-scope to solve = 15 node types** (12 Layer-B + scheduled + loop + the depth/UI gaps).

## Gaps (in-scope)

- **GAP-A — per-node real designer-UI cases.** Build Layer-A (browser-driven) cases for each
  in-scope node: drag the node onto the canvas, configure via the property panel, connect, save
  (assert the POSTed flowConfig), reload (assert re-render), enable, fire, assert node-status
  overlay + side-effect. Reuse the Phase-0 harness (`flow-designer-harness.ts`).
- **GAP-B — golden 4-path coverage per flow.** happy (valid → success) / sad (validation or
  runtime failure surfaced with a clear, field-level/node-level reason) / edge (boundary: empty,
  max, missing-optional, threshold) / corner (concurrency, re-entrancy, disabled-then-fire,
  duplicate, unicode/i18n). Both at the UI layer (designer gates) and the backend (node-status).
- **GAP-C — backend integration coverage → 80%.** Measure the current jacoco baseline for the
  automation packages (executor / trigger.impl / listener / bpm / service); add IT to reach 80%.
- **GAP-D — FINDING-4b.** `on_state_change` toStates filter is imprecise: `readCurrentState` runs
  via the tenant-line-interceptor `selectByQuery` and the `@Async` bridge has no MetaContext →
  wrong tenant predicate → null. Fix: set tenant from `event.getTenantId()` before the read
  (mirror `AutomationProcessRuntime.run`), or use `selectByQueryWithoutTenant`. Then a
  `toStates:['cancelled']`-filtered test must pass.
- **GAP-E — llm-call CI-portable.** Make the GA bootstrap seed the LLM provider with the stub
  sentinel `stub_key_for_no_llm_paths` (non-secret) instead of a demo key, OR enable
  `agent.llm.stub-mode` on the ga-e2e stack; then un-fixme the test (UI + backend).
- **GAP-F — deeper assertions.** send-webhook: register a webhook subscription at a host receiver
  and assert the outbound POST landed. execute-command: add a permission-free command to
  test-fixtures and assert the success path (in addition to the existing denial path).

## Out of scope (SmartEngine-EXCLUDED, per directive)
- **action-start-process**, **trigger-bpm-event** — need a non-stub BPM process engine
  (`SmartEngineBpmAdapter` is an in-memory stub: `processEngineService.startProcess` for a
  *deployed business process* → "not implement intentionally"). Executor + unit + derivation are
  done; assembled fire needs enterprise BPM.
- **control-delay** — needs the SmartEngine timer (suspended, roadmap). Front render is covered by
  palette-coverage.
- These stay honest `test.fixme` with the documented reason; NOT faked.

## Plan (phased; feature branch `feat/automation-golden-back-coverage`, §11 isolated GA stack)
- **P0 (this doc + infra):** ✅ doc. Free disk. Bring up GA stack (no rebuild; bootstrap). Confirm
  the Phase-0 designer harness still drives the canvas (smoke).
- **P1 — UI per-node happy (GAP-A):** one Layer-A case per in-scope node driving the real designer
  to a happy success. Verify backend (node-status=completed + DB side-effect) after each.
- **P2 — golden 4-path (GAP-B):** extend each to sad/edge/corner. UI gates (field-level errors,
  SpEL safety, required) + backend failure assertions.
- **P3 — backend fixes (GAP-D/E/F):** FINDING-4b tenant fix (rebuild) + verify toStates filter;
  llm stub seed + un-fixme; send-webhook real-outbound + execute-command success path.
- **P4 — coverage (GAP-C):** measure jacoco baseline, add IT to 80% for the automation packages.
- **P5 — acceptance:** `/e2e-truth` self-audit; ≥3× flake on the UI suite; final per-node matrix
  with execution status + evidence + pass/fail (no cross-layer averaging, §2.4); update PR #438.

## Definition of done
- Every in-scope node: a real-UI-driven Layer-A case (happy) + sad/edge/corner where applicable,
  each followed by a backend assertion. SmartEngine-excluded nodes: documented honest skips.
- Backend automation-package IT coverage ≥ 80% (jacoco evidence).
- `/e2e-truth` audit clean; UI suite 3× flake-free; per-node matrix in the findings doc.
- NO faked green, NO cross-layer averaging, NO real secrets committed.

## Constraints / risks
- **Disk:** host `/` is volatile (~5–9GB, drained by 6 other sessions' stacks). Avoid rebuilds
  where possible; prune artifacts between runs; a backend rebuild (GAP-D) needs headroom.
- This is a multi-session body of work; progress is committed incrementally on the feature branch.
