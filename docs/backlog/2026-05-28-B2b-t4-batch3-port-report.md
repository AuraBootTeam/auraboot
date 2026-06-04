---
type: backlog
status: active
created: 2026-05-28
---

# T4 B2b batch3 — pickers + shared.tsx + CallActivity + edge editor port report

**Date**: 2026-05-28
**Worktree**: `wt-sdk-b2b-t4-batch3` (branch `wt/sdk-b2b-t4-batch3`)
**Base**: `wt/sdk-b2b-t4-batch2` (5f1b463f1)
**Scope**: T4 layer 1 (nodes) + layer 2 (editors) final batch — 1 node + 1 G2 editor + 1 G1 edge editor + 3 shared.tsx sections + 2 remote-data pickers.

---

## 1. Stage 0 — accounting (red-line #16)

Before touching SDK code we grepped each artefact in scope:

| Artefact | LOC | Dependencies (grep-verified) | Decision |
| --- | --- | --- | --- |
| `shared.tsx` (3 sections) | 627 | `useI18n`, `core-bpm/PagePickerSelect`, `core-bpm/VariableMappingEditor`, `core-bpm/FieldPermissionMatrix`, `bpmn-designer/types`(MultiInstanceConfig/FormBindingEntry/NodeHookEntry) | Split into 3 SDK files per B2b2 §8 — each one a single-purpose controlled component (NOT a G2 editor; hosting editor wires patch). Reuse the 3 core-bpm helpers directly (mature, app-scoped — re-porting would just duplicate). |
| `AssigneePicker.tsx` | 267 | `useI18n`, real `POST /tenant/members/search`, real `GET /roles/all`, real `GET /org/teams`, `ResultHelper`, `lucide-react` | Port byte-equivalent into `editors/pickers/AssigneePicker.tsx`. Endpoints **verified real** (grep on legacy file) — no mocking. |
| `ProcessPicker.tsx` | 100 | `useI18n`, real `GET /api/bpm/process-definitions/deployed`, `ResultHelper` | Port byte-equivalent into `editors/pickers/ProcessPicker.tsx`. Endpoint **verified real**. |
| `CallActivityEditor.tsx` | 207 | `CallActivityConfig`, `useI18n`, `ProcessPicker` | Port as G2 `NodePropertyEditorProps` adapter in `BpmSdkBatch3Editors.tsx`. Inline `VariableMappingTable` helper kept private to the file. |
| `EdgeEditor.tsx` | 56 | `useI18n`, `BPMNEdgeData`, `ConditionExpression`, `ConditionExpressionEditor` | Port as G1 **edge** editor (`EdgePropertyEditorProps`, NOT G2) → renamed to `BpmSequenceFlowEdgeEditor` to make the contract explicit. Reuses `ConditionExpressionBody` ported in batch2. |
| `CallActivityNode.tsx` | 56 | `BPMN_NODE_STYLES`, `BPMNNodeType`, `useNodeMonitorStatus`(SDK G8) | Port into `nodes/BpmSdkBatch3Nodes.tsx`, drop-in. |

No endpoint was found to be fake — all 4 picker/editor remote calls already pointed at real auraboot APIs. We did **not** create any backlog for "endpoint missing" because the grep audit found none.

### Decision validation vs B2b2 §8

B2b2 §8 recommended splitting `shared.tsx` into 3 SDK files exactly along these lines. This batch ships that split with no change in recommendation. The B2b2 §8 deferral of `callActivity` ("blocked on ProcessPicker") is also resolved here.

---

## 2. Files changed (worktree-local diff)

**Added** (10 files, ~1450 LOC):

| File | LOC | Purpose |
| --- | --- | --- |
| `web-admin/app/plugins/core-designer/components/bpm-designer-sdk/nodes/BpmSdkBatch3Nodes.tsx` | 89 | CallActivityNode renderer |
| `…/bpm-designer-sdk/editors/BpmSdkBatch3Editors.tsx` | 247 | CallActivityEditor (G2) + BpmSequenceFlowEdgeEditor (G1 edge) + VariableMappingTable helper |
| `…/bpm-designer-sdk/editors/sections/MultiInstanceSection.tsx` | 166 | shared.tsx split — 5-field multi-instance config |
| `…/bpm-designer-sdk/editors/sections/FormBindingSection.tsx` | 196 | shared.tsx split — page-picker + save/version strategy + var-mapping + permissions |
| `…/bpm-designer-sdk/editors/sections/HookConfigSection.tsx` | 312 | shared.tsx split — pre/post hooks with http/script/command sub-configs |
| `…/bpm-designer-sdk/editors/pickers/AssigneePicker.tsx` | 287 | Remote-data user/role/dept picker — real POST/GET endpoints |
| `…/bpm-designer-sdk/editors/pickers/ProcessPicker.tsx` | 105 | Remote-data deployed-process picker — real GET endpoint |
| `…/bpm-designer-sdk/registerBpmSdkBatch3Nodes.ts` | 65 | Registration + `registerBpmSdkAll` aggregate (batch1+2+3 = 9 types) |
| `…/bpm-designer-sdk/__tests__/bpmSdkBatch3.test.tsx` | 478 | 30 test cases |
| (modified) `…/bpm-designer-sdk/index.ts` | +37 | Export new symbols |

