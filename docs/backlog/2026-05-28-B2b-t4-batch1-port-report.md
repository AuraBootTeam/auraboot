---
type: backlog
status: active
created: 2026-05-28
---

# B2b — T4 BPMN→SDK Node Port, Batch 1 (4 nodes + 4 editors)

- Date: 2026-05-28
- Worktree: `/Users/ghj/work/auraboot/wt-sdk-b2b-t4-batch1`
- Branch: `wt/sdk-b2b-t4-batch1`
- Base: `wt/sdk-g7-g8` (commit `ff21b9798`)
- Merge: `wt/sdk-g5-runtime` (commit `680ff334a`) merged in as commit
  `028e88860` — 1 conflict resolved in `FlowDesigner.tsx` (G5
  `nodeStatuses` and G8 `monitorMode`/`monitorData` props coexist; no
  functionality dropped).

## 1. Phase 0 — decision matrix: which 4 nodes?

Before writing any port code we re-read A3 §4.1 (Tier 1 drop-in) +
re-grepped `bpmn-designer/components/{nodes,property-editors}/` and the
SDK G2/G7/G8 surfaces. Selection criteria for batch 1:

1. **Tier 1** in A3 — no SDK extension required.
2. **No dependency on G7** (sibling-edge access) — eliminates
   `exclusiveGateway` + `inclusiveGateway`, whose editors today read
   outgoing edges for the default-flow dropdown.
3. **No dependency on remote pickers** — eliminates `userTask`
   (`AssigneePicker`, 267 LOC) and `callActivity` (`ProcessPicker`, 99
   LOC) for batch 1.
4. **Smallest editor LOC first** — keeps the batch under one session and
   maximises learning per surface area.
5. **Structural-class coverage** — cover events + gateway + task in one
   batch so the batch validates the full BPMN shape, not just leaves.

Final 4 nodes (file:line evidence in legacy `bpmn-designer/`):

| # | Node              | Legacy renderer LOC | Legacy editor          | Editor LOC | Why batch 1 |
|---|-------------------|---------------------|------------------------|------------|-------------|
| 1 | `startEvent`      | `StartEventNode.tsx:1` — 52  | `EventEditor.tsx#StartEventEditor` | 54  | smallest; structural class = event |
| 2 | `endEvent`        | `EndEventNode.tsx:1` — 52    | `EventEditor.tsx#EndEventEditor`   | 38  | mirror of startEvent; same shape |
| 3 | `parallelGateway` | `ParallelGatewayNode.tsx:1` — 60 | `ParallelGatewayEditor.tsx:1`      | 41  | simplest gateway (no defaultFlow → no G7 need) |
| 4 | `serviceTask`     | `ServiceTaskNode.tsx:1` — 80 | `ServiceTaskEditor.tsx:1`          | 147 | structural class = task; exercises 4-branch conditional editor |

**Explicitly excluded from batch 1** (deferred + reasoning):

- `exclusiveGateway` / `inclusiveGateway` — editors read sibling outgoing
  edges (needs G7 `useNodeNeighbors`). Tier 2 batch.
- `userTask` — 116 LOC renderer + 278 LOC editor + AssigneePicker (267
  LOC) — too large for a first batch, exercises remote-data adapters.
- `callActivity` — needs ProcessPicker remote data.
- `receiveTask` — Tier 1 but no structural-class benefit beyond
  serviceTask (same task shape). Defer to next batch as easy filler.

## 2. Files added / modified

```
A web-admin/app/plugins/core-designer/components/bpm-designer-sdk/index.ts
A web-admin/app/plugins/core-designer/components/bpm-designer-sdk/nodes/BpmSdkNodes.tsx
A web-admin/app/plugins/core-designer/components/bpm-designer-sdk/editors/BpmSdkEditors.tsx
A web-admin/app/plugins/core-designer/components/bpm-designer-sdk/registerBpmSdkNodes.ts
A web-admin/app/plugins/core-designer/components/bpm-designer-sdk/__tests__/bpmSdkBatch1.test.tsx
M web-admin/app/plugins/core-designer/components/flow-designer-sdk/core/FlowDesigner.tsx  (merge conflict resolution only — adds G5 props alongside G8)
A docs/backlog/2026-05-28-B2b-t4-batch1-port-report.md
```

**Not touched** (double-write discipline):

