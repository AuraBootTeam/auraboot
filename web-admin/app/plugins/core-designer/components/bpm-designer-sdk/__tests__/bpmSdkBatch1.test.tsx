/**
 * B2b batch1 unit tests — 4 BPMN nodes ported onto flow-designer-sdk via G2.
 *
 * Coverage matrix (target ≥ 12 cases):
 *
 *   Registration (3)
 *     1. registerBpmSdkBatch1 registers all 4 types
 *     2. each definition exposes component + propertyEditor (G2)
 *     3. each definition exposes the expected NodeValidation invariants
 *        (start has maxInputs=0, end has maxOutputs=0, etc.)
 *
 *   Rendering (4)
 *     4. StartEventNode renders with handle + label
 *     5. EndEventNode renders with handle + label
 *     6. ParallelGatewayNode renders rotated diamond + label
 *     7. ServiceTaskNode renders label + service subtitle for serviceType=http
 *
 *   PropertyEditor patches (4)
 *     8. StartEventEditor onChange emits {description: ...} patch
 *     9. EndEventEditor onChange emits {terminateAll: true} patch
 *    10. ParallelGatewayEditor onChange emits {description: ...} patch
 *    11. ServiceTaskEditor onChange emits {serviceType: 'java'} patch +
 *        conditional className field appears
 *
 *   Status overlay (1) + JSON round-trip (1) + factory stability (1)
 *    12. StartEventNode shows completed badge when monitor reports completed
 *        (via SDK store G8 monitorMode + monitorData)
 *    13. JSON round-trip of a 4-node BPMN graph (start → parallelGateway →
 *        serviceTask → endEvent) is loss-free
 *    14. buildBpmSdkBatch1NodeDefinitions returns stable 4-item list
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
  BPM_SDK_BATCH1_NODE_TYPES,
  buildBpmSdkBatch1NodeDefinitions,
  registerBpmSdkBatch1,
  StartEventNode,
  EndEventNode,
  ParallelGatewayNode,
  ServiceTaskNode,
  StartEventEditor,
  EndEventEditor,
  ParallelGatewayEditor,
  ServiceTaskEditor,
} from '../index';

// Each xyflow node renderer pulls state via useNodeId / store; wrap in
// ReactFlowProvider so the renderer is allowed to render outside a real
// canvas. We pass the `id` prop directly so useNodeMonitorStatus can read it.
function renderNode(Component: React.ComponentType<any>, props: Record<string, any>) {
  return render(
    <ReactFlowProvider>
      <Component {...props} />
    </ReactFlowProvider>,
  );
}

describe('bpm-designer-sdk batch1 (B2b)', () => {
  // ---------- Registration ----------

  describe('registration', () => {
    let nodes: NodeRegistry;
    beforeEach(() => {
      nodes = new NodeRegistry();
    });

    it('1) registers all 4 batch1 types', () => {
      registerBpmSdkBatch1(nodes);
      const registered = nodes.getAll().map((d) => d.type).sort();
      expect(registered).toEqual([...BPM_SDK_BATCH1_NODE_TYPES].sort());
    });

    it('2) each definition exposes a component + propertyEditor (G2)', () => {
      registerBpmSdkBatch1(nodes);
      for (const type of BPM_SDK_BATCH1_NODE_TYPES) {
        const def = nodes.get(type);
        expect(def, `${type} not registered`).toBeDefined();
        expect(def!.component, `${type} missing component`).toBeDefined();
        expect(def!.propertyEditor, `${type} missing propertyEditor`).toBeDefined();
        expect(def!.category).toMatch(/^bpm\./);
      }
    });

    it('3) each definition exposes the expected structural validation rules', () => {
      registerBpmSdkBatch1(nodes);
      const start = nodes.get('startEvent')!;
      const end = nodes.get('endEvent')!;
      const gw = nodes.get('parallelGateway')!;
      const svc = nodes.get('serviceTask')!;
      expect(start.validation).toMatchObject({ maxInputs: 0, minOutputs: 1 });
      expect(end.validation).toMatchObject({ minInputs: 1, maxOutputs: 0 });
      expect(gw.validation).toMatchObject({ minInputs: 1, minOutputs: 1 });
      expect(svc.validation).toMatchObject({ minInputs: 1, minOutputs: 1 });
    });
  });

  // ---------- Rendering ----------

  describe('node rendering', () => {
    it('4) StartEventNode renders ▶ glyph + bottom label', () => {
      renderNode(StartEventNode, {
        id: 'n1',
        data: { label: 'Begin', config: {} },
        selected: false,
        type: 'startEvent',
        zIndex: 0,
        isConnectable: true,
        xPos: 0,
        yPos: 0,
        dragging: false,
      });
      expect(screen.getByTestId('bpm-sdk-start-event')).toBeInTheDocument();
      expect(screen.getByText('▶')).toBeInTheDocument();
      expect(screen.getByText('Begin')).toBeInTheDocument();
    });

    it('5) EndEventNode renders ⬛ glyph + bottom label', () => {
      renderNode(EndEventNode, {
        id: 'n2',
        data: { label: 'Done', config: {} },
        selected: false,
        type: 'endEvent',
        zIndex: 0,
        isConnectable: true,
        xPos: 0,
        yPos: 0,
        dragging: false,
      });
      expect(screen.getByTestId('bpm-sdk-end-event')).toBeInTheDocument();
      expect(screen.getByText('⬛')).toBeInTheDocument();
      expect(screen.getByText('Done')).toBeInTheDocument();
    });

    it('6) ParallelGatewayNode renders + glyph + bottom label inside rotated container', () => {
      renderNode(ParallelGatewayNode, {
        id: 'n3',
        data: { label: 'Fork', config: {} },
        selected: false,
        type: 'parallelGateway',
        zIndex: 0,
        isConnectable: true,
        xPos: 0,
        yPos: 0,
        dragging: false,
      });
      const wrapper = screen.getByTestId('bpm-sdk-parallel-gateway');
      expect(wrapper).toBeInTheDocument();
      // Outer div has rotate-45 applied to render diamond shape.
      const rotated = wrapper.querySelector('.rotate-45');
      expect(rotated).not.toBeNull();
      expect(screen.getByText('+')).toBeInTheDocument();
      expect(screen.getByText('Fork')).toBeInTheDocument();
    });

    it('7) ServiceTaskNode renders ⚙ + label + http URL subtitle when serviceType=http', () => {
      renderNode(ServiceTaskNode, {
        id: 'n4',
        data: {
          label: 'Call API',
          config: { serviceType: 'http', serviceUrl: 'https://example.com/x' },
        },
        selected: false,
        type: 'serviceTask',
        zIndex: 0,
        isConnectable: true,
        xPos: 0,
        yPos: 0,
        dragging: false,
      });
      expect(screen.getByTestId('bpm-sdk-service-task')).toBeInTheDocument();
      expect(screen.getByText('⚙')).toBeInTheDocument();
      expect(screen.getByText('Call API')).toBeInTheDocument();
      expect(screen.getByText('https://example.com/x')).toBeInTheDocument();
    });
  });

  // ---------- PropertyEditor patches ----------

  describe('property-editor patches (G2 NodePropertyEditorProps)', () => {
    it('8) StartEventEditor emits {description} patch on textarea change', () => {
      const patches: Array<Record<string, unknown>> = [];
      render(
        <StartEventEditor
          nodeId="n1"
          config={{}}
          onChange={(p) => patches.push(p)}
        />,
      );
      fireEvent.change(screen.getByTestId('bpm-sdk-start-description'), {
        target: { value: 'kick off' },
      });
      expect(patches).toEqual([{ description: 'kick off' }]);
    });

    it('9) EndEventEditor emits {terminateAll: true} patch on checkbox click', () => {
      const patches: Array<Record<string, unknown>> = [];
      render(
        <EndEventEditor
          nodeId="n2"
          config={{}}
          onChange={(p) => patches.push(p)}
        />,
      );
      fireEvent.click(screen.getByTestId('bpm-sdk-end-terminate-all'));
      expect(patches).toEqual([{ terminateAll: true }]);
    });

    it('10) ParallelGatewayEditor emits {description} patch on textarea change', () => {
      const patches: Array<Record<string, unknown>> = [];
      render(
        <ParallelGatewayEditor
          nodeId="n3"
          config={{}}
          onChange={(p) => patches.push(p)}
        />,
      );
      fireEvent.change(screen.getByTestId('bpm-sdk-parallel-description'), {
        target: { value: 'fan-out' },
      });
      expect(patches).toEqual([{ description: 'fan-out' }]);
    });

    it('11) ServiceTaskEditor switches to java and reveals className input', () => {
      const patches: Array<Record<string, unknown>> = [];
      const { rerender } = render(
        <ServiceTaskEditor
          nodeId="n4"
          config={{ serviceType: 'http' }}
          onChange={(p) => patches.push(p)}
        />,
      );
      // HTTP variant first — className input must not be present.
      expect(screen.queryByTestId('bpm-sdk-svc-class-name')).toBeNull();

      fireEvent.change(screen.getByTestId('bpm-sdk-svc-service-type'), {
        target: { value: 'java' },
      });
      expect(patches).toEqual([{ serviceType: 'java' }]);

      // Re-render with java config so the conditional className input mounts.
      rerender(
        <ServiceTaskEditor
          nodeId="n4"
          config={{ serviceType: 'java' }}
          onChange={(p) => patches.push(p)}
        />,
      );
      const classInput = screen.getByTestId('bpm-sdk-svc-class-name');
      expect(classInput).toBeInTheDocument();

      fireEvent.change(classInput, { target: { value: 'com.example.Approver' } });
      expect(patches[1]).toEqual({ className: 'com.example.Approver' });
    });
  });

  // ---------- Monitor overlay (G8) ----------

  describe('monitor overlay (G8 useNodeMonitorStatus)', () => {
    it('12) StartEventNode shows completed badge when monitorData reports completed', () => {
      act(() => {
        useFlowStore.getState().reset();
        useFlowStore.getState().setMonitorMode(true);
        useFlowStore.getState().setMonitorData({
          'n-completed': { status: 'completed', updatedAt: Date.now() },
        });
      });

      renderNode(StartEventNode, {
        id: 'n-completed',
        data: { label: 'Start', config: {} },
        selected: false,
        type: 'startEvent',
        zIndex: 0,
        isConnectable: true,
        xPos: 0,
        yPos: 0,
        dragging: false,
      });

      expect(screen.getByTestId('bpm-sdk-completed-badge')).toBeInTheDocument();

      // Reset state to avoid leaking into sibling tests.
      act(() => {
        useFlowStore.getState().reset();
      });
    });
  });

  // ---------- JSON round-trip ----------

  describe('JSON round-trip', () => {
    it('13) 4-node BPMN graph (start → parallelGateway → serviceTask → endEvent) round-trips losslessly', () => {
      const original: FlowData = {
        nodes: [
          {
            id: 'n_start',
            type: 'startEvent',
            position: { x: 0, y: 100 },
            data: { label: 'Begin', config: { name: 'Begin', initiator: 'initiator' } },
          },
          {
            id: 'n_gw',
            type: 'parallelGateway',
            position: { x: 200, y: 100 },
            data: { label: 'Fork', config: { name: 'Fork' } },
          },
          {
            id: 'n_svc',
            type: 'serviceTask',
            position: { x: 400, y: 100 },
            data: {
              label: 'Notify',
              config: {
                name: 'Notify',
                serviceType: 'http',
                serviceUrl: 'https://example.com/notify',
                async: true,
              },
            },
          },
          {
            id: 'n_end',
            type: 'endEvent',
            position: { x: 600, y: 100 },
            data: { label: 'Done', config: { name: 'Done', terminateAll: false } },
          },
        ],
        edges: [
          { id: 'e1', source: 'n_start', target: 'n_gw' },
          { id: 'e2', source: 'n_gw', target: 'n_svc' },
          { id: 'e3', source: 'n_svc', target: 'n_end' },
        ],
      };

      const restored = JSON.parse(JSON.stringify(original)) as FlowData;
      expect(restored).toEqual(original);

      // Every node type used must be in the batch1 registry.
      const reg = new NodeRegistry();
      registerBpmSdkBatch1(reg);
      for (const node of restored.nodes) {
        expect(reg.has(node.type)).toBe(true);
      }
    });
  });

  // ---------- Factory stability ----------

  describe('factory stability', () => {
    it('14) buildBpmSdkBatch1NodeDefinitions returns 4 definitions in deterministic order', () => {
      const defs = buildBpmSdkBatch1NodeDefinitions();
      expect(defs).toHaveLength(4);
      expect(defs.map((d) => d.type)).toEqual([
        'startEvent',
        'endEvent',
        'parallelGateway',
        'serviceTask',
      ]);
    });
  });
});
