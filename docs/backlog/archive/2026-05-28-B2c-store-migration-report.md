---
type: backlog
status: closed
created: 2026-05-28
---

<!-- no-precipitation: terminal phase-1 store-migration checkpoint; downstream Phase2/3 shipped (#332/#342/#347) -->

# B2c — useBPMNStore → useFlowStore Migration (Phase 1 Checkpoint)

**Date**: 2026-05-28
**Worktree**: `/Users/ghj/work/auraboot/wt-sdk-b2c-store-migration`
**Branch**: `wt/sdk-b2c-store-migration` (base: `wt/sdk-b2b-t4-batch3`)
**Status**: Phase 1 SHIPPED · Phase 2 designed · Phase 3 deferred
**Owner cutover**: B2d (separate session)

---

## 1. Phase 0 — `useBPMNStore` 707 LOC decomposition matrix

Source: `web-admin/app/plugins/core-designer/components/bpmn-designer/store/useBPMNStore.ts` (707 LOC).
SDK base: `web-admin/app/plugins/core-designer/components/flow-designer-sdk/store/useFlowStore.ts` (270 LOC pre-B2c, 383 LOC post).

### 1.1 State slots

| BPMN slot | SDK has? | Verdict | Notes |
|---|---|---|---|
| `nodes: BPMNNode[]` | `nodes: FlowNode[]` | SDK has — **type mismatch** | BPMN nodes carry `data.type: BPMNNodeType` enum; SDK `FlowNode.data.type?: string`. Adapter must lift. |
| `edges: BPMNEdge[]` | `edges: FlowEdge[]` | SDK has — **type mismatch** | BPMN edges carry `data.condition: ConditionExpression` (already migrated into SDK types in earlier T4 batch). Mostly compatible. |
| `selectedNodeId / selectedEdgeId` | ✅ has | SDK has | direct reuse |
| `isDirty / isSaving` | `isDirty` ✅, `isSaving` ❌ | SDK partial | `isSaving` is BPMN-side UI flag (save spinner) — keep in adapter. |
| `isDeploying` | ❌ | BPMN-only | adapter |
| `isPreviewMode` | ❌ | BPMN-only | adapter (canvas read-only flag) |
| `history / historyIndex` | ✅ has (JSON shallow clone) | SDK has — **clone strategy differs** | BPMN uses `structuredClone` + sanitize (drops React Flow internals like `measured`, `handleBounds`). SDK uses `JSON.parse(JSON.stringify(...))` which already drops functions/refs. **Safe** — JSON clone naturally drops the internals BPMN sanitizes. No SDK change needed. |
| `viewMode: 'design' \| 'monitor'` | `monitorMode: boolean` ✅ | SDK has (different shape) | Adapter normalizes. |
| `instanceStatus: ProcessInstanceNodeStatus` | `monitorData: FlowMonitorData` | SDK has (different shape) | **Bridge required**: BPMN `{currentNodes:[...], completedNodes:[...]}` → SDK keyed map `{nodeId: {status: 'running'\|'completed'}}`. Adapter does the projection. |
| `monitorInstanceId` | ❌ | BPMN-only | adapter |
| `viewingVersionId / _savedCurrentState` | ❌ | BPMN-only | adapter (version preview state machine) |
| `validationResult` | ✅ has | SDK has | direct reuse |
| `processDefinition: BPMNProcessDefinition` | ❌ | BPMN-only | adapter (DTO carrying id/name/key/version/status) |
| `subFlowStack` (NEW in B2c) | ✅ **added in Phase 1** | SDK has | sub-process / CallActivity drilldown |

### 1.2 Actions

| BPMN action | SDK action | Verdict |
|---|---|---|
| `setNodes / setEdges` | ❌ (SDK only has `addNode/updateNode/deleteNode`) | **SDK gap — adapter wraps via state mutation** (these BPMN actions are React-Flow per-frame callbacks; they bypass history on purpose) |
| `addNode / updateNode / deleteNode` | ✅ has | SDK has (signature differs: BPMN takes `Partial<BPMNNode['data']>`, SDK takes `Partial<FlowNode>`) |
| `addEdge / updateEdge / deleteEdge` | ✅ has | SDK has |
| `setSelectedNode / setSelectedEdge` | `selectNode / selectEdge` | rename in adapter |
| `setPreviewMode / setDirty / setSaving` | partial | adapter (only `setDirty` maps direct) |
| `validate()` (158 LOC of BPMN rules) | `setValidationResult` (no built-in rule engine) | **BPMN-only** — keep in adapter; SDK exposes `validateFlow` but BPMN rule set is bespoke (start/end events, gateway conditions, default-flow checks) |
| `loadVersionData / backToCurrent` | ❌ | BPMN-only adapter |
| `deployProcess` (async, calls backend) | ❌ | BPMN-only adapter |
| `setViewMode / setMonitorInstanceId / fetchInstanceStatus / clearInstanceStatus` | `setMonitorMode / setMonitorData` | adapter bridges BPMN viewmode → SDK monitorMode + fetches `ProcessInstanceNodeStatus` then projects to SDK monitorData |
| `undo / redo / canUndo / canRedo` | ✅ has | direct reuse |
| `importFromJSON` (calls `normalizeDesignerJsonPayload`) | `importData` | adapter; BPMN normalize stays in adapter |
| `getNodeById / getEdgeById / getConnectedEdges` | ❌ explicit; can be derived | adapter shim (one-liners over `state.nodes.find`) |
| `setProcessDefinition` | ❌ | BPMN-only adapter |
| `reset` | ✅ has | direct reuse |
| **NEW: `pushSubFlow / popSubFlow / getSubFlowPath`** | ✅ **added in Phase 1** | SDK has |
| **NEW: `getCurrentMonitorNodeIds`** | ✅ **added in Phase 1** | SDK has (helper selector for BPMN UserTask monitor panel) |

### 1.3 Verdict summary

- **~30%** of `useBPMNStore` already maps cleanly onto SDK `useFlowStore` (nodes/edges/selection/history/dirty/import/monitor base).
- **~70%** is BPMN-specific business logic (validate rules / processDefinition / deploy / version state / monitor projection) → **adapter layer only**, must NOT pollute SDK.
- **2 SDK gaps** identified and closed in Phase 1: sub-flow drilldown + currentMonitor selector.

---

## 2. Phase 1 — SDK gap fill (**SHIPPED**)

Commit: `842dfc286` on `wt/sdk-b2c-store-migration`.

### 2.1 New SDK surface

```ts
// flow-designer-sdk/store/useFlowStore.ts
export interface SubFlowFrame {
  parentNodeId: string;
  label?: string;
  outerNodes: FlowNode[];
  outerEdges: FlowEdge[];
  outerSelectedNodeId: string | null;
  outerSelectedEdgeId: string | null;
}

interface FlowStoreState {
  // ... existing ...
  subFlowStack: SubFlowFrame[];
  pushSubFlow(parentNodeId, innerNodes, innerEdges, label?): void;
  popSubFlow(): void;
  resetSubFlowStack(): void;
  getSubFlowPath(): Array<{parentNodeId: string; label?: string}>;
  getCurrentMonitorNodeIds(): Set<string>;
}
```

Exported from `flow-designer-sdk/index.ts`:

```ts
export { useFlowStore } from './store/useFlowStore';
export type { SubFlowFrame } from './store/useFlowStore';
```

### 2.2 Design choices (record)

1. **Inner-frame undo is scoped** — `pushSubFlow` resets `history` to a single-snapshot seed of the inner state. Outer-frame history is intentionally discarded (treated as committed at drilldown time). Rationale: undo in inner graph should not pop the user out of the sub-process visualization. Callers wanting cross-frame undo can layer a thin wrapper.
2. **`pushSubFlow` does NOT push a history snapshot** — drilldown is navigation, not a mutation.
3. **`resetSubFlowStack` does NOT revert visible nodes/edges** — caller decides (e.g. on `reset()` the full reset clears everything, but mid-edit "abort drilldown" semantics is caller's call).
4. **`getCurrentMonitorNodeIds` filters by `status === 'running'`** — `FlowMonitorStatus` includes `pending/running/completed/failed/skipped/idle`; only `running` maps to BPMN `currentNodes` (active wait-state).

### 2.3 Test results

`useFlowStore.subFlow.test.ts`: **11/11 pass**

- initial empty stack
- pushSubFlow swaps visible + stacks frame + breadcrumb path
- popSubFlow restores nodes/edges/selection
- multi-level nesting (push/push/pop/pop)
- popSubFlow on empty = no-op
- inner-frame undo stays scoped
- resetSubFlowStack does not touch visible
- reset() clears stack
- monitor selector — off, empty, filtered by status

Full unit suite: **1570/1570 pass** (219 files, 13.86 s). **Zero regression** to existing useFlowStore, BPM SDK batch1/2/3, or any other consumer.

---

## 3. Phase 2 — Adapter (`useBpmFlowStore`) — DEFERRED

**Why deferred**: scope is significantly larger than the 1-session budget allows once Phase 1 is properly tested. Concrete reasons:

1. **Type bridging is non-trivial**: BPMN `BPMNNode` carries `data.type: BPMNNodeType` enum + BPMN-specific `data` shape; SDK `FlowNode` carries `data.label/config + type?:string`. Need either (a) extend SDK FlowNode to be open enough (`data: Record<string, unknown>`), risking widening contract, or (b) adapter does deep map on every selector. Decision needs design review.
2. **`validate()` is 158 LOC of BPMN-specific rules** — must live in adapter, but the BPMN PropertyPanel reads `validationResult` reactively. Adapter must wire SDK validation surface + BPMN rule runner.
3. **22 call sites** across 7 files consume `useBPMNStore` with hook-shape `useBPMNStore((s) => s.X)` and `useBPMNStore.getState()` and `useBPMNStore` as the store ref (passed to ProcessStatusViewer as `const store = useBPMNStore`). Adapter must preserve all three calling conventions.

### 3.1 Recommended Phase 2 design (for next session)

Two valid approaches:

**Option A — Composite zustand store ("BPMN store wraps + delegates to SDK store")**

```ts
// bpm-designer-sdk/store/useBpmFlowStore.ts
export const useBpmFlowStore = create<BpmFlowStoreState>((set, get) => ({
  // BPMN-only state
  processDefinition: null,
  isSaving: false,
  isDeploying: false,
  isPreviewMode: false,
  viewingVersionId: null,
  _savedCurrentState: undefined,
  validationResult: null,

  // Delegate to SDK store
  get nodes() { return useFlowStore.getState().nodes as BPMNNode[]; },
  get edges() { return useFlowStore.getState().edges as BPMNEdge[]; },
  // ...
  addNode: (n) => useFlowStore.getState().addNode(n as any),
  validate: () => { /* 158-LOC BPMN rules, set state.validationResult */ },
  // ...
}));
```

PRO: bounded change to consumers. CON: zustand getters do not trigger re-render — must use proper selectors via `useFlowStore` directly for reactivity, then bridge via a `useSyncExternalStore` shim. Tricky.

**Option B — Adapter hook wrapping both stores ("hook returns merged surface")**

```ts
export function useBpmFlowStore<T>(selector?: (s: BpmFlowState) => T): T {
  const sdkState = useFlowStore();
  const bpmState = useBpmOnlyStore();
  const merged = useMemo(() => ({ ...sdkState, ...bpmState, nodes: sdkState.nodes as BPMNNode[], ... }), [sdkState, bpmState]);
  return selector ? selector(merged) : merged as T;
}
```

PRO: reactivity falls out naturally. CON: cannot do `useBpmFlowStore.getState()` (it's a hook, not a store) → must expose a parallel `getBpmFlowState()` and migrate 6 call sites of `.getState()`.

**Recommendation**: Option A with a thin `subscribeWithSelector` wrapper. Detailed PR plan in Phase 2 session.

### 3.2 Phase 2 test plan (≥10 cases)

- All 22 BPMN store API endpoints round-trip via adapter
- `validate()` BPMN rule cases (start-event missing, gateway no condition, etc.) — port from existing `useBPMNStore.test.ts`
- Adapter's `viewMode='monitor'` correctly populates SDK `monitorMode + monitorData`
- BPMN `instanceStatus.currentNodes` correctly projected to SDK `monitorData`
- `loadVersionData / backToCurrent` round-trip

---

## 4. Phase 3 — Consumer migration — DEFERRED

22 import sites across 7 files. Migration is gated on Phase 2 adapter shipping.

### 4.1 Consumer inventory

| File | LOC using store | Notes |
|---|---|---|
| `BPMNDesigner.tsx` | 7 sites | full destructure + 3 `.getState()` calls + `__bpmnDesignerStore` window debug ref |
| `components/BPMNCanvas.tsx` | 2 sites | hook destructure |
| `components/BPMNPropertyPanel.tsx` | 2 sites | hook destructure |
| `components/BPMNToolbar.tsx` | 2 sites | hook destructure |
| `components/nodes/UserTaskNode.tsx` | 2 sites | `useBPMNStore((s) => s.instanceStatus)` |
| `components/ProcessStatusViewer.tsx` | 3 sites | `const store = useBPMNStore` then `store.setState/getState` — local fork of state for read-only preview |
| `hooks/useNodeMonitorStatus.ts` | 3 sites | `viewMode + instanceStatus` reader; legacy 3-state `'active' \| 'completed' \| 'idle'`. Already replaced by SDK `useNodeMonitorStatus` (different status enum). Migration to SDK monitor + currentMonitorNodeIds selector. |

### 4.2 Suggested migration order (smallest blast radius first)

1. `useNodeMonitorStatus.ts` — rewrite as thin shim over SDK `getCurrentMonitorNodeIds` + `monitorData[nodeId]`. Single file. No consumer changes.
2. `UserTaskNode.tsx` — same source already touched by step 1.
3. `ProcessStatusViewer.tsx` — local-fork pattern. Use SDK `importData` on a scoped store instance.
4. `BPMNToolbar / BPMNPropertyPanel / BPMNCanvas` — destructure consumers, replace import.
5. `BPMNDesigner.tsx` — last (most call sites, includes window debug ref).

**Per the task spec, double-write is mandatory**: leave `useBPMNStore.ts` intact until B2d.

---

## 5. SDK contract for B2d (page cutover)

When B2d (page mount cutover) lands, expect these requirements on the SDK side:

1. `useFlowStore.subFlowStack` is the single source of truth for drilldown nav. BPMNDesigner's breadcrumb component should subscribe via `useFlowStore((s) => s.getSubFlowPath())`.
2. Monitor data injection should go through `<FlowDesigner monitorMode monitorData={projectedFromInstanceStatus} />` (already supported in the SDK FlowDesigner core). Adapter does the projection once on `fetchInstanceStatus` resolve.
3. The window debug ref `__bpmnDesignerStore` should expose the **adapter** store (not the underlying SDK store) so existing E2E selectors that introspect store state continue to work.
4. Type compatibility: BPMN`Node[]` ↔ `FlowNode[]` cast is safe because BPMN nodes are structural supersets of FlowNode (all required fields present). Verify with TS compiler in Phase 2.

---

## 6. Open questions for owner

1. **Type strategy** (Phase 2 P0): keep BPMN-specific types in adapter and use `as unknown as FlowNode[]` casts at the boundary, OR widen `FlowNode.data` to `Record<string, unknown>` in SDK? Latter touches Automation/Studio FlowDesigner consumers — needs broader review.
2. **`useBPMNStore.test.ts` migration**: should the existing 158-LOC validate test suite move to the adapter (it tests BPMN rules), or stay in `bpmn-designer/store/__tests__/` until B2d removal?
3. **Window debug ref `__bpmnDesignerStore`**: E2E specs may grep for this. Phase 2 must preserve the symbol on the adapter.

---

## 7. Status / next step recommendation

- ✅ **Phase 1 done, committed, full vitest 1570/1570**, 11 new cases for sub-flow + monitor selector.
- ❌ Phase 2 (adapter) — needs **1 design discussion** (Option A vs B + type strategy) + ~3-5 work-days for clean ship including 10+ adapter tests.
- ❌ Phase 3 (consumer migration) — depends on Phase 2. ~2 work-days for 22 sites with vitest re-runs after each.

**Recommendation to main session**:

> Do **NOT** jump to B2d yet. Open a new B2c-followup session focused on Phase 2 with the design choice locked first. Once adapter ships green, Phase 3 + B2d can be batched in a single follow-on session because they share the same consumer-file edits.

If urgency requires B2d to move, Phase 1 sub-flow APIs are stable and can be used directly by new BPMN code (e.g. CallActivity click-to-drill) without waiting for adapter.