**Total**: 9 new source files + 1 modified index. **No deletions** (double-write per task spec).

---

## 3. Section interface signatures (for B2c store-migration consumers)

The 3 sections are pure controlled components; B2c should host them inside `UserTaskEditor` / `CallActivityEditor` / future composite editors by translating G2 `NodePropertyEditorProps` `(config, onChange)` into the section's typed prop pair:

```ts
// MultiInstanceSection
interface MultiInstanceSectionProps {
  config?: MultiInstanceConfig;
  onChange: (config: MultiInstanceConfig) => void;
}

// FormBindingSection
interface FormBindingSectionProps {
  bindings: FormBindingEntry[];                // V1 operates on bindings[0]
  onChange: (bindings: FormBindingEntry[]) => void;
}

// HookConfigSection
interface HookConfigSectionProps {
  hooks: NodeHookEntry[];
  onChange: (hooks: NodeHookEntry[]) => void;
}
```

**Recommended host wiring pattern** (drop into UserTaskEditor in B2c):

```ts
export function UserTaskEditor({ config, onChange }: NodePropertyEditorProps) {
  const c = (config ?? {}) as UserTaskConfig;
  return (
    <>
      {/* …existing batch2 fields… */}
      <MultiInstanceSection
        config={c.multiInstance}
        onChange={(mi) => onChange({ multiInstance: mi })}
      />
      <FormBindingSection
        bindings={c.formBindings ?? []}
        onChange={(b) => onChange({ formBindings: b })}
      />
      <HookConfigSection
        hooks={c.hooks ?? []}
        onChange={(h) => onChange({ hooks: h })}
      />
    </>
  );
}
```

The 3 sections preserve every `data-testid` and i18n key from `shared.tsx`, so existing E2E selectors continue to match once B2c does the cutover.

---

## 4. Picker remote-API status

| Picker | Endpoint | Verb | Real? | Status |
| --- | --- | --- | --- | --- |
| AssigneePicker (user) | `/tenant/members/search` | POST | ✅ verified | Connected verbatim |
| AssigneePicker (role) | `/roles/all` | GET | ✅ verified | Connected verbatim |
| AssigneePicker (dept) | `/org/teams` | GET | ✅ verified | Connected verbatim |
| ProcessPicker | `/api/bpm/process-definitions/deployed` | GET | ✅ verified | Connected verbatim |

**No picker is mocked / no endpoint is fake** — all 4 calls were already real in the legacy file. Tests mock `~/shared/services/http-client` at the SDK boundary so unit tests run without the platform; the SDK itself ships unaltered HTTP calls.

`AssigneePicker` exposes `__assigneeInternals = { fetchUsers, fetchRoles, fetchTeams, FETCHERS }` for downstream tests that need to stub a single fetcher.

---

## 5. CallActivity port details

- Node renderer (`nodes/BpmSdkBatch3Nodes.tsx`) — drop-in port; same geometry / handles / colours / double-border `box-shadow` as legacy, plus G8 monitor-aware ring + `bpm-sdk-completed-badge`.
- Editor (`editors/BpmSdkBatch3Editors.tsx` `CallActivityEditor`) — converts the legacy "full config onChange" contract into G2 patch contract. The inline `VariableMappingTable` helper (input + output) is kept private to the file — it has no plausible reuse outside this editor.
- Registration (`registerBpmSdkBatch3Nodes.ts`) — single-item registry; also ships `registerBpmSdkAll` that aggregates batch1+2+3 into the 9-type SDK BPMN registration call B2d will use.
- JSON shape unchanged: `CallActivityConfig` round-trips through the SDK store identically to the legacy renderer (verified in test 28).

---

## 6. Test results

```
Test Files  3 passed (3)
Tests      65 passed (65)
Duration   ~1.8 s
```

