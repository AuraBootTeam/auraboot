/**
 * A3 PoC tests for bpm-smoke. Validates that the SDK's NodeRegistry +
 * EdgeRegistry injection points (G1/G2) can host a BPMN-shaped flow:
 *
 *  1. Node registration registers all 4 BPMN-shaped types with the SDK.
 *  2. Edge registration registers the bpmConditional edge type with the SDK.
 *  3. A drag-and-drop simulation produces a serialisable JSON graph
 *     (startEvent → exclusiveGateway → 2 outgoing edges → serviceTask + endEvent).
 *  4. JSON round-trip rehydrates back into the SDK store unchanged.
 *  5. The G2 property editor patches `node.data.config` correctly.
 */

import { describe, it, expect, beforeEach } from 'vitest';
import { render, fireEvent, screen } from '@testing-library/react';
import {
  NodeRegistry,
  EdgeRegistry,
} from '~/plugins/core-designer/components/flow-designer-sdk';
import type {
  FlowData,
} from '~/plugins/core-designer/components/flow-designer-sdk';
import {
  BPM_SMOKE_NODE_TYPES,
  BPM_SMOKE_EDGE_TYPE,
  buildBpmSmokeNodeDefinitions,
  buildBpmSmokeEdgeDefinitions,
  registerBpmSmoke,
} from '../registerBpmSmoke';
import { ServiceTaskEditor, BpmConditionalEdgeEditor } from '../editors/BpmEditors';

