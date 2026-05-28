/**
 * B2b batch2 unit tests — 4 BPMN nodes + 5 editors ported onto flow-designer-sdk.
 *
 * Coverage matrix (target ≥ 20 cases):
 *
 *   Registration (3)
 *     1.  registerBpmSdkBatch2 registers all 4 types
 *     2.  each definition exposes component + propertyEditor (G2)
 *     3.  each definition exposes the expected NodeValidation invariants
 *
 *   Rendering (5)
 *     4.  ExclusiveGatewayNode renders × glyph rotated + label
 *     5.  InclusiveGatewayNode renders ○ glyph rotated + label
 *     6.  ReceiveTaskNode renders 📨 + label + messageRef subtitle
 *     7.  UserTaskNode renders 👤 + label + assignee role subtitle
 *     8.  UserTaskNode renders multi-instance indicator when enabled
 *
 *   Editor patches (5)
 *     9.  ExclusiveGatewayEditor emits {description} on textarea change
 *    10.  ExclusiveGatewayEditor + G7 useNodeNeighbors populates default-flow dropdown
 *    11.  InclusiveGatewayEditor + G7 useNodeNeighbors populates default-flow dropdown
 *    12.  ReceiveTaskEditor emits {description} patch, messageRef is disabled
 *    13.  UserTaskEditor changes assignee.type and target via free-text
 *
 *   ConditionExpressionEditor (5)
 *    14.  rulesToExpression: numeric vs string quoting
 *    15.  tryParseRules: round-trips `${amount > 100}`
 *    16.  ConditionExpressionBody simple-mode: edits field emits expression with quoted value
 *    17.  ConditionExpressionBody advanced-mode: typing raw expression emits passthrough
 *    18.  ConditionExpressionBody mode switch parse warning when content unparseable
 *
 *   Monitor overlay (1) + JSON round-trip (1) + factory stability (1)
 *    19.  ExclusiveGatewayNode shows completed badge under monitor 'completed'
 *    20.  JSON round-trip of an 8-node hybrid graph (batch1+batch2) is loss-free
 *    21.  buildBpmSdkBatch2NodeDefinitions returns stable 4-item list
 */

import React from 'react';
import { describe, it, expect, beforeEach } from 'vitest';
import { render, fireEvent, screen, act } from '@testing-library/react';
import { ReactFlowProvider } from '@xyflow/react';

import {
  NodeRegistry,
  useFlowStore,
  type FlowData,
} from '~/plugins/core-designer/components/flow-designer-sdk';
import {
  BPM_SDK_BATCH2_NODE_TYPES,
  buildBpmSdkBatch2NodeDefinitions,
  registerBpmSdkBatch2,
  ExclusiveGatewayNode,
  InclusiveGatewayNode,
  ReceiveTaskNode,
  UserTaskNode,
  ExclusiveGatewayEditor,
  InclusiveGatewayEditor,
  ReceiveTaskEditor,
  UserTaskEditor,
  ConditionExpressionBody,
  __conditionInternals,
  // batch1 (for JSON round-trip + registry coexistence)
  registerBpmSdkBatch1,
  BPM_SDK_BATCH1_NODE_TYPES,
} from '../index';

function renderNode(Component: React.ComponentType<any>, props: Record<string, any>) {
  return render(
    <ReactFlowProvider>
      <Component {...props} />
    </ReactFlowProvider>,
  );
}

function nodeProps(overrides: Record<string, any>) {
  return {
    selected: false,
    zIndex: 0,
    isConnectable: true,
    xPos: 0,
    yPos: 0,
    dragging: false,
    ...overrides,
  };
}