Breakdown:
- batch1 14/14 (no regression)
- batch2 21/21 (no regression)
- **batch3 30/30** (new)

Batch3 coverage matrix:

| Slice | Cases | Notes |
| --- | --- | --- |
| Registration | 4 | registerBpmSdkBatch3 + registerBpmSdkAll (verifies 9-type aggregate) |
| CallActivityNode rendering | 3 | label / processKey subtitle / monitor-completed badge |
| CallActivityEditor (G2) | 3 | description / version mode / ProcessPicker selection |
| MultiInstanceSection | 3 | collapsed / expanded 5 fields / sequential radio |
| FormBindingSection | 3 | collapsed / expanded (no formRef) / clear-formRef → onChange([]) |
| HookConfigSection | 3 | header (0) / addHook / action-type swap |
| AssigneePicker | 3 | loading / select toggles value[] / empty state |
| ProcessPicker | 3 | loading / loaded+search / fetch error |
| BpmSequenceFlowEdgeEditor | 3 | label / condition body + default checkbox / multi-patch round-trip |
| Cross-batch JSON round-trip | 1 | 9-node hybrid (batch1+2+3) loss-free import/export |

Each section / picker / editor has ≥3 cases — meets the red-line #1 hard-rule for completeness.

`./node_modules/.bin/vitest run app/plugins/core-designer/components/bpm-designer-sdk/__tests__/` is reproducible from the worktree.

---

## 7. T4 layer 1+2 port completeness (after this batch)

**Nodes (9/9 ported)**:

| BPMNNodeType | Batch | SDK file |
| --- | --- | --- |
| startEvent / endEvent / parallelGateway / serviceTask | 1 | `nodes/BpmSdkNodes.tsx` |
| exclusiveGateway / inclusiveGateway / receiveTask / userTask | 2 | `nodes/BpmSdkBatch2Nodes.tsx` |
| **callActivity** | **3** | `nodes/BpmSdkBatch3Nodes.tsx` |

**Editors (14/14 ported)**:

| Editor | Batch | SDK file |
| --- | --- | --- |
| StartEventEditor / EndEventEditor / ParallelGatewayEditor / ServiceTaskEditor | 1 | `editors/BpmSdkEditors.tsx` |
| ExclusiveGatewayEditor / InclusiveGatewayEditor / ReceiveTaskEditor / UserTaskEditor / ConditionExpressionEditor(+Body) | 2 | `editors/BpmSdkBatch2Editors.tsx` |
| **CallActivityEditor / BpmSequenceFlowEdgeEditor** | **3** | `editors/BpmSdkBatch3Editors.tsx` |
| **MultiInstanceSection / FormBindingSection / HookConfigSection** | **3** | `editors/sections/*.tsx` |

**Pickers (2/2 ported)**:

| Picker | Batch | SDK file |
| --- | --- | --- |
| **AssigneePicker / ProcessPicker** | **3** | `editors/pickers/*.tsx` |

**Layer 1 + layer 2 of T4 are complete.** Anything left in `bpmn-designer/components/property-editors/` (e.g. `ProcessMetadataPanel`, `EventEditor`, `index.ts` glue) is either non-node-property-editor (panel-level UX) or already covered above.

---

## 8. B2c (store migration) recommendations

The remaining T4 work is **store-level**, not node-level:

1. **`useBPMNStore` → `useFlowStore` migration**
   - `BPMNCanvas.tsx` / `BPMNPropertyPanel.tsx` / `BPMNToolbar.tsx` still talk to the BPMN-specific store.
   - The SDK `useFlowStore` already exposes the same `nodes / edges / monitorData / setMonitorMode / importData / exportData` surface — confirmed by batch3 test 6 (monitor) + test 28 (9-node round-trip).
   - Strategy: write a thin `useBPMNStoreAdapter` that proxies to `useFlowStore`, drop the legacy file once consumers compile-clean.

2. **UserTaskEditor full re-wire**
   - Today batch2 ships a "simple" UserTaskEditor with an inert free-text assignee target + TODO. B2c should swap that field for `AssigneePicker` and mount `MultiInstanceSection` + `FormBindingSection` + `HookConfigSection`. The wiring example in §3 above is the canonical pattern.

3. **Live-assignee monitor-mode subtitle**
   - `UserTaskNode` (batch2) explicitly dropped the `useBPMNStore.instanceStatus` lookup that renders the currently-active assignee in monitor mode. Wire it via `useFlowStore.getMonitorEntry(nodeId)?.activeAssignee` once B2c lands.

