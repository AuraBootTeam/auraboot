---
type: backlog
status: closed
created: 2026-05-28
---

<!-- no-precipitation: terminal port/gap-fill report (78 tests passed); flow-designer-sdk hooks are the durable artifact -->

# B2a — flow-designer-sdk G7+G8 gap fill (2026-05-28)

Status: PR-ready (no PR opened — main worktree decides merge order with A1/A3).

Worktree: `/Users/ghj/work/auraboot/wt-sdk-g7-g8` on branch `wt/sdk-g7-g8`
(base `origin/main` = `3c6ea1e7c`).

## Scope

Two gap fills surfaced by A3 T4 BPMN→SDK feasibility report
(`wt-sdk-bpm-smoke/docs/backlog/2026-05-28-A3-T4-feasibility-report.md` §7).
Both are pure additions — no existing SDK behavior changes — so bpmn-designer
and automation-designer continue to work unchanged. T4 follow-ups (B2b node
ports, B2c store migration) can now drop-in instead of bolt-on.

## G7 — `useNodeNeighbors(nodeId)`

File: `web-admin/app/plugins/core-designer/components/flow-designer-sdk/hooks/useNodeNeighbors.ts`

API:
```ts
export interface NodeNeighbors {
  incoming: FlowEdge[];      // edges where target === nodeId
  outgoing: FlowEdge[];      // edges where source === nodeId
  sourceNodes: FlowNode[];   // resolved source nodes of incoming edges
  targetNodes: FlowNode[];   // resolved target nodes of outgoing edges
}

export function useNodeNeighbors(
  nodeId: string | null | undefined,
): NodeNeighbors;
```

Reactive: subscribes to `useFlowStore`'s `nodes` and `edges`, re-derives via
`useMemo`. Returns a frozen empty value when `nodeId` is null/undefined.

### B2b drop-in example — ExclusiveGatewayEditor default-flow dropdown

```tsx
import { useNodeNeighbors } from '~/plugins/core-designer/components/flow-designer-sdk';

function ExclusiveGatewayEditor({ node, onChange }: NodePropertyEditorProps) {
  const { outgoing } = useNodeNeighbors(node.id);
  return (
    <select
      value={(node.data.config.defaultFlowEdgeId as string) ?? ''}
      onChange={(e) => onChange({ defaultFlowEdgeId: e.target.value })}
    >
      <option value="">(no default flow)</option>
      {outgoing.map((edge) => (
        <option key={edge.id} value={edge.id}>
          {edge.data?.label ?? edge.id} → {edge.target}
        </option>
      ))}
    </select>
  );
}
```

This replaces the bpmn-designer's current direct `useBPMNStore` read of edges.

## G8 — `useNodeMonitorStatus(nodeId)` + `<FlowDesigner monitorMode monitorData>` prop pair

Files:
- `web-admin/app/plugins/core-designer/components/flow-designer-sdk/store/monitorTypes.ts` (new)
- `web-admin/app/plugins/core-designer/components/flow-designer-sdk/hooks/useNodeMonitorStatus.ts` (new)
- `web-admin/app/plugins/core-designer/components/flow-designer-sdk/store/useFlowStore.ts` (added `monitorMode` / `monitorData` / setters; cleared on `reset()`)
- `web-admin/app/plugins/core-designer/components/flow-designer-sdk/core/FlowDesigner.tsx` (added `monitorMode?: boolean` and `monitorData?: FlowMonitorData` props that sync into the store on every change)

API:
```ts
export type FlowMonitorStatus =
  | 'pending' | 'running' | 'completed' | 'failed' | 'skipped' | 'idle';

export interface NodeMonitorStatus {
  status: FlowMonitorStatus;
  message?: string;
  updatedAt?: number;
  meta?: Record<string, unknown>;
}

export type FlowMonitorData = Record<string, NodeMonitorStatus>;

export function useNodeMonitorStatus(
  nodeId: string | null | undefined,
): NodeMonitorStatus | undefined;

// FlowDesigner gains:
//   monitorMode?: boolean
//   monitorData?: FlowMonitorData
```

Returns `undefined` whenever monitor mode is off OR no entry exists for the
node id. Subscribes via zustand selectors so renderers re-render on every
status transition.