- `bpmn-designer/components/nodes/{StartEventNode,EndEventNode,ParallelGatewayNode,ServiceTaskNode}.tsx`
  — legacy renderers kept live, still consumed by `BPMNDesigner.tsx`.
- `bpmn-designer/components/property-editors/{EventEditor,ParallelGatewayEditor,ServiceTaskEditor}.tsx`
  — legacy editors kept live.
- `bpmn-designer/{store,services,hooks}/` — all untouched. B2c migrates these.

## 3. Port-by-port code diff

### 3.1 Node renderers — line-for-line geometry preserved

| Asset                | Legacy hook                                              | SDK port hook                              | Visual diff |
|----------------------|----------------------------------------------------------|--------------------------------------------|-------------|
| Monitor overlay      | `bpmn-designer/hooks/useNodeMonitorStatus` (returns `'active'\|'completed'\|'idle'\|null`) | SDK `useNodeMonitorStatus` (returns `NodeMonitorStatus \| undefined` with wider enum) | Adapter `ringClassesForStatus()` collapses the SDK enum back to legacy ring classes — visual parity. |
| Geometry / colour    | `BPMN_NODE_STYLES[BPMNNodeType.X]`                       | **same import** — reused unchanged          | Zero — pixel-identical box / circle / diamond sizing. |
| `<Handle>` topology  | xyflow `Handle` with same `position`/`className`         | identical                                   | Zero. |
| Completed badge      | Inline SVG check icon                                    | Extracted `CompletedBadge` helper (DRY)     | Zero rendered. |
| Bottom label         | Inline conditional `<div>`                               | Extracted `BottomLabel` helper              | Zero rendered. |

LOC: 4 legacy files = 244 LOC → 1 SDK file `BpmSdkNodes.tsx` = 218 LOC
(11% smaller from shared helpers).

### 3.2 Property editors — contract migrated to G2

Contract diff:

```ts
// legacy (4 separate signatures)
function StartEventEditor({ config, onChange }: {
  config?: StartEventConfig;
  onChange: (config: StartEventConfig) => void;
})

// SDK G2 (1 uniform signature)
function StartEventEditor({ nodeId, config, onChange, readOnly }: NodePropertyEditorProps)
// where onChange now emits a PATCH (Record<string, unknown>) not the full config
```

The patch-shape onChange is more efficient (store merges via
`useFlowStore.updateNode`) and matches how the G2 PoC at
`bpm-smoke/editors/BpmEditors.tsx` shipped.

LOC: 4 legacy editors = 280 LOC → 1 SDK file `BpmSdkEditors.tsx` = 248
LOC (11% smaller, +1 added `readOnly` support throughout via SDK prop).

**Intentional gap, called out in `BpmSdkEditors.tsx` comment**:
`ServiceTaskEditor.HookConfigSection` (pre/post execution hooks UI) is
NOT ported in batch 1. It depends on `bpmn-designer/components/property-editors/shared.tsx`
(627 LOC, the largest editor support file). It will be ported in the
batch that owns `shared.tsx` (estimate: 2 d on its own — see §6).

### 3.3 SDK affordances exercised

| Affordance | Used by | Verification |
|------------|---------|--------------|
| G1 `EdgeRegistry`                            | (not in batch 1 — edge stays on bpmn-designer) | n/a |
| G2 `FlowNodeDefinition.propertyEditor`       | All 4 nodes register a bespoke editor               | test #2 |
| G2 `NodePropertyEditorProps`                 | All 4 editors                                       | tests #8–#11 |
| G4 `NodeValidation.{minInputs,maxInputs,minOutputs,maxOutputs}` | All 4 nodes declare structural rules | test #3 |
| G7 `useNodeNeighbors`                        | **not used yet** — batch 1 picked nodes that don't need sibling-edge access | n/a |
| G8 `useNodeMonitorStatus`                    | All 4 renderers consume                             | test #12 |
| G8 `<FlowDesigner monitorMode monitorData>`  | Exercised via direct store access in test #12        | test #12 |
| G5 `NodeRuntimeStatusProvider` / `nodeStatuses` | **not used yet** — kept in tree (merged from g5-runtime) for automation consumers; B2c will pick the canonical path | n/a — coexist |

## 4. JSON compatibility — round-trip verification

