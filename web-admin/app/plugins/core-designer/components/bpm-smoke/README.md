# bpm-smoke (A3 PoC)

One-shot PoC proving that `flow-designer-sdk` can host a BPMN-shaped flow
(4 core nodes + conditional outgoing edge) via NodeRegistry (G2) +
EdgeRegistry (G1) injection points, without touching `bpmn-designer/`.

Scope (intentional):
- 4 node types: `startEvent`, `exclusiveGateway`, `serviceTask`, `endEvent`
- 1 edge type: `bpmConditional` (label + condition expression + isDefault flag)
- Bespoke property editors registered through G2 (`FlowNodeDefinition.propertyEditor`)
- Bespoke edge editor registered through G1 (`FlowEdgeDefinition.editor`)

Out of scope:
- Multi-handle nodes (gateway diamond rotation, user task forms, call activity)
- Monitor-mode status overlays
- BPMN XML import/export
- Touching existing `bpmn-designer/` consumers

See `docs/backlog/2026-05-28-A3-T4-feasibility-report.md` for the full
inventory, gap analysis, and T4 migration estimate.
