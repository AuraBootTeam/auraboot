# A3 — flow-designer-sdk BPM-shaped Smoke PoC & T4 Feasibility Report

- Date: 2026-05-28
- Branch: `wt/sdk-bpm-smoke`
- Worktree: `/Users/ghj/work/auraboot/wt-sdk-bpm-smoke`
- Scope: prove the SDK can host BPMN-shaped flows via G1 (`EdgeRegistry`) +
  G2 (`FlowNodeDefinition.propertyEditor`) injection points; quantify the
  effort to migrate the full `bpmn-designer/` consumer onto the SDK (T4).
- Decision context: DDR `2026-05-23 automation/bpm designer convergence`
  Option B; T3 G1 + G2 already landed (see
  `2026-05-23-T3-flow-designer-sdk-enhancement-plan.md`).

## 1. Baseline inventory — `bpmn-designer/` today

Reference: `auraboot/web-admin/app/plugins/core-designer/components/bpmn-designer/`.

### 1.1 Node renderers (9, not 10)

The task brief assumed 10 nodes; the codebase actually has 9. Verified by
`ls components/nodes/`:

| # | Type                  | File                                                                                  | LOC |
|---|-----------------------|---------------------------------------------------------------------------------------|-----|
| 1 | `startEvent`          | `components/nodes/StartEventNode.tsx`                                                 | 52  |
| 2 | `endEvent`            | `components/nodes/EndEventNode.tsx`                                                   | 52  |
| 3 | `userTask`            | `components/nodes/UserTaskNode.tsx`                                                   | 116 |
| 4 | `serviceTask`         | `components/nodes/ServiceTaskNode.tsx`                                                | 80  |
| 5 | `receiveTask`         | `components/nodes/ReceiveTaskNode.tsx`                                                | 54  |
| 6 | `callActivity`        | `components/nodes/CallActivityNode.tsx`                                               | 55  |
| 7 | `exclusiveGateway`    | `components/nodes/ExclusiveGatewayNode.tsx`                                           | 60  |
| 8 | `inclusiveGateway`    | `components/nodes/InclusiveGatewayNode.tsx`                                           | 60  |
| 9 | `parallelGateway`     | `components/nodes/ParallelGatewayNode.tsx`                                            | 60  |

Each node imports `Handle, Position` from `@xyflow/react` and calls
`useNodeMonitorStatus(id)` (`hooks/useNodeMonitorStatus.ts:1`) for live
run-overlay highlighting.

### 1.2 Property editors (14 files)

`ls components/property-editors/`:

| File                                        | LOC | Role                                  |
|---------------------------------------------|-----|---------------------------------------|
| `StartEventEditor` (in `EventEditor.tsx`)   | 93  | start/end shared event panel          |
| `UserTaskEditor.tsx`                        | 278 | assignee/form/candidate users         |
| `ServiceTaskEditor.tsx`                     | 147 | java class / expression / delegate    |
| `ReceiveTaskEditor.tsx`                     | 75  | message receive props                 |
| `CallActivityEditor.tsx`                    | 206 | sub-process binding + in/out mapping  |
| `ExclusiveGatewayEditor.tsx`                | 100 | default flow + condition summary      |
| `InclusiveGatewayEditor.tsx`                | 99  | same                                  |
| `ParallelGatewayEditor.tsx`                 | 41  | none-config                           |
| `EdgeEditor.tsx`                            | 56  | edge name + condition delegate        |
| `ConditionExpressionEditor.tsx`             | 493 | structured/script/MVEL condition UI   |
| `AssigneePicker.tsx`                        | 267 | user/role/group picker w/ remote data |
| `ProcessPicker.tsx`                         | 99  | process key remote picker             |
| `ProcessMetadataPanel.tsx`                  | 140 | top-level process meta panel          |
| `shared.tsx`                                | 627 | inputs/textarea/codeArea/section building blocks |

### 1.3 Edge renderer (1)

| File                                | LOC | Role                                        |
|-------------------------------------|-----|---------------------------------------------|
| `components/edges/ConditionalEdge.tsx` | 99  | smooth-step path + label/condition badge |

### 1.4 Supporting infra (not 1:1 with SDK)

