---
type: backlog
status: active
created: 2026-05-28
---

# B2c-followup — `useBpmFlowStore` Adapter + Consumer Migration

**Date**: 2026-05-28
**Worktree**: `/Users/ghj/work/auraboot/wt-sdk-b2c-followup`
**Branch**: `wt/sdk-b2c-followup` (base: `wt/sdk-b2c-store-migration`)
**Base report**: `docs/backlog/2026-05-28-B2c-store-migration-report.md` (Phase 1)
**Status**: Phase 2 SHIPPED · Phase 3 in progress (double-write maintained, no consumer cutover by default)

---

## 0. Phase 0 — Option A vs Option B re-evaluation

The Phase 1 report recommended **Option A** (composite zustand store delegating to SDK
`useFlowStore`). After grep-driven inspection of consumers + SDK + `useBPMNStore`, I
**flipped to a hybrid** that preserves Option A's *external selector shape* but uses
**Option B's hook-composition** under the hood.

### 0.1 What forced the flip

| Risk Option A (pure composite zustand) | Evidence |
|---|---|
| **Zustand getters do not trigger re-render.** Option A as sketched in the Phase 1 report (`get nodes() { return useFlowStore.getState().nodes }`) breaks reactivity entirely — selectors fire on slice change, not on read-through. | `useFlowStore` and `useBPMNStore` both use `create(...)` with shallow `===` slice equality. A getter property is invoked on every selector call but does not register a subscription on `useFlowStore`. |
| **Middleware mismatch.** `useBPMNStore` uses `immer + subscribeWithSelector`; SDK `useFlowStore` is plain. Composing two stores with different middleware stacks via "wraps" requires re-implementing the immer ergonomics inside Option A. | `useBPMNStore.ts` L189-190 imports immer + subscribeWithSelector explicitly. |
| **`useBPMNStore` as store ref** (`const store = useBPMNStore` in `ProcessStatusViewer.tsx` L80, then `store.setState({...})` L136/L159) needs `.setState()` available on the adapter. A composite zustand store can provide this **only if** it owns the state — at which point it is no longer "delegating to SDK", just a parallel store (= no consolidation win). | Confirmed via `grep -n "store\\." components/ProcessStatusViewer.tsx`. |

### 0.2 What the hybrid keeps

Keeps the **stated goal** of Option A: *selectors at the call site look identical to
`useBPMNStore((s) => s.X)`* so the consumer-side diff in Phase 3 is just an import-path
rename plus a few `.getState()` migrations.

### 0.3 Hybrid mechanism

`useBpmFlowStore` is a **hook function** with attached static helpers:

```ts
// Hook form: useBpmFlowStore(selector?) returns merged BpmFlowState
function useBpmFlowStore<T>(selector?: (s: BpmFlowState) => T): T;

// Store-like statics for .getState() / .setState() / .subscribe() consumers
useBpmFlowStore.getState: () => BpmFlowState;
useBpmFlowStore.setState: (partial: Partial<BpmFlowState> | ((s: BpmFlowState) => Partial<BpmFlowState>)) => void;
useBpmFlowStore.subscribe: (listener: (s: BpmFlowState, prev: BpmFlowState) => void) => () => void;
```

Internally the adapter:

1. Holds a **sidecar zustand store** `useBpmOnlyStore` for BPMN-only state
   (processDefinition / isSaving / isDeploying / isPreviewMode / viewMode /
   instanceStatus / monitorInstanceId / viewingVersionId / _savedCurrentState /
   validationResult). No immer (state is simple enough).
2. **Delegates** nodes/edges/selection/history/dirty/import/reset to SDK `useFlowStore`.
3. The hook composes both stores with a memoized merge so React subscriptions fire on
   *either* underlying store update — the standard cross-store hook pattern.
4. `getState()` merges both snapshots on demand; `setState(partial)` fans out per-key
   to the correct store; `subscribe()` chains both stores' subscribe and dedupes calls.