Test #13 builds a 4-node BPMN graph (`startEvent → parallelGateway →
serviceTask → endEvent`) whose `data.config` payloads use the legacy
`StartEventConfig` / `ServiceTaskConfig` / `EndEventConfig` field names
(verified at `bpmn-designer/types/index.ts:143-155`), serialises with
`JSON.stringify`, parses back, and asserts `toEqual(original)`. All 4
node types are then looked up in a fresh `NodeRegistry` populated by
`registerBpmSdkBatch1` to prove the registry accepts the same `type`
strings the legacy `BPMNNodeType.*` enum emits
(`bpmn-designer/types/index.ts:7-19`).

Concretely:

- `BPMNNodeType.START_EVENT === 'startEvent'` ✅ — registered as
  `'startEvent'` in `registerBpmSdkNodes.ts:54`.
- `BPMNNodeType.END_EVENT === 'endEvent'` ✅ — `registerBpmSdkNodes.ts:62`.
- `BPMNNodeType.PARALLEL_GATEWAY === 'parallelGateway'` ✅ — `registerBpmSdkNodes.ts:70`.
- `BPMNNodeType.SERVICE_TASK === 'serviceTask'` ✅ — `registerBpmSdkNodes.ts:78`.

A page saved with the legacy `BPMNDesigner` can be reopened in a future
SDK-driven `FlowDesigner` host without migration — node `type` strings
and `data.config` field names are bit-identical. (Edges still pass
through bpmn-designer for now; B2c covers edge migration.)

We did **not** plumb the A1 `validate-flow.mjs` lint script here because
A1 was scoped to the SDK's own JSON shape, not BPMN-specific configs.
The unit test asserts the structural invariant directly.

## 5. Test results

```
$ cd web-admin && pnpm exec vitest run \
    app/plugins/core-designer/components/bpm-designer-sdk/__tests__/
Test Files  1 passed (1)
     Tests  14 passed (14)
  Duration  1.03s
```

Combined with the pre-existing SDK suite:

```
$ pnpm exec vitest run \
    app/plugins/core-designer/components/flow-designer-sdk/__tests__/ \
    app/plugins/core-designer/components/bpm-designer-sdk/__tests__/
Test Files  10 passed (10)
     Tests  102 passed (102)
  Duration  801ms