| Area      | File                          | LOC | SDK status                                                                 |
|-----------|-------------------------------|-----|----------------------------------------------------------------------------|
| Store     | `store/useBPMNStore.ts`       | 707 | SDK has `useFlowStore` (lighter; see §3)                                  |
| Service   | `services/bpmnService.ts`     | 650 | SDK has no direct counterpart (validate/save/deploy logic, BPMN XML)      |
| Hooks     | `hooks/useNodeMonitorStatus.ts` | 42 | Not in SDK — runtime monitor overlay                                       |
| Types     | `types/index.ts`              | 295 | SDK uses generic `FlowNode/FlowEdge`; BPMN-specific config types live here |
| Constants | `constants/index.ts`          | 284 | Node geometry, handle classes, palette categories                          |

## 2. SDK capability surface (verified)

- `NodeRegistry` extends `DesignerRegistry<FlowNodeDefinition>`
  (`flow-designer-sdk/nodes/NodeRegistry.ts:8`).
- `EdgeRegistry` exposes `register / registerAll / get / has / clear`
  (`flow-designer-sdk/edges/EdgeRegistry.ts:8`).
- `FlowNodeDefinition.propertyEditor?: React.ComponentType<NodePropertyEditorProps>`
  (`flow-designer-sdk/nodes/types.ts:53`) — G2.
- `FlowEdgeDefinition.editor?: React.ComponentType<EdgePropertyEditorProps>` +
  `.component` (`flow-designer-sdk/edges/types.ts:24`) — G1.
- `FlowEdge.data.condition: ConditionExpression` already supports
  `expression | script` with `language: 'mvel'|'juel'|'spel'` and `ruleCode`
  (`flow-designer-sdk/store/types.ts:20-26`). This is functionally a superset
  of what bpmn-designer's `BPMNEdgeData.condition` carries today.
- `FlowEdge.data.isDefault?: boolean` already present in the SDK store type
  (`flow-designer-sdk/store/types.ts:41`) — gateway default-flow contract is
  natively expressed.
- `NodeValidation.minInputs/maxInputs/minOutputs/maxOutputs` plus a
  `custom(node, ctx)` hook (`flow-designer-sdk/nodes/types.ts:15`) covers the
  BPMN structural rules (start has 0 in, end has 0 out, gateway has 2+ out).

## 3. PoC implementation (this PR)

Location: `web-admin/app/plugins/core-designer/components/bpm-smoke/`
(intentionally one-shot; deleted at the end of T4 once `bpmn-designer/` is
migrated). Files:

- `nodes/BpmNodes.tsx` — 4 renderers (`startEvent`, `exclusiveGateway`,
  `serviceTask`, `endEvent`) with the same handle topology as bpmn-designer.
- `edges/BpmConditionalEdge.tsx` — smooth-step path + label badge + default
  dashed style (mirrors `bpmn-designer/components/edges/ConditionalEdge.tsx`).
- `editors/BpmEditors.tsx` — 4 node editors + 1 edge editor, each wired
  through `NodePropertyEditorProps` / `EdgePropertyEditorProps`.
- `registerBpmSmoke.ts` — `registerBpmSmoke(nodeRegistry, edgeRegistry)`
  side-effect that registers all 4 nodes + the `bpmConditional` edge.
- `__tests__/bpmSmoke.test.tsx` — 7 unit cases (target was ≥5).

### 3.1 Test result

`pnpm exec vitest run app/plugins/core-designer/components/bpm-smoke` →
**7 passed / 0 failed (1.01s)**. `pnpm exec tsc --noEmit` reports **zero new
errors** in `bpm-smoke/`.

The 7 cases cover:

1. Node registration with G2 propertyEditor presence for all 4 types.
2. Edge registration with custom component + editor via G1.
3. Drag-and-drop JSON shape: `start → gateway → 2 branches` with one
   conditional + one default outgoing edge.
4. JSON serialise + parse round-trip preserves `condition.type/content`.
5. G2 editor `onChange` produces correct config patches for `serviceTask`.
6. G1 edge editor patches label / condition / `isDefault`.
7. Factory stability (4 nodes, 1 edge).