5. BPMN-specific actions (`validate`, `loadVersionData`, `backToCurrent`, `deployProcess`,
   `setViewMode`, `setMonitorInstanceId`, `fetchInstanceStatus`, `clearInstanceStatus`,
   `setProcessDefinition`, `importFromJSON`) live on the sidecar store but transactionally
   touch SDK `useFlowStore` where shared state (nodes/edges/history) is involved.

This means the *signature* contract from the Phase 1 report (Option A: external API mirrors
`useBPMNStore`) is honored; the *implementation* is Option B (hook-composes both stores +
exposes `.getState`/`.setState`/`.subscribe` shims to preserve call-site shapes).

### 0.4 Why this is *not* "parallel store with no consolidation win"

The SDK `useFlowStore` is now the single source of truth for nodes / edges / selection /
history / monitor / sub-flow stack. The BPMN sidecar store owns only the BPMN-specific
70% (validate, processDefinition, version preview, deploy lifecycle). Once Phase 3 lands,
deleting `useBPMNStore.ts` removes a 707-LOC parallel implementation; the sidecar is
roughly 250 LOC of BPMN-only logic with zero duplicated state.

---

## 1. Phase 2 — `useBpmFlowStore` adapter

**Location**: `web-admin/app/plugins/core-designer/components/bpm-designer-sdk/store/useBpmFlowStore.ts`
**Exports**: re-exported from `bpm-designer-sdk/index.ts`.
**Tests**: `bpm-designer-sdk/store/__tests__/useBpmFlowStore.test.ts`.

### 1.1 Public surface (mirrors `useBPMNStore`)

State:
- `processDefinition / nodes / edges / selectedNodeId / selectedEdgeId`
- `isPreviewMode / isDirty / isSaving / isDeploying`
- `history / historyIndex`
- `viewMode / instanceStatus / monitorInstanceId`
- `viewingVersionId / _savedCurrentState`
- `validationResult`

Actions:
- `setProcessDefinition / setNodes / setEdges`
- `addNode / updateNode / deleteNode`
- `addEdge / updateEdge / deleteEdge`
- `setSelectedNode / setSelectedEdge`
- `setPreviewMode / setDirty / setSaving`
- `validate`
- `reset`
- `loadVersionData / backToCurrent`
- `deployProcess`
- `setViewMode / setMonitorInstanceId / fetchInstanceStatus / clearInstanceStatus`
- `undo / redo / canUndo / canRedo`
- `importFromJSON`
- `getNodeById / getEdgeById / getConnectedEdges`

### 1.2 Type bridging

`BPMNNode = Node<BPMNNodeData>` (xyflow). SDK `FlowNode` has `data: { label; config; type? }`.
BPMN `data` is a structural superset (`type: BPMNNodeType + label + config + ...`).
Cast helpers `toFlowNode(bpmn): FlowNode` and `fromFlowNode(flow): BPMNNode` provide a
single place to localize the cast (currently a single-typed `as unknown as` boundary;
runtime is identical because xyflow does not introspect `data`).

### 1.3 Test results

**`useBpmFlowStore.test.ts`: 18/18 pass**

- adapter shape preserves all 28 selector keys from useBPMNStore
- delegated reads: nodes/edges/selection/history/dirty come from useFlowStore
- delegated writes: addNode/addEdge/updateNode/updateEdge/deleteNode/deleteEdge update useFlowStore
- setNodes / setEdges (per-frame React Flow callbacks) bypass history snapshot
- BPMN-only state: setProcessDefinition / setSaving / setPreviewMode round-trip
- validate(): start_event_required + exclusive_gateway_edge_missing_condition cases
- reset(): clears both sidecar and SDK store
- loadVersionData / backToCurrent: preserves _savedCurrentState round-trip
- setViewMode('design') clears instanceStatus + monitorInstanceId
- fetchInstanceStatus → state.instanceStatus populated (mocked service)
- importFromJSON via normalizeDesignerJsonPayload
- undo/redo/canUndo/canRedo delegate
- getNodeById/getEdgeById/getConnectedEdges
- **`.getState()` shim**: merged snapshot returns both BPMN-only and SDK keys
- **`.setState()` shim**: partial update routes per-key to correct store
- **`.subscribe()` shim**: fires on either store mutation, de-duplicated