```

`pnpm exec tsc --noEmit` — **zero errors in `bpm-designer-sdk/`**. There
is 1 pre-existing TS error in
`flow-designer-sdk/__tests__/NodeRuntimeStatus.test.tsx:47` that came
in via the `wt/sdk-g5-runtime` merge (FlowNodeDefinition missing `icon`
field in the test fixture); not in scope for this batch.

Coverage matrix mapped to the task's "≥12 cases, ≥3/node" minimum:

| Node             | Render | Editor patch | Validation | Status overlay | JSON round-trip |
|------------------|--------|--------------|------------|----------------|-----------------|
| startEvent       | #4     | #8           | #3         | #12            | #13             |
| endEvent         | #5     | #9           | #3         | (#12 covers shared adapter) | #13 |
| parallelGateway  | #6     | #10          | #3         | (#12 covers shared adapter) | #13 |
| serviceTask      | #7     | #11          | #3         | (#12 covers shared adapter) | #13 |
| edges (4-node DAG) | n/a  | n/a          | n/a        | n/a            | #13             |
| registry plumbing | #1, #2 | n/a         | #3         | n/a            | n/a             |
| factory stability | n/a   | n/a          | n/a        | n/a            | #14             |

14 cases / 4 nodes ≥ 3 cases/node ✅

## 6. Re-estimated effort for remaining batches

A3 §5 estimated T4 total at **33 engineer-days**. With batch 1 done we
can sharpen the numbers — batch 1 itself came in at ≈ 0.5 d (one session,
including the report). A3's "Port 9 node renderers behind
`FlowNodeDefinition` (geometry + handles)" line item was 4 d; we burned
the first 4/9 (44%) of that in 0.5 d, so the next 5 nodes should land
in ≈ 0.5 d. Editors were the bigger expense per LOC than renderers
(legacy 280 LOC editor vs. 244 LOC renderer for the 4-node subset).

| Workstream                                                              | A3 estimate | Re-estimate after batch 1 | Δ      |
|-------------------------------------------------------------------------|-------------|---------------------------|--------|
| **Batch 1** (done)                                                      | (subset)    | 0.5 d                     | n/a    |
| Batch 2: port remaining 5 nodes (`userTask`/`receiveTask`/`exclusive` + `inclusive` + `callActivity` renderers — geometry only) | 4 d (whole 9) | 0.5 d                     | −3.5 d |
| Batch 3: port `shared.tsx` (627 LOC, drives Hook + Condition shared building blocks); enables `HookConfigSection` add-back to ServiceTask | (folded into "14 editors" → 5 d) | 2 d  | reclass |
| Batch 4: port remaining 10 editors (`EventEditor` already done, `ParallelGatewayEditor` already done, `ServiceTaskEditor` already done sans hooks; remaining: `UserTaskEditor` 278 + `ReceiveTaskEditor` 75 + `CallActivityEditor` 206 + `ExclusiveGatewayEditor` 100 + `InclusiveGatewayEditor` 99 + `EdgeEditor` 56 + `ConditionExpressionEditor` 493 + `AssigneePicker` 267 + `ProcessPicker` 99 + `ProcessMetadataPanel` 140 = 1813 LOC) | (5 d included above) | 4 d | −1 d (G7+G8 already shipped) |
| SDK extensions per A3 §4.2                                              | 4 d         | **0 d** (G7+G8 landed in B2a; monitor slot done; sibling-edge prop done; only palette i18n + category ordering remains, ≈ 0.5 d) | −3.5 d |
| Edge renderer port + `bpmConditional` register                          | 0.5 d       | 0.5 d                     | 0      |
| `useBPMNStore` → `useFlowStore` migration                               | 6 d         | 6 d                       | 0      |
| Adapt `bpmnService` (XML ↔ FlowData)                                    | 3 d         | 3 d                       | 0      |
| Unit-test backfill to parity                                            | 4 d         | 3 d (batch-by-batch tests already accumulated) | −1 d |
| Decommission `bpmn-designer/`                                           | 1 d         | 1 d                       | 0      |
| Risk buffer (20%)                                                       | 5.5 d       | 4.5 d                     | −1 d   |
| **Total**                                                               | **33 d**    | **~25.5 d** (≈ 5 calendar weeks) | **−7.5 d** |

The largest line item remains `useBPMNStore → useFlowStore` (6 d, ≈24%
of total). Batch 1 did not touch it; the re-estimate of that line is
unchanged. The savings come almost entirely from B2a/A2 already landing
G7+G8 (A3 §4.2's 4 d gap-fill = done) plus the renderer-port rate being
faster than A3 assumed.

## 7. Recommendations for B2c (useBPMNStore migration)

While reading `useBPMNStore.ts` (707 LOC) at file:line scale for this
report, three SDK API gaps stand out that B2c will hit:

1. **`useBPMNStore` has a sub-process drill-down stack**
   (`bpmn-designer/store/useBPMNStore.ts` — search for "subprocess" /
   `pushSubProcess`). The SDK's `useFlowStore` has no parent stack
   concept. B2c will need either an SDK extension (`pushSubFlow` /
   `popSubFlow` stack) or zustand middleware on the host side. I
   recommend SDK-side because every BPM-shaped consumer will want it.
2. **History snapshots** — `useBPMNStore` has its own undo/redo. The SDK
   has `undo` / `redo` (per `FlowDesigner.tsx:80`) but the snapshot
   payload shape may not preserve BPMN-only fields like
   `sub-process pointer` cleanly. Worth a 30-min spike before B2c starts.
3. **`addEdge` id-collision bug noted in B2a §"Pre-existing bug"** —
   `edge_${Date.now()}` collides on rapid creation. B2c will exercise
   this hot path during initial XML→FlowData import for any BPMN with
   tightly batched edges. Fix recommended **before** B2c (one-line:
   add a counter or nanoid suffix).

## 8. Out of scope (kept as-is)

- `bpmn-designer/` is fully intact — no files deleted, no imports
  re-routed, no live consumer (e.g. `BPMNDesigner.tsx`) altered.
- Backend / docker / Playwright E2E — this batch is pure SDK; no app
  start required (per task brief).
- A1 `validate-flow.mjs` lint cherry-pick — A1 scope is SDK shapes, not
  BPMN configs; the JSON round-trip is checked inline in test #13
  instead.