### B2c bpmn-designer migration mapping

The legacy `bpmn-designer/hooks/useNodeMonitorStatus.ts` exposes a
3-state string (`'active' | 'completed' | 'idle' | null`). On migration:

```ts
// in BPMNDesigner.tsx → at the FlowDesigner adapter boundary
const monitorData: FlowMonitorData = useMemo(() => {
  if (!instanceStatus) return {};
  const acc: FlowMonitorData = {};
  for (const n of instanceStatus.currentNodes) acc[n.nodeId] = { status: 'running' };
  for (const n of instanceStatus.completedNodes) acc[n.nodeId] ??= { status: 'completed' };
  return acc;
}, [instanceStatus]);

<FlowDesigner
  monitorMode={viewMode === 'monitor'}
  monitorData={monitorData}
  ...
/>
```

Then drop `bpmn-designer/hooks/useNodeMonitorStatus.ts` and switch every
`bpmn-designer` node renderer's import to the SDK hook.

### Automation A2 alignment TODO

`monitorTypes.ts` carries an explicit `TODO(B2c/A2-bridge)`. A2 (running
flow status injection — landing in a separate worktree) should converge on
this superset enum. If A2 chose different names, a thin mapper in
`AutomationDesigner.tsx` translates at the prop boundary; no SDK fork needed.

## Tests (red line #1)

- `__tests__/useNodeNeighbors.test.tsx` — **6 cases**: no-in/no-out / sink only
  / source only / fan-out (default-flow main use case) / edge re-target
  reactivity / null nodeId.
- `__tests__/useNodeMonitorStatus.test.tsx` — **7 cases**: undefined when
  monitorMode off / undefined when missing entry / pending / running →
  completed reactive transition / failed with message+meta / null nodeId /
  cleared on `reset()`.
- `__tests__/index.exports.test.ts` — **2 cases** guarding G7 + G8 SDK
  surface against accidental removal.

Total: **15 new** + 63 pre-existing SDK tests = **78 passed / 0 failed**.

Command:
```
cd web-admin && pnpm exec vitest run \
  app/plugins/core-designer/components/flow-designer-sdk/__tests__/
# Test Files  8 passed (8)
#      Tests 78 passed (78)
```

Tsc: `pnpm exec tsc --noEmit` — 0 errors globally (no new errors introduced).

## Pre-existing bug noted (not in scope)

`useFlowStore.addEdge` generates ids via `edge_${Date.now()}` with no random
suffix, so edges added inside the same millisecond collide on id. Did not
affect G7 (`useNodeNeighbors` reads structure, not ids), worked around it in
the reactivity test by using a single-edge fixture. Filing a follow-up is
recommended but out of B2a scope.

## Files changed

```
M web-admin/app/plugins/core-designer/components/flow-designer-sdk/index.ts
M web-admin/app/plugins/core-designer/components/flow-designer-sdk/core/FlowDesigner.tsx
M web-admin/app/plugins/core-designer/components/flow-designer-sdk/store/useFlowStore.ts
A web-admin/app/plugins/core-designer/components/flow-designer-sdk/store/monitorTypes.ts
A web-admin/app/plugins/core-designer/components/flow-designer-sdk/hooks/useNodeNeighbors.ts
A web-admin/app/plugins/core-designer/components/flow-designer-sdk/hooks/useNodeMonitorStatus.ts
A web-admin/app/plugins/core-designer/components/flow-designer-sdk/__tests__/useNodeNeighbors.test.tsx
A web-admin/app/plugins/core-designer/components/flow-designer-sdk/__tests__/useNodeMonitorStatus.test.tsx
A web-admin/app/plugins/core-designer/components/flow-designer-sdk/__tests__/index.exports.test.ts
A docs/backlog/2026-05-28-B2a-sdk-gap-fill-report.md
```

## Out of scope (T4 follow-ups)

- B2b — node ports/handles formalization (after G7 default-flow refactor).
- B2c — useBPMNStore → useFlowStore migration (consumes monitorData prop).
- Automation A2 enum reconciliation (bridge PR after A2 lands).
- `bpmn-designer/hooks/useNodeMonitorStatus.ts` deletion (do during B2c so
  no intermediate broken state).
