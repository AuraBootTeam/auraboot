---
type: backlog
status: closed
created: 2026-05-28
---

<!-- no-precipitation: completed BPMN-node migration-batch record (PR #323) -->

# B2b batch2 — T4 BPMN→SDK port report (4 nodes + 5 editors)

**Worktree**: `/Users/ghj/work/auraboot/wt-sdk-b2b-t4-batch2`
**Branch**: `wt/sdk-b2b-t4-batch2` (off `wt/sdk-b2b-t4-batch1` + merged `wt/sdk-addedge-id`)
**Author**: subagent (implementer)
**Date**: 2026-05-28

---

## 1. Phase-0 inventory & decision

### Phase-0 grep / read

* `web-admin/app/plugins/core-designer/components/bpmn-designer/components/nodes/` — 9 node renderers,
  4 already ported in batch1. Remaining 5: ExclusiveGateway (60 LOC), InclusiveGateway (60),
  ReceiveTask (54), UserTask (116), CallActivity (55).
* `web-admin/app/plugins/core-designer/components/bpmn-designer/components/property-editors/` — 14 files:
  3 already ported (Start/End via EventEditor, ParallelGateway, ServiceTask). Remaining covers
  Exclusive/Inclusive/Receive/UserTask editors plus AssigneePicker, CallActivityEditor,
  ProcessPicker, ProcessMetadataPanel, EdgeEditor, ConditionExpressionEditor, shared.tsx.
* `flow-designer-sdk/hooks/useNodeNeighbors.ts:36` confirms G7 API shape (`{incoming, outgoing,
  sourceNodes, targetNodes}`) — directly usable by gateway editors.
* `flow-designer-sdk/nodes/types.ts:28` confirms G2 `NodePropertyEditorProps` = `{nodeId, config,
  onChange(patch), readOnly?}`.
* `bpm-designer-sdk/nodes/BpmSdkNodes.tsx` + `editors/BpmSdkEditors.tsx` show batch1's shared
  helpers (`ringClassesForStatus`, `CompletedBadge`, `BottomLabel`) and the established G2 patch
  contract.

### Scope decision — what's IN batch2

* **Nodes (4)**: `exclusiveGateway`, `inclusiveGateway`, `receiveTask`, `userTask` (static-only;
  see §4 for the dropped store-coupled branch).
* **Editors (5)**: `ExclusiveGatewayEditor`, `InclusiveGatewayEditor`, `ReceiveTaskEditor`,
  `UserTaskEditor` (simple subset), `ConditionExpressionEditor` (493 LOC drop-in).

### What's OUT (deferred)

| Item | Why deferred | Target |
|------|--------------|--------|
| `CallActivityNode` | Drives `ProcessPicker` remote loader; touches sub-process drill-down | B2c |
| `AssigneePicker` (267 LOC) | Remote data (roles/users/depts) + multi-tab UX | batch3 |
| `ProcessPicker` (99 LOC) | Remote process-key picker | batch3 |
| `CallActivityEditor` (206 LOC) | Depends on ProcessPicker | batch3 |
| `ProcessMetadataPanel` | Process-level not node-level | B2c (different host) |
| `EdgeEditor` (56 LOC) | Edge G2 contract still being defined (EdgePropertyEditorProps) | B2c |
| `shared.tsx` (627 LOC: MultiInstanceSection/FormBindingSection/HookConfigSection) | Used by UserTask + ServiceTask full editors; bulky; not needed by any batch2 surface | batch3 alongside picker |

This keeps the PR at 5 files (under the 14-file cap) and avoids touching the sub-process
drill-down or remote-data picker classes per the brief.

---

## 2. Files changed

| File | Op | LOC |
|------|----|-----|
| `web-admin/app/plugins/core-designer/components/bpm-designer-sdk/nodes/BpmSdkBatch2Nodes.tsx` | new | 252 |
| `web-admin/app/plugins/core-designer/components/bpm-designer-sdk/editors/BpmSdkBatch2Editors.tsx` | new | 644 |
| `web-admin/app/plugins/core-designer/components/bpm-designer-sdk/registerBpmSdkBatch2Nodes.ts` | new | 90 |
| `web-admin/app/plugins/core-designer/components/bpm-designer-sdk/__tests__/bpmSdkBatch2.test.tsx` | new | 408 |
| `web-admin/app/plugins/core-designer/components/bpm-designer-sdk/index.ts` | edit | +22 |

**Zero touches to legacy `bpmn-designer/`** — double-write preserved.

### `wt/sdk-addedge-id` merge

`git merge wt/sdk-addedge-id --no-ff` into batch1 base produced **no conflicts**
(addedge-id only touched `flow-designer-sdk/store/useFlowStore.ts` and tests, untouched by
batch1's bpm-designer-sdk additions). Confirmed via `git status` after merge: clean tree.

---

## 3. Port LOC + helper reuse

| Source editor | Source LOC | SDK port LOC | Notes |
|---------------|-----------|--------------|-------|
| ExclusiveGatewayEditor | 100 | 94 | -6: removed `handleChange` `name` boilerplate (G2 patch contract) |
| InclusiveGatewayEditor | 99 | 86 | -13: same handleChange removal + shared GAP-252 textarea |
| ReceiveTaskEditor | 75 | 60 | -15: G2 patch contract + outer wrapper |
| UserTaskEditor (simple subset) | 278 → ~95 of source | 138 | subset: descr/dueDate/priority/skipable/assignee.type+target; defers multiInstance/forms/hooks/aura |
| ConditionExpressionEditor | 493 | 466 (body) | byte-equivalent UI; +G2 adapter wrapper exposes via `config.condition` |

| Source node | Source LOC | SDK port LOC | Notes |
|-------------|-----------|--------------|-------|
| ExclusiveGatewayNode | 60 | 39 | helpers inlined; same geometry |
| InclusiveGatewayNode | 60 | 39 | same |
| ReceiveTaskNode | 54 | 38 | same |
| UserTaskNode | 116 | 96 | -20: dropped `useBPMNStore.instanceStatus` live lookup (see §4) |

**Shared helper reuse**: `ringClassesForStatus` / `CompletedBadge` / `BottomLabel` are duplicated
byte-identically from `BpmSdkNodes.tsx` (intentionally; see file header). Extracting to
`./shared/badges.tsx` is a 3-line follow-up that can land alongside the shared.tsx port without
touching consumers — kept duplicated this batch to avoid 6-file PR for a 2-line helper extract.

---

## 4. G7 useNodeNeighbors — real call sites

ExclusiveGatewayEditor (`BpmSdkBatch2Editors.tsx:46-58`):

```tsx
const neighbors = useNodeNeighbors(nodeId);
const outgoingEdges = useMemo(
  () =>
    neighbors.outgoing.map((e) => ({
      id: e.id,
      label: (e.data as any)?.label as string | undefined,
      condition: ((e.data as any)?.condition as ConditionExpression | undefined)?.content,
    })),
  [neighbors.outgoing],
);
```

InclusiveGatewayEditor (`BpmSdkBatch2Editors.tsx:155-165`):

```tsx
const neighbors = useNodeNeighbors(nodeId);
const outgoingEdges = useMemo(
  () => neighbors.outgoing.map((e) => ({ id: e.id, label: (e.data as any)?.label })),
  [neighbors.outgoing],
);
```

This eliminates the legacy prop-drilling pattern (`outgoingEdges` prop walked by the BPMN store
in the page host) and instead derives live from the SDK store — so adding/removing an edge
auto-refreshes the default-flow dropdown without any host plumbing. Test 10 + 11 both seed
`useFlowStore.importData()` with sibling edges and confirm the dropdown populates correctly.

---

## 5. JSON compatibility verification

* `ExclusiveGatewayConfig` / `InclusiveGatewayConfig` / `ReceiveTaskConfig` / `UserTaskConfig`
  are imported directly from `bpmn-designer/types` — no shape divergence possible.
* Test 20 builds an **8-node hybrid graph** (4 batch1 nodes + 4 batch2 nodes + 2 edges with
  `condition.content` payload), JSON-serializes, deserializes, asserts equality, then asserts
  every node type resolves through `(batch1 ∪ batch2)` registry. Pass.
* `ConditionExpression` shape preserved through `ConditionExpressionBody`: `{type, content,
  language?, ruleCode?}`. `ruleCode` is threaded through every `onChange` via `ruleCodeRef` so
  pre-existing rule references survive edits.
* GAP-252 disabled textareas (`InclusiveGateway.completionCondition`,
  `ReceiveTask.messageRef/messageType`) round-trip verbatim into the same field locations.

---

## 6. Test results

Command: `node_modules/.bin/vitest run app/plugins/core-designer/components/bpm-designer-sdk/__tests__/`

* batch1: **14/14 pass** (no regression)
* batch2: **21/21 pass**
* Combined: **35/35 pass** in 2.57s

Type-check: `tsc --noEmit` reports **0 errors in bpm-designer-sdk/** (1 pre-existing error in
`flow-designer-sdk/__tests__/NodeRuntimeStatus.test.tsx:47` predates this batch, untouched).

---

## 7. UserTaskNode — store-coupled branch deliberately dropped

Legacy `UserTaskNode.tsx:21-23` reads `useBPMNStore.instanceStatus` to look up the LIVE assignee
currently servicing the task in monitor mode. That selector cannot port until B2c migrates
`useBPMNStore` onto the SDK store. The batch2 port:

* keeps every **static** `data.config.assignee` rendering branch verbatim (role/user/dept/starter/expression)
* keeps the multi-instance indicator with `assigneeMode` label (Countersign/Sequential)
* **drops** the `activeEntry?.assignee` override (monitor-time live assignee)

Visual parity holds for any node not currently active in monitor mode (the vast majority of
states). When a task IS active in monitor mode, the SDK port falls back to the static config
assignee instead of the live runtime assignee — explicitly called out for B2c rewire.

---

## 8. `shared.tsx` (627 LOC) — handling decision + B2c interface suggestion

`shared.tsx` exports three big sections — all unused by batch2 surfaces:

| Export | LOC | Consumers | Should it port wholesale? |
|--------|-----|-----------|---------------------------|
| `MultiInstanceSection` | ~135 | UserTaskEditor (full), ServiceTaskEditor (full) | **Split** into `shared/sections/MultiInstanceSection.tsx` — pure controlled component, no store / no remote data |
| `FormBindingSection` | ~300 | UserTaskEditor (full) | **Split** — touches form picker (remote), so will need its own G2-like FormPicker contract carve-out |
| `HookConfigSection` | ~180 | ServiceTaskEditor (full), UserTaskEditor (full) | **Split** — pure controlled list editor, port as-is |

**Suggested B2c interface contract**:

1. Each section becomes its own file: `bpm-designer-sdk/shared/sections/{MultiInstance,FormBinding,HookConfig}.tsx`.
2. Each takes the G2 patch contract directly: `{config: Pick<UserTaskConfig,'multiInstance'|...>; onChange(patch)}` — no parent merge wrapper.
3. The full UserTask/ServiceTask editors compose them: `<MultiInstanceSection .../>` `<FormBindingSection .../>` `<HookConfigSection .../>`.
4. **Do not** port `shared.tsx` as a single file — losing the 627-LOC blob into one SDK file
   would re-introduce the editor god-object pattern we're trying to break. The cost of three
   small files is justified by editor-side clarity.

---

## 9. Remaining T4 batches — re-estimation

Original A3 estimate: 25.5d. Batch1+2 together delivered 8 nodes / 9 editors. Remaining work:

| Slice | Scope | Original est | Revised est | Why |
|-------|-------|--------------|-------------|-----|
| batch3 — pickers + shared sections | AssigneePicker (267) + ProcessPicker (99) + CallActivityEditor (206) + shared.tsx 3 sections split (627) + CallActivityNode (55) | ~7d | **~5d** | shared.tsx is mechanical split; AssigneePicker is the only real new logic (remote data + tabs); CallActivityNode is drop-in like batch2 nodes |
| B2c — store migration | useBPMNStore → useFlowStore (incl. instanceStatus / currentNodes for UserTaskNode live assignee + EdgeEditor + edge propertyEditor contract + sub-process drill-down) | ~8d | **~6d** | G7 already exists & is the model; edge G2 contract is the only new SDK surface; sub-process drill-down is well-scoped |
| B2d — page cutover | Replace bpmn-designer host with SDK FlowDesigner; delete legacy nodes/editors | ~3d | **~3d** | unchanged |

**Revised total remaining**: ~14d (was ~17d). Batch2 itself was ~1d wall-clock as a single
implementer slice.

---

## 10. Hand-off notes

* Worktree NOT staged, NOT pushed, NO PR. Subagent boundary respected (red-line §11 / §1).
* `web-admin/node_modules` was a transient symlink to batch1's `node_modules` for local vitest;
  removed before reporting (`git status` clean: 5 files only).
* No backend / docker / Playwright touched.
* Caller decides whether to ship batch2 as its own PR or fold it into batch1's PR.