### 3.2 PoC result: 4/4 BPMN-shaped node types render and round-trip

All 4 target node types are registered, rendered (handle topology preserved,
xyflow `<Handle>` works), and produce serialisable JSON. The exclusive
gateway successfully expresses two outgoing edges where one carries a
`condition` and the other is marked `isDefault: true` — the core BPMN
divergence semantic.

## 4. Gap classification — full T4 scope

### 4.1 Tier 1: SDK can host **as-is** (no SDK change)

| bpmn-designer asset                                 | SDK seat                  | Notes |
|-----------------------------------------------------|---------------------------|-------|
| `StartEventNode`, `EndEventNode`                    | `FlowNodeDefinition.component` | trivial port; PoC done |
| `ServiceTaskNode`, `ReceiveTaskNode`                | `FlowNodeDefinition.component` | trivial port; PoC done for serviceTask |
| `ExclusiveGatewayNode`, `InclusiveGatewayNode`, `ParallelGatewayNode` | `FlowNodeDefinition.component` | only difference is icon glyph + rotated diamond; same `Handle` topology fits SDK |
| `ConditionalEdge`                                   | `FlowEdgeDefinition.component` | PoC done |
| All editors that are pure form panels (`ParallelGatewayEditor`, `StartEventEditor`, `ReceiveTaskEditor`, `ServiceTaskEditor`) | `FlowNodeDefinition.propertyEditor` | direct port behind G2 |
| Edge condition + isDefault                          | `FlowEdge.data.{condition,isDefault}` | already in SDK store types |
| Structural validation                               | `NodeValidation.{minInputs,maxInputs,minOutputs,maxOutputs,custom}` | already in SDK |

### 4.2 Tier 2: needs **incremental SDK extension** (modest)

| Gap                                                 | SDK change required                                                                                | Est.   |
|-----------------------------------------------------|----------------------------------------------------------------------------------------------------|--------|
| `useNodeMonitorStatus(id)` runtime overlay          | Add `useMonitorOverlay` hook on the SDK side or expose a `useNodeMeta(id)` slot the host can fill | 2 d    |
| `ExclusiveGatewayEditor` reads sibling outgoing edges to populate "default flow" dropdown | Editor prop today only sees `node.data.config`. Need to extend `NodePropertyEditorProps` with `outgoingEdges`/`incomingEdges` derived selectors | 1 d    |
| Pickers that need remote data (`AssigneePicker`, `ProcessPicker`) | Already work as React components; only need the SDK editor prop to expose `nodeId` + a host-side data adapter — `nodeId` is already exposed | 0.5 d  |
| `EdgeEditor` for non-condition edges (label only)   | Already coverable by `FlowEdgeDefinition.editor` (G1) | 0      |
| BPMN-specific palette categories (`events`/`tasks`/`gateways`) | `FlowNodeDefinition.category` exists; needs i18n keys + ordering hook | 0.5 d  |

### 4.3 Tier 3: **blocking / out-of-scope** for SDK without redesign

| Gap                                              | Why blocking                                                                  | Mitigation                                                                 |
|--------------------------------------------------|-------------------------------------------------------------------------------|----------------------------------------------------------------------------|
| BPMN XML import/export                           | `services/bpmnService.ts:1-650` round-trips Flowable BPMN 2.0 XML. SDK has no XML notion. | Keep `bpmnService` outside the SDK as a domain service that adapts to/from `FlowData`. No SDK change. |
| `useBPMNStore.ts` (707 LOC)                      | Diverges from `useFlowStore` (history, branch validation, sub-process drill-down). | Migrate state piecemeal to `useFlowStore` + ship BPMN-only slices as zustand middleware/selectors. |
| `ConditionExpressionEditor.tsx` (493 LOC)        | Complex structured editor (3 tabs: visual/script/rule). Already a self-contained component. | Drop into G1 `FlowEdgeDefinition.editor` unchanged. **Not blocking; reclassify to Tier 1.** |

## 5. T4 migration effort estimate