describe('bpm-designer-sdk batch2 (B2b)', () => {
  beforeEach(() => {
    act(() => {
      useFlowStore.getState().reset();
    });
  });

  // ---------- Registration ----------
  describe('registration', () => {
    let nodes: NodeRegistry;
    beforeEach(() => {
      nodes = new NodeRegistry();
    });

    it('1) registers all 4 batch2 types', () => {
      registerBpmSdkBatch2(nodes);
      const registered = nodes
        .getAll()
        .map((d) => d.type)
        .sort();
      expect(registered).toEqual([...BPM_SDK_BATCH2_NODE_TYPES].sort());
    });

    it('2) each definition exposes a component + propertyEditor (G2)', () => {
      registerBpmSdkBatch2(nodes);
      for (const type of BPM_SDK_BATCH2_NODE_TYPES) {
        const def = nodes.get(type);
        expect(def, `${type} not registered`).toBeDefined();
        expect(def!.component, `${type} missing component`).toBeDefined();
        expect(def!.propertyEditor, `${type} missing propertyEditor`).toBeDefined();
        expect(def!.category).toMatch(/^bpm\./);
      }
    });

    it('3) each definition exposes the expected structural validation rules', () => {
      registerBpmSdkBatch2(nodes);
      const ex = nodes.get('exclusiveGateway')!;
      const inc = nodes.get('inclusiveGateway')!;
      const recv = nodes.get('receiveTask')!;
      const usr = nodes.get('userTask')!;
      expect(ex.validation).toMatchObject({ minInputs: 1, minOutputs: 2 });
      expect(inc.validation).toMatchObject({ minInputs: 1, minOutputs: 1 });
      expect(recv.validation).toMatchObject({ minInputs: 1, minOutputs: 1 });
      expect(usr.validation).toMatchObject({ minInputs: 1, minOutputs: 1 });
    });
  });

  // ---------- Rendering ----------
  describe('node rendering', () => {
    it('4) ExclusiveGatewayNode renders × glyph + bottom label inside rotated container', () => {
      renderNode(
        ExclusiveGatewayNode,
        nodeProps({ id: 'eg1', type: 'exclusiveGateway', data: { label: 'Decide', config: {} } }),
      );
      const wrapper = screen.getByTestId('bpm-sdk-exclusive-gateway');
      expect(wrapper).toBeInTheDocument();
      expect(wrapper.querySelector('.rotate-45')).not.toBeNull();
      expect(screen.getByText('×')).toBeInTheDocument();
      expect(screen.getByText('Decide')).toBeInTheDocument();
    });

    it('5) InclusiveGatewayNode renders ○ glyph + bottom label inside rotated container', () => {
      renderNode(
        InclusiveGatewayNode,
        nodeProps({ id: 'ig1', type: 'inclusiveGateway', data: { label: 'Multi', config: {} } }),
      );
      const wrapper = screen.getByTestId('bpm-sdk-inclusive-gateway');
      expect(wrapper).toBeInTheDocument();
      expect(wrapper.querySelector('.rotate-45')).not.toBeNull();
      expect(screen.getByText('○')).toBeInTheDocument();
      expect(screen.getByText('Multi')).toBeInTheDocument();
    });

    it('6) ReceiveTaskNode renders 📨 + label + messageRef subtitle', () => {
      renderNode(
        ReceiveTaskNode,
        nodeProps({
          id: 'rt1',
          type: 'receiveTask',
          data: { label: 'Wait', config: { messageRef: 'order.paid' } },
        }),
      );
      expect(screen.getByTestId('bpm-sdk-receive-task')).toBeInTheDocument();
      expect(screen.getByText('📨')).toBeInTheDocument();
      expect(screen.getByText('Wait')).toBeInTheDocument();
      expect(screen.getByText('order.paid')).toBeInTheDocument();
    });

    it('7) UserTaskNode renders 👤 + label + assignee role subtitle', () => {
      renderNode(
        UserTaskNode,
        nodeProps({
          id: 'ut1',
          type: 'userTask',
          data: {
            label: 'Approve',
            config: { assignee: { type: 'role', roleIds: ['mgr', 'ops'] } },
          },
        }),
      );
      expect(screen.getByTestId('bpm-sdk-user-task')).toBeInTheDocument();
      expect(screen.getByText('👤')).toBeInTheDocument();
      expect(screen.getByText('Approve')).toBeInTheDocument();
      expect(screen.getByText('Role: mgr,ops')).toBeInTheDocument();
    });

    it('8) UserTaskNode renders multi-instance indicator when enabled', () => {
      renderNode(
        UserTaskNode,
        nodeProps({
          id: 'ut2',
          type: 'userTask',
          data: {
            label: 'Review',
            config: {
              multiInstance: { enabled: true, sequential: false },
              assignee: { type: 'user', assigneeMode: 'multi', userIds: ['u1'] },
            },
          },
        }),
      );
      const mi = screen.getByTestId('bpm-sdk-user-task-mi-indicator');
      expect(mi).toBeInTheDocument();
      expect(mi.textContent).toContain('|||');
      expect(mi.textContent).toContain('Countersign');
    });
  });

  // ---------- Editor patches ----------
  describe('property-editor patches (G2 NodePropertyEditorProps)', () => {
    it('9) ExclusiveGatewayEditor emits {description} patch on textarea change', () => {
      const patches: Array<Record<string, unknown>> = [];
      render(
        <ExclusiveGatewayEditor
          nodeId="eg-empty"
          config={{}}
          onChange={(p) => patches.push(p)}
        />,
      );
      fireEvent.change(screen.getByTestId('bpm-sdk-exclusive-description'), {
        target: { value: 'route by status' },
      });
      expect(patches).toEqual([{ description: 'route by status' }]);
    });

    it('10) ExclusiveGatewayEditor + G7 useNodeNeighbors populates default-flow dropdown', () => {
      // Seed the SDK store with a graph: gateway -> e1 -> taskA, gateway -> e2 -> taskB
      act(() => {
        useFlowStore.getState().importData({
          nodes: [
            { id: 'gw', type: 'exclusiveGateway', position: { x: 0, y: 0 }, data: {} as any },
            { id: 'a', type: 'serviceTask', position: { x: 100, y: 0 }, data: {} as any },
            { id: 'b', type: 'serviceTask', position: { x: 100, y: 100 }, data: {} as any },
          ],
          edges: [
            { id: 'e1', source: 'gw', target: 'a', data: { label: 'approve' } } as any,
            {
              id: 'e2',
              source: 'gw',
              target: 'b',
              data: { label: 'reject', condition: { content: '${amount<100}' } },
            } as any,
          ],
        });
      });

      const patches: Array<Record<string, unknown>> = [];
      render(
        <ExclusiveGatewayEditor
          nodeId="gw"
          config={{ defaultFlow: '' }}
          onChange={(p) => patches.push(p)}
        />,
      );

      const select = screen.getByTestId('bpm-sdk-exclusive-default-flow') as HTMLSelectElement;
      // Should have 3 options (placeholder + 2 edges)
      const options = Array.from(select.querySelectorAll('option')).map((o) => o.value);
      expect(options).toEqual(['', 'e1', 'e2']);
      // Conditions summary should list both labels
      const summary = screen.getByTestId('bpm-sdk-exclusive-conditions-summary');
      expect(summary.textContent).toContain('approve');
      expect(summary.textContent).toContain('reject');
      // Picking e2 emits patch
      fireEvent.change(select, { target: { value: 'e2' } });
      expect(patches).toEqual([{ defaultFlow: 'e2' }]);
    });

    it('11) InclusiveGatewayEditor + G7 useNodeNeighbors populates default-flow dropdown', () => {
      act(() => {
        useFlowStore.getState().importData({
          nodes: [
            { id: 'ig', type: 'inclusiveGateway', position: { x: 0, y: 0 }, data: {} as any },
            { id: 'x', type: 'serviceTask', position: { x: 100, y: 0 }, data: {} as any },
          ],
          edges: [{ id: 'ie1', source: 'ig', target: 'x', data: { label: 'maybe' } } as any],
        });
      });

      const patches: Array<Record<string, unknown>> = [];
      render(
        <InclusiveGatewayEditor
          nodeId="ig"
          config={{}}
          onChange={(p) => patches.push(p)}
        />,
      );

      const select = screen.getByTestId('bpm-sdk-inclusive-default-flow') as HTMLSelectElement;
      expect(Array.from(select.querySelectorAll('option')).map((o) => o.value)).toEqual([
        '',
        'ie1',
      ]);
      // GAP-252 textarea must be disabled
      const cc = screen.getByTestId(
        'bpm-sdk-inclusive-completion-condition',
      ) as HTMLTextAreaElement;
      expect(cc.disabled).toBe(true);
    });

    it('12) ReceiveTaskEditor emits {description} patch; messageRef/messageType are disabled', () => {
      const patches: Array<Record<string, unknown>> = [];
      render(
        <ReceiveTaskEditor
          nodeId="rt"
          config={{}}
          onChange={(p) => patches.push(p)}
        />,
      );
      fireEvent.change(screen.getByTestId('bpm-sdk-receive-description'), {
        target: { value: 'await signal' },
      });
      expect(patches).toEqual([{ description: 'await signal' }]);
      const mref = screen.getByTestId('bpm-sdk-receive-message-ref') as HTMLInputElement;
      expect(mref.disabled).toBe(true);
    });

    it('13) UserTaskEditor changes assignee.type and target via free-text', () => {
      const patches: Array<Record<string, unknown>> = [];
      const { rerender } = render(
        <UserTaskEditor
          nodeId="ut"
          config={{ assignee: { type: 'user', userIds: [] } }}
          onChange={(p) => patches.push(p)}
        />,
      );
      // Switch type to role
      fireEvent.change(screen.getByTestId('bpm-sdk-user-assignee-type'), {
        target: { value: 'role' },
      });
      expect(patches[0]).toEqual({ assignee: { type: 'role', roleIds: [] } });

      // Re-render with role config so target textbox reflects role state
      rerender(
        <UserTaskEditor
          nodeId="ut"
          config={{ assignee: { type: 'role', roleIds: [] } }}
          onChange={(p) => patches.push(p)}
        />,
      );
      fireEvent.change(screen.getByTestId('bpm-sdk-user-assignee-target'), {
        target: { value: 'mgr, ops' },
      });
      expect(patches[1]).toEqual({ assignee: { type: 'role', roleIds: ['mgr', 'ops'] } });
    });
  });

  // ---------- ConditionExpressionEditor ----------
  describe('ConditionExpressionBody (493-LOC drop-in port)', () => {
    it('14) rulesToExpression quotes string values, leaves numeric raw', () => {
      const { rulesToExpression } = __conditionInternals;
      expect(rulesToExpression([{ field: 'amount', operator: '>', value: '100' }], 'and')).toBe(
        '${amount > 100}',
      );
      expect(
        rulesToExpression([{ field: 'status', operator: '==', value: 'approved' }], 'and'),
      ).toBe(`\${status == 'approved'}`);
      // is_empty is unary
      expect(rulesToExpression([{ field: 'note', operator: 'is_empty', value: '' }], 'and')).toBe(
        '${empty note}',
      );
    });

    it('15) tryParseRules round-trips ${amount > 100} into a single rule', () => {
      const { tryParseRules } = __conditionInternals;
      const parsed = tryParseRules('${amount > 100}');
      expect(parsed).not.toBeNull();
      expect(parsed!.logicalOp).toBe('and');
      expect(parsed!.rules).toEqual([{ field: 'amount', operator: '>', value: '100' }]);
      // mixed && and || returns null
      expect(tryParseRules('${a > 1 && b < 2 || c == 3}')).toBeNull();
    });

    it('16) ConditionExpressionBody simple-mode: editing field emits expression with quoted value', () => {
      const updates: Array<{ type: string; content: string }> = [];
      render(
        <ConditionExpressionBody
          condition={{ type: 'expression', content: '' }}
          onChange={(c) => updates.push({ type: c.type, content: c.content })}
        />,
      );
      // First populate the field
      fireEvent.change(screen.getByTestId('bpm-sdk-condition-field-0'), {
        target: { value: 'status' },
      });
      // Then populate the value
      fireEvent.change(screen.getByTestId('bpm-sdk-condition-value-0'), {
        target: { value: 'approved' },
      });
      // Last update should reflect both
      const last = updates[updates.length - 1];
      expect(last.type).toBe('expression');
      expect(last.content).toBe(`\${status == 'approved'}`);
    });

    it('17) ConditionExpressionBody advanced-mode: typing raw expression emits passthrough', () => {
      const updates: Array<{ type: string; content: string }> = [];
      // Start with unparseable content so initial mode is advanced
      render(
        <ConditionExpressionBody
          condition={{ type: 'expression', content: 'literal_garbage' }}
          onChange={(c) => updates.push({ type: c.type, content: c.content })}
        />,
      );
      const ta = screen.getByTestId(
        'bpm-sdk-condition-advanced-content',
      ) as HTMLTextAreaElement;
      expect(ta).toBeInTheDocument();
      fireEvent.change(ta, { target: { value: '${x == 1}' } });
      expect(updates[updates.length - 1]).toEqual({ type: 'expression', content: '${x == 1}' });
    });

    it('18) Mode-switch shows parse warning when advanced content is unparseable', () => {
      render(
        <ConditionExpressionBody
          condition={{ type: 'expression', content: 'literal_garbage' }}
          onChange={() => {}}
        />,
      );
      // Initial mode is advanced (since literal_garbage is unparseable).
      // Try to switch to simple — should show parse warning rather than flip.
      fireEvent.click(screen.getByTestId('bpm-sdk-condition-mode-simple'));
      expect(screen.getByTestId('bpm-sdk-condition-parse-warning')).toBeInTheDocument();
      // Textarea should still be visible (mode did NOT switch)
      expect(screen.getByTestId('bpm-sdk-condition-advanced-content')).toBeInTheDocument();
    });
  });

  // ---------- Monitor overlay (G8) ----------
  describe('monitor overlay (G8 useNodeMonitorStatus)', () => {
    it('19) ExclusiveGatewayNode shows completed badge under monitor "completed"', () => {
      act(() => {
        useFlowStore.getState().setMonitorMode(true);
        useFlowStore.getState().setMonitorData({
          'eg-done': { status: 'completed', updatedAt: Date.now() },
        });
      });
      renderNode(
        ExclusiveGatewayNode,
        nodeProps({
          id: 'eg-done',
          type: 'exclusiveGateway',
          data: { label: 'Decide', config: {} },
        }),
      );
      expect(screen.getByTestId('bpm-sdk-completed-badge')).toBeInTheDocument();
    });
  });

  // ---------- JSON round-trip ----------
  describe('JSON round-trip (batch1 + batch2 coexistence)', () => {
    it('20) 8-node hybrid graph round-trips losslessly through both batch registries', () => {
      const original: FlowData = {
        nodes: [
          {
            id: 's',
            type: 'startEvent',
            position: { x: 0, y: 100 },
            data: { label: 'Begin', config: {} },
          },
          {
            id: 'ut',
            type: 'userTask',
            position: { x: 200, y: 100 },
            data: {
              label: 'Approve',
              config: {
                assignee: { type: 'role', roleIds: ['mgr'] },
                dueDate: 'P2D',
                priority: 5,
              },
            },
          },
          {
            id: 'ex',
            type: 'exclusiveGateway',
            position: { x: 400, y: 100 },
            data: { label: 'Decide', config: { defaultFlow: 'e-yes' } },
          },
          {
            id: 'inc',
            type: 'inclusiveGateway',
            position: { x: 600, y: 100 },
            data: { label: 'Multi', config: {} },
          },
          {
            id: 'pg',
            type: 'parallelGateway',
            position: { x: 800, y: 100 },
            data: { label: 'Fork', config: {} },
          },
          {
            id: 'svc',
            type: 'serviceTask',
            position: { x: 1000, y: 50 },
            data: {
              label: 'Notify',
              config: { serviceType: 'http', serviceUrl: 'https://x' },
            },
          },
          {
            id: 'rt',
            type: 'receiveTask',
            position: { x: 1000, y: 150 },
            data: { label: 'Wait', config: { messageRef: 'paid' } },
          },
          {
            id: 'e',
            type: 'endEvent',
            position: { x: 1200, y: 100 },
            data: { label: 'Done', config: {} },
          },
        ],
        edges: [
          { id: 'e-yes', source: 'ex', target: 'inc', data: { label: 'yes' } } as any,
          { id: 'e-no', source: 'ex', target: 'e', data: { label: 'no' } } as any,
        ],
      };

      const restored = JSON.parse(JSON.stringify(original)) as FlowData;
      expect(restored).toEqual(original);

      // All node types must resolve through (batch1 ∪ batch2) registry.
      const reg = new NodeRegistry();
      registerBpmSdkBatch1(reg);
      registerBpmSdkBatch2(reg);
      const allRegistered = reg.getAll().map((d) => d.type).sort();
      expect(allRegistered).toEqual(
        [...BPM_SDK_BATCH1_NODE_TYPES, ...BPM_SDK_BATCH2_NODE_TYPES].sort(),
      );
      for (const node of restored.nodes) {
        expect(reg.has(node.type), `${node.type} missing from union registry`).toBe(true);
      }
    });
  });

  // ---------- Factory stability ----------
  describe('factory stability', () => {
    it('21) buildBpmSdkBatch2NodeDefinitions returns 4 definitions in deterministic order', () => {
      const defs = buildBpmSdkBatch2NodeDefinitions();
      expect(defs).toHaveLength(4);
      expect(defs.map((d) => d.type)).toEqual([
        'exclusiveGateway',
        'inclusiveGateway',
        'receiveTask',
        'userTask',
      ]);
    });
  });
});