---

## 2. Phase 3 — consumer migration

Per task spec, **double-write is mandatory**: `useBPMNStore.ts` is unchanged and no
consumer file is cut over by default. Migration would land in B2d (page mount cutover).

### 2.1 Status

Not started in this session. Adapter is shipped and unit-tested, but no consumer was
flipped from `useBPMNStore` → `useBpmFlowStore`. Rationale:

1. The adapter's `.getState()` + `.setState()` + selector shims need a live `<BPMNDesigner>`
   render cycle to validate behavior (the `__bpmnDesignerStore` window debug ref pattern
   in `BPMNDesigner.tsx` L125 + `installDesignerTestHooks`). That validation is
   E2E-shaped, not vitest-shaped. Doing it under vitest would require a JSDOM render of
   the full designer, which is brittle.
2. Migrating consumers without a working E2E gate violates [red line #1] (testing-before-done):
   we would be shipping behavioral changes whose only proof is "vitest of the adapter".
3. The task spec explicitly allows "Phase 3 partial / 0 migrated" and asks for a status
   report.

### 2.2 Recommended next-session sequence

Same as Phase 1 report §4.2 (blast-radius ascending):

1. `hooks/useNodeMonitorStatus.ts` (3 sites) — rewrite as shim over
   `useFlowStore.getCurrentMonitorNodeIds()` + `monitorData[nodeId]`.
2. `components/nodes/UserTaskNode.tsx` (1 site) — same source as step 1.
3. `components/ProcessStatusViewer.tsx` (3 sites — `.setState()` / `.getState()`) —
   adapter `.setState()` shim covers; verify scope-isolation semantics still hold.
4. `components/BPMNCanvas.tsx` / `BPMNToolbar.tsx` / `BPMNPropertyPanel.tsx`
   (2 sites each) — destructure rename only.
5. `BPMNDesigner.tsx` (7 sites including `__bpmnDesignerStore` ref) — last; needs E2E
   validation.

### 2.3 What the next session must NOT do

- Delete `useBPMNStore.ts` — that is B2d.5 + post-E2E.
- Delete any `bpmn-designer/components/*.tsx` — same.
- Change `BPMNDesigner.tsx` entry point mounting — that is B2d.2.

---

## 3. vitest full-suite result

Recorded after each commit; final run pasted at session end below the commit log.

---

## 4. Remaining backlog (file-level)

| Item | File | Owner | Notes |
|---|---|---|---|
| Phase 3 migration | `hooks/useNodeMonitorStatus.ts` | next session | shim over `useFlowStore` |
| Phase 3 migration | `components/nodes/UserTaskNode.tsx` | next session | swap selector source |
| Phase 3 migration | `components/ProcessStatusViewer.tsx` | next session | verify `.setState` semantics |
| Phase 3 migration | `components/BPMNCanvas.tsx` | next session | import rename |
| Phase 3 migration | `components/BPMNToolbar.tsx` | next session | import rename |
| Phase 3 migration | `components/BPMNPropertyPanel.tsx` | next session | import rename |
| Phase 3 migration | `BPMNDesigner.tsx` | next session | last; includes `__bpmnDesignerStore` ref |
| B2d entry-point cutover | `BPMNDesigner.tsx` | B2d session | mount FlowDesigner shell + adapter |
| Delete legacy store | `bpmn-designer/store/useBPMNStore.ts` | B2d.5 (post-E2E) | only after green E2E |
| Delete legacy components | `bpmn-designer/components/*.tsx` | B2d.5 | replaced by SDK + adapter |

### 4.1 SDK additions (none required)

Adapter requires nothing new from SDK side. Phase 1 already delivered the sub-flow APIs
and `getCurrentMonitorNodeIds` selector that the legacy store lacked.

---

## 5. Commits

(See `git log wt/sdk-b2c-store-migration..HEAD` once committed.)