4. **EdgePropertyEditor registration**
   - `BpmSequenceFlowEdgeEditor` is unregistered as of this batch. B2c should register it on a `bpmSequenceFlow` edge type via `edgeRegistry.register({ type: 'bpmSequenceFlow', editor: BpmSequenceFlowEdgeEditor })` (or attach in `registerBpmSdkAll`).

5. **shared/badges extraction follow-up (small)**
   - `CompletedBadge` / `ringClassesForStatus` are duplicated across `BpmSdkNodes.tsx`, `BpmSdkBatch2Nodes.tsx`, and `BpmSdkBatch3Nodes.tsx`. Extract to `nodes/shared/badges.tsx` in a 1-file cleanup PR alongside B2c.

6. **Legacy `bpmn-designer/` deletion timing**
   - **Keep until B2d cutover** — `BPMNDesigner.tsx` page entry still imports from `bpmn-designer/`, and several non-batch3 surfaces (palette item metadata, `ProcessStatusViewer`, monitor panel, save dialog) live there too.
   - Delete the moment B2d page cutover is merged and Playwright golden runs green on the SDK path. Recommended PR boundary: **B2d ships SDK page → 1 follow-up commit deletes `bpmn-designer/components/{nodes,property-editors,store}/`** (keeps `types/` + `constants/` since the SDK port re-imports from them; those should migrate to `bpm-designer-sdk/types/` and `bpm-designer-sdk/constants/` in a B2e tidy).

---

## 9. B2d (page cutover) draft steps

Goal: switch `BPMNDesigner.tsx` to mount the SDK `FlowDesigner` instead of the legacy `<ReactFlow>` wrapping. Suggested ordering:

1. **B2d.1 — Edge registration**
   - Add `bpmSequenceFlow` to `edgeRegistry` with `BpmSequenceFlowEdgeEditor`.
   - Add `bpmSequenceFlow` to the SDK default `edgeTypes` map (or pass via `<FlowDesigner edgeTypes={...} />`).
2. **B2d.2 — Mount-side cutover**
   - Replace the body of `BPMNDesigner.tsx` with `<FlowDesigner>` + `registerBpmSdkAll()` on first render.
   - Translate the BPMNDesigner props (`processKey` / `instanceId` / `readOnly` / monitor data) into the SDK store shape (`importData` for initial JSON, `setMonitorData` for live updates).
   - Keep `BPMNCanvas / BPMNToolbar / BPMNPropertyPanel` aliased to the SDK equivalents until visual diff is zero.
3. **B2d.3 — Palette + category labels**
   - Port `BPMN_PALETTE_ITEMS` into a SDK `paletteSource` registered alongside `registerBpmSdkAll`. SDK already supports `palette` slot.
4. **B2d.4 — E2E gating** — run `web-admin/tests/bpmn-designer/*.spec.ts` Playwright slice; only after green, delete the legacy `bpmn-designer/{components,store,hooks}/` per §8 #6.
5. **B2d.5 — Final tidy** — migrate `bpmn-designer/types` + `bpmn-designer/constants` into `bpm-designer-sdk/`, drop the legacy folder entirely.

Each step is independently shippable; B2d.1 + B2d.2 unlock the visual cutover; B2d.3–.5 are tidy.

---

## 10. Out of scope / not done (explicit)

- ❌ `useBPMNStore` migration — B2c.
- ❌ `BPMNDesigner.tsx` page cutover — B2d.
- ❌ Deletion of legacy `bpmn-designer/components/{nodes,property-editors}/` — gated on B2d golden green.
- ❌ Real browser screenshots — `bpm-designer-sdk` isn't mounted on a page yet (still double-write); visual proof comes in B2d.
- ❌ Backend / docker / E2E — not in task scope; not run.

---

## 11. Commit decision (for parent agent)

Subagent has **NOT** committed or pushed. Per task prompt the parent agent will decide commit ownership. The two prior batches diverged:
- **B2b1** (subagent): did not commit.
- **B2b2** (subagent): parent committed.

Recommend the parent commit B2b3 too (same shape: 10 new files, lockfile-safe, clean test green, ready for `git add` + a single commit per the AGENTS.md commit ritual). The worktree currently has only untracked additions + the index.ts edit — no auto-stage occurred.

---

## 12. Verification commands (reproducible)

```bash
cd /Users/ghj/work/auraboot/wt-sdk-b2b-t4-batch3
git status -s                                                     # 10 untracked + 1 modified
./web-admin/node_modules/.bin/vitest run \
  web-admin/app/plugins/core-designer/components/bpm-designer-sdk/__tests__/
# → Test Files 3 passed (3) | Tests 65 passed (65)
```
