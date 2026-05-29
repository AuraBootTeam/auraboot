# T4 cutover session — B2c phase3 batch2 complete; B2d (mount-side cutover) pending

**Session date**: 2026-05-29
**Worktree**: `/Users/ghj/work/auraboot/wt-sdk-b2c-final`
**Branch**: `wt/sdk-b2c-final`
**Base**: `origin/main @ 5e6fff2a7` (contains #344 TimeSeriesPort Optional fix, #342 batch1, #345 login fullbleed)
**Commits**: 1 — `124fbb665` (batch2 migration + 2 adapter fixes)

---

## 1. Outcome

| Phase | Plan | Actual |
|-------|------|--------|
| Phase 1 — verify #344 isolated stack | required | ✅ green, 3/3 stable |
| Phase 2 — B2c phase3 batch2 (6 consumers) | 6 named | ✅ 4 real consumers + 1 test migrated; 6 in original count overcounted (3 of those already shipped in #342) |
| Phase 3 — B2d 5-step cutover | required | ⏭ **NOT executed** — architecture decision回路 checkpoint |
| Phase 4 — delete legacy | required | ⏭ NOT executed — gated on Phase 3 |
| Phase 5 — teardown + report | required | ✅ stack stopped, disk recovered to 18 GB free |

**T4 overall progress after this session**: B2a (G5–G8 hooks) + B2b1/2/3 (12 BPMN node + editor ports) + B2c phase1/2 (sub-flow + adapter) + B2c phase3 batch1 (3 consumers) + **B2c phase3 batch2 (4 remaining consumers)** = **B2c COMPLETE**. Remaining: B2d.1 → B2d.5.

---

## 2. Phase 1 — E2E gate result (3-run stability against #344)

Isolated stack: `auraboot-wt-sdk-b2c-final` (offset 50, backend 6495, vite 5225, postgres 5484, redis 6530).
Preflight: `curl http://localhost:6495/actuator/health` → `{"status":"UP"}`.

```
RUN 1: 25 passed (17.7s) | 1 skipped | 0 failed
RUN 2: 25 passed (7.3s)  | 1 skipped | 0 failed
RUN 3: 25 passed (5.2s)  | 1 skipped | 0 failed
```

Verifies #344 unblocks isolated stack startup end-to-end (backend + plugin import + setup + auth + bpm-workflow runtime UI).

**Caveat**: `bpm-workflow.spec.ts` exercises the **runtime task-center / process-management UI**, not the BPMN designer itself. The main-conversation prompt selected this as the gate per B2b3 §9, and it is the appropriate proxy for "isolated stack + BPM backend is healthy". Designer-specific E2E specs live under `tests/e2e/bpm/designer-*.spec.ts` and were partially exercised in §4 below.

---

## 3. Phase 2 — B2c phase3 batch2: actual scope

The original plan said "6 consumers". Real grep of legacy `useBPMNStore` import sites:

| File | Status before this session | Action |
|------|---------------------------|--------|
| `bpmn-designer/store/useBPMNStore.ts` | source (double-write keep) | unchanged |
| `bpmn-designer/store/__tests__/useBPMNStore.test.ts` | tests the store itself | unchanged (deleted in B2d.5) |
| `bpmn-designer/hooks/useNodeMonitorStatus.ts` | migrated in #342 | n/a |
| `bpmn-designer/components/nodes/UserTaskNode.tsx` | migrated in #342 | n/a |
| `bpmn-designer/components/ProcessStatusViewer.tsx` | migrated in #342 | n/a |
| `bpmn-designer/BPMNDesigner.tsx` | legacy | ✅ migrated |
| `bpmn-designer/components/BPMNCanvas.tsx` | legacy | ✅ migrated |
| `bpmn-designer/components/BPMNPropertyPanel.tsx` | legacy | ✅ migrated |
| `bpmn-designer/components/BPMNToolbar.tsx` | legacy | ✅ migrated |
| `bpmn-designer/components/__tests__/BPMNToolbar.test.tsx` | drives the toolbar | ✅ migrated + `__flush` added |

Net: **4 production migrations + 1 test migration**. Migration is pure import-rename via `useBpmFlowStore as useBPMNStore`.

### Adapter fixes uncovered + bundled

Two real adapter defects were exposed when consumers actually exercised the no-arg destructure path. Both fixes are in the same commit because consumers couldn't type-check / test without them:

1. **Overload ordering (TS2339)** — `interface UseBpmFlowStore` declared `<T>(selector?) => T` before `() => BpmFlowState`. TS overload resolution picks the first match, so destructures like `const { processDefinition } = useBPMNStore()` inferred `unknown` → every property access errored. Fixed by reordering: no-arg overload first.

2. **Microtask deferral exposed to tests** — `subscribeBoth` dedupes cross-store fan-out via `queueMicrotask`. Legacy `useBPMNStore` (raw zustand) fired listeners synchronously, so existing tests' `act(() => setState(...))` saw immediate re-render. Adapter requires `await useBPMNStore.__flush()` after the setState. The `__flush` helper was already exported on the adapter; documented in BPMNToolbar.test.tsx.

Production code does **not** observe issue 2 because React's normal commit cycle drains microtasks between event-handler invocations and render. The only observed impact is in synchronous-then-assert test patterns.

### Verification

- `tsc --noEmit` — clean except for **1 pre-existing** unrelated error in `flow-designer-sdk/__tests__/NodeRuntimeStatus.test.tsx` (FlowNodeDefinition.icon required, present in main before this session).
- `vitest run` — **1619 / 1619 pass (223 files)**.
- `bpm-workflow.spec.ts × 1` post-migration — 25 pass / 1 skip / 0 fail (same as baseline).
- `designer-roundtrip.spec.ts × 1 + B4.1 retry` — 19 pass / 1 first-run flake (B4.1 timeout waiting for create button on /p/bpm_process_management — passed on rerun, classic cold-mount flake unrelated to BPMNDesigner mount).

---

## 4. Phase 3 — B2d cutover: deliberately NOT executed

### Why checkpoint here

Per the new canonical `docs/standards/core/agent-collaboration.md` §「架构层决策回路」, structural cutovers (replacing component mount, deleting legacy code paths) require a checkpoint+report before subagent proceeds. The B2d.2 step ("Replace the body of `BPMNDesigner.tsx` with `<FlowDesigner>` + `registerBpmSdkAll()`") triggers this gate because:

1. **Scope**: BPMNDesigner.tsx (631 LOC) + BPMNCanvas.tsx (341) + BPMNToolbar.tsx (169) + BPMNPropertyPanel.tsx (238) = **1379 LOC of integration surface** to translate into `<FlowDesigner config={...} initialData={...} onSave={...} monitorMode={...} monitorData={...} />` prop shape. Includes:
   - versioning panel + version preview / exit
   - monitor mode + per-node status fetch loop
   - test hooks bridge (`installDesignerTestHooks`)
   - sub-flow drilldown
   - palette (BPMN_PALETTE_ITEMS → SDK paletteSource)
   - process-definition save/load + dirty/saving state

2. **Phase 3 failure mode**: Plan says "rollback cutover commit + commit batch2 + report". A partial cutover that breaks any designer-* E2E spec would force a revert anyway. Doing 4+ hour cutover in the same session as a 60-120 min batch invites half-done work.

3. **B2b3 §9 explicit guidance**: "Each step is independently shippable; B2d.1 + B2d.2 unlock the visual cutover; B2d.3–.5 are tidy." Batch2 was always intended as the prep step before a separate B2d.2 session.

### What B2d.2 cutover should look like (notes for next session)

- `FlowDesigner` (in `flow-designer-sdk/core/FlowDesigner.tsx`) takes `FlowDesignerConfig { nodeDefinitions, categoryOrder?, showMinimap?, showControls? }` + `initialData?: FlowData` + `onSave?: (data: FlowData) => Promise<void>` + monitor/readOnly/autoSave props.
- `registerBpmSdkAll` (from `bpm-designer-sdk/registerBpmSdkBatch3Nodes.ts`) is the single call to register all 12 BPMN nodes on the SDK NodeRegistry.
- Translation gaps to resolve:
  - `BPMNProcessDefinition` ↔ `FlowData` (likely shape-compatible after `toFlowNodes`/`toFlowEdges` in adapter)
  - Versioning panel — currently bolted onto BPMNDesigner; SDK has no slot for it. Options: keep as outer wrapper around `<FlowDesigner>`, or extend SDK with a `headerSlot` prop.
  - Test hooks (`installDesignerTestHooks`) — needs to migrate to subscribe to `useBpmFlowStore` instead of `useBPMNStore`. Trivial.
  - Palette — `BPMN_PALETTE_ITEMS` ports to a SDK `paletteSource`. B2b3 report §9 §B2d.3.

### Phase 4 — delete legacy (gated)

Per plan, only after Phase 3 E2E gate is green. Should NOT delete:
- `bpmn-designer/types.ts` and `bpmn-designer/constants` — actively imported by `bpm-designer-sdk/store/useBpmFlowStore.ts` (`BPMNProcessDefinition`, `BPMNNodeType`, etc.). Need to migrate these into `bpm-designer-sdk/` first (B2d.5).

---

## 5. Tear-down + disk

```
docker ps --filter name=auraboot-wt-sdk-b2c-final → 0 containers
df -h / → 18 GB free (was 21 GB before stack start)
```

Worktree `wt-sdk-b2c-final` retained for parent agent to push / cherry-pick / continue B2d.

---

## 6. Recommended next steps (for parent / next session)

1. **Push `wt/sdk-b2c-final` + open PR** for batch2 (1 commit `124fbb665`). Low-risk: adapter fixes + import renames. Land before starting B2d.2 to keep the cutover commit clean.
2. **Open dedicated B2d session** (≥ 4 hours estimated):
   - B2d.1 — register `bpmSequenceFlow` edge in SDK edgeRegistry (small, 30 min)
   - B2d.2 — BPMNDesigner mount-side cutover (largest step, 2-3 hours)
   - B2d.3 — palette port (1 hour)
   - B2d.4 — E2E gate (`tests/e2e/bpm/designer-*.spec.ts` full slice × 3 runs)
   - B2d.5 — delete legacy `bpmn-designer/{components,store,hooks}/` + migrate `types`/`constants` into SDK
3. **Adapter improvement (optional)**: the microtask-deferral test ergonomics issue (§3.2) could be addressed by exposing a `useBpmFlowStoreSyncForTests` variant that disables the dedupe, or by changing `subscribeBoth` to fire synchronously when not under React. Defer until B2d lands.

---

## 7. Reproduction commands

```bash
cd /Users/ghj/work/auraboot/wt-sdk-b2c-final

# Start stack (no rebuild needed unless backend Java changes)
./scripts/dev/start-isolated.sh --e2e --offset=50 --wait

# Vitest baseline
cd web-admin && ./node_modules/.bin/vitest run

# E2E gate
PLAYWRIGHT_BASE_URL=http://localhost:5225 \
  BACKEND_URL=http://localhost:6495 BE_PORT=6495 BFF_PORT=3552 \
  BFF_URL=http://localhost:3552 VITE_PORT=5225 \
  PG_HOST=localhost PG_PORT=5484 PG_DB=aura_boot PG_USER=auraboot PGPASSWORD=auraboot_dev \
  PW_SKIP_WEBSERVER=1 PW_WORKERS=2 \
  ./node_modules/.bin/playwright test tests/e2e/bpm/bpm-workflow.spec.ts \
  --project=chromium --reporter=line

# Teardown
./scripts/dev/stop-isolated.sh --slug=wt-sdk-b2c-final
```