Assumes 1 senior FE engineer, no parallel workstreams, includes unit tests
(red line #1) but excludes new E2E (covered by T5).

| Workstream                                                            | Effort   |
|-----------------------------------------------------------------------|----------|
| Port 9 node renderers behind `FlowNodeDefinition` (geometry + handles) | 4 d      |
| Port 1 edge renderer + register `bpmConditional`                       | 0.5 d    |
| Port 14 editor files behind G2/G1                                      | 5 d      |
| SDK extensions per §4.2 (monitor overlay slot + sibling-edge prop + palette categories) | 4 d      |
| Migrate `useBPMNStore` consumers onto `useFlowStore` + BPMN slice      | 6 d      |
| Adapt `bpmnService` to `FlowData` (XML ↔ SDK shape)                    | 3 d      |
| Backfill unit tests to existing bpmn-designer coverage parity          | 4 d      |
| Decommission `bpmn-designer/` directory + grep cleanup                 | 1 d      |
| Slack / risk buffer (20%)                                              | 5.5 d    |
| **Total**                                                              | **33 d** (~6.5 calendar weeks) |

## 6. Recommended migration batch order

1. **Batch A (1.5 wk)** — SDK extensions only (Tier 2). Land on main behind
   tests; no consumer change. Unblocks every later batch.
2. **Batch B (1 wk)** — Port all 9 nodes + the 1 edge into a sibling
   `bpmn-designer/sdk/` package, registered through the SDK but **not yet
   wired into the live designer**. Pure addition, fully unit-tested.
3. **Batch C (1.5 wk)** — Port editors 1-by-1 in dependency order:
   parallel/exclusive/inclusive gateway → start/end → receive/service/user
   task → callActivity → ConditionExpressionEditor → AssigneePicker /
   ProcessPicker. Each editor lands behind a feature flag.
4. **Batch D (1.5 wk)** — Cut the live `BPMNDesigner` page over to the SDK
   `FlowDesigner` with the new registrations; run side-by-side E2E (T5).
5. **Batch E (1 wk)** — Delete `bpmn-designer/components/`, `store/`,
   `constants/` legacy assets after a clean release. Keep `bpmnService.ts`
   and `useNodeMonitorStatus` as standalone modules.

## 7. Top-3 gaps to call out

1. **`useNodeMonitorStatus` runtime overlay seam** — every BPMN node today
   calls it; the SDK currently has no analogous slot. Without an SDK
   extension, monitor mode silently breaks during migration. (Tier 2; ~2 d.)
2. **Editor needs sibling-edge context** — `ExclusiveGatewayEditor` and
   `InclusiveGatewayEditor` populate the default-flow dropdown from outgoing
   edges. Today `NodePropertyEditorProps` only sees `node.data.config`. Needs
   a `useNodeNeighbors(nodeId)` SDK hook or an additional prop. (Tier 2;
   ~1 d.)
3. **`useBPMNStore` vs `useFlowStore` divergence** — 707 LOC including
   sub-process drill-down stack and BPMN-only history snapshots. This is
   the single largest migration item (~6 d) and should not be underestimated.

## 8. PoC code artefacts

- `web-admin/app/plugins/core-designer/components/bpm-smoke/README.md`
- `web-admin/app/plugins/core-designer/components/bpm-smoke/nodes/BpmNodes.tsx`
- `web-admin/app/plugins/core-designer/components/bpm-smoke/edges/BpmConditionalEdge.tsx`
- `web-admin/app/plugins/core-designer/components/bpm-smoke/editors/BpmEditors.tsx`
- `web-admin/app/plugins/core-designer/components/bpm-smoke/registerBpmSmoke.ts`
- `web-admin/app/plugins/core-designer/components/bpm-smoke/__tests__/bpmSmoke.test.tsx`
  (7 vitest cases, all green)

## 9. Conclusion

T4 (full bpmn-designer → flow-designer-sdk migration) is **feasible without
SDK redesign**. G1 and G2 already cover the dominant BPMN concerns
(condition expression + default flow + bespoke editors). Two small Tier-2
SDK extensions (monitor overlay slot, sibling-edge prop) unblock the
remaining BPMN-specific affordances. Estimated total effort: **~33
engineer-days (≈6.5 weeks)**, recommended in 5 batches with feature-flag
rollout in batch D.