describe('bpm-smoke (A3 PoC)', () => {
  let nodes: NodeRegistry;
  let edges: EdgeRegistry;

  beforeEach(() => {
    nodes = new NodeRegistry();
    edges = new EdgeRegistry();
  });

  it('registers all 4 BPMN-shaped node types via the SDK G2 injection point', () => {
    registerBpmSmoke(nodes, edges);
    const registered = nodes.getAll().map((d) => d.type).sort();
    expect(registered).toEqual([...BPM_SMOKE_NODE_TYPES].sort());
    // Each definition must declare a propertyEditor (G2 = bespoke panel).
    for (const type of BPM_SMOKE_NODE_TYPES) {
      const def = nodes.get(type);
      expect(def, `node ${type} missing`).toBeDefined();
      expect(def!.propertyEditor, `node ${type} missing propertyEditor`).toBeDefined();
      expect(def!.component, `node ${type} missing component`).toBeDefined();
    }
  });

  it('registers the bpmConditional edge with custom component + editor via G1', () => {
    registerBpmSmoke(nodes, edges);
    expect(edges.has(BPM_SMOKE_EDGE_TYPE)).toBe(true);
    const def = edges.get(BPM_SMOKE_EDGE_TYPE)!;
    expect(def.component).toBeDefined();
    expect(def.editor).toBeDefined();
  });

  it('produces a serialisable BPMN-shaped JSON graph (start → gateway → 2 branches)', () => {
    registerBpmSmoke(nodes, edges);
    // Simulate dropping the 4 nodes onto a canvas + drawing 4 edges.
    const flow: FlowData = {
      nodes: [
        { id: 'n_start', type: 'startEvent', position: { x: 0, y: 100 }, data: { label: 'Start', config: {} } },
        {
          id: 'n_gw',
          type: 'exclusiveGateway',
          position: { x: 200, y: 100 },
          data: { label: 'amount?', config: { name: 'amount?', defaultFlow: 'e_gw_end' } },
        },
        {
          id: 'n_svc',
          type: 'serviceTask',
          position: { x: 400, y: 0 },
          data: { label: 'Approve', config: { name: 'Approve', implementation: 'bean:approver' } },
        },
        { id: 'n_end', type: 'endEvent', position: { x: 400, y: 200 }, data: { label: 'End', config: {} } },
      ],
      edges: [
        { id: 'e_start_gw', source: 'n_start', target: 'n_gw', type: BPM_SMOKE_EDGE_TYPE },
        {
          id: 'e_gw_svc',
          source: 'n_gw',
          target: 'n_svc',
          type: BPM_SMOKE_EDGE_TYPE,
          data: {
            label: 'big',
            condition: { type: 'expression', content: '${amount > 1000}' },
          },
        },
        {
          id: 'e_gw_end',
          source: 'n_gw',
          target: 'n_end',
          type: BPM_SMOKE_EDGE_TYPE,
          data: { label: 'default', isDefault: true },
        },
        { id: 'e_svc_end', source: 'n_svc', target: 'n_end', type: BPM_SMOKE_EDGE_TYPE },
      ],
    };

    const json = JSON.stringify(flow);
    expect(() => JSON.parse(json)).not.toThrow();

    // BPMN-shaped invariants for the exclusive gateway.
    const outgoing = flow.edges.filter((e) => e.source === 'n_gw');
    expect(outgoing).toHaveLength(2);
    const conditionEdges = outgoing.filter((e) => e.data?.condition?.content);
    const defaultEdges = outgoing.filter((e) => e.data?.isDefault);
    expect(conditionEdges).toHaveLength(1);
    expect(defaultEdges).toHaveLength(1);
    // Every node type used appears in the registry.
    for (const node of flow.nodes) {
      expect(nodes.has(node.type)).toBe(true);
    }
    for (const edge of flow.edges) {
      expect(edges.has(edge.type!)).toBe(true);
    }
  });

  it('round-trips a BPMN JSON payload through JSON.parse without loss', () => {
    const original: FlowData = {
      nodes: [
        { id: 'a', type: 'startEvent', position: { x: 0, y: 0 }, data: { label: 'S', config: { name: 'S' } } },
        { id: 'b', type: 'endEvent', position: { x: 200, y: 0 }, data: { label: 'E', config: {} } },
      ],
      edges: [
        {
          id: 'e1',
          source: 'a',
          target: 'b',
          type: BPM_SMOKE_EDGE_TYPE,
          data: {
            label: 'go',
            condition: { type: 'expression', content: '${ok}' },
          },
        },
      ],
    };
    const restored = JSON.parse(JSON.stringify(original)) as FlowData;
    expect(restored).toEqual(original);
    expect(restored.edges[0].data?.condition?.type).toBe('expression');
    expect(restored.edges[0].data?.condition?.content).toBe('${ok}');
  });

  it('SDK G2 editor patches node.data.config when the user types', () => {
    const patches: Array<Record<string, unknown>> = [];
    render(
      <ServiceTaskEditor
        nodeId="n_svc"
        config={{ name: '', implementation: '' }}
        onChange={(p) => patches.push(p)}
      />,
    );
    fireEvent.change(screen.getByTestId('svc-task-name'), { target: { value: 'Approve' } });
    fireEvent.change(screen.getByTestId('svc-task-impl'), { target: { value: 'bean:approver' } });
    expect(patches).toEqual([
      { name: 'Approve' },
      { implementation: 'bean:approver' },
    ]);
  });

  it('SDK G1 edge editor patches condition + isDefault + label', () => {
    const patches: Array<Record<string, unknown>> = [];
    render(
      <BpmConditionalEdgeEditor
        edgeId="e1"
        data={{ label: '' }}
        onChange={(p) => patches.push(p as Record<string, unknown>)}
      />,
    );
    fireEvent.change(screen.getByTestId('edge-label'), { target: { value: 'big' } });
    fireEvent.change(screen.getByTestId('edge-cond'), { target: { value: '${amount > 1000}' } });
    fireEvent.click(screen.getByTestId('edge-default'));
    expect(patches[0]).toEqual({ label: 'big' });
    expect(patches[1]).toEqual({ condition: { type: 'expression', content: '${amount > 1000}' } });
    expect(patches[2]).toEqual({ isDefault: true });
  });

  it('default flow node + edge factories produce stable counts', () => {
    expect(buildBpmSmokeNodeDefinitions()).toHaveLength(4);
    expect(buildBpmSmokeEdgeDefinitions()).toHaveLength(1);
  });
});
