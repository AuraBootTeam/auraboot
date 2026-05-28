/**
 * B2b batch3 unit tests — 1 BPMN node (callActivity) + 5 editors/sections
 * + 2 remote-data pickers + 1 edge editor ported onto flow-designer-sdk.
 *
 * Coverage matrix (target ≥ 25 cases):
 *
 *   Registration (3)
 *     1. registerBpmSdkBatch3 registers the callActivity type
 *     2. callActivity definition exposes component + propertyEditor (G2)
 *     3. callActivity definition exposes the expected NodeValidation invariants
 *     +  registerBpmSdkAll aggregates batch1+2+3 to 9 total types
 *
 *   CallActivityNode rendering (3)
 *     4. renders ⊙ glyph + label
 *     5. renders calledProcessKey subtitle when set
 *     6. completed badge shows under monitor 'completed'
 *
 *   CallActivityEditor — G2 patches (3)
 *     7. description textarea emits {description} patch
 *     8. version mode select emits {calledProcessVersion} patch
 *     9. ProcessPicker selection emits {calledProcessKey} patch
 *
 *   MultiInstanceSection (3)
 *    10. empty config: only toggle button rendered (collapsed)
 *    11. enabled=true expands all 5 fields
 *    12. sequential radio emits onChange with sequential:true
 *
 *   FormBindingSection (3)
 *    13. empty bindings: section starts collapsed
 *    14. expanded shows page-picker but NOT mapping/permission (no formRef)
 *    15. clearing formRef yields onChange([]) — section drops the binding
 *
 *   HookConfigSection (3)
 *    16. zero hooks: header shows "(0)" and collapsed by default
 *    17. addHook emits new pre_execute http_callback hook
 *    18. switching action type to script swaps the sub-config UI
 *
 *   AssigneePicker (3 — real fetch helpers mocked)
 *    19. loading state visible while fetcher pending
 *    20. selecting an option toggles into value[] (controlled)
 *    21. empty option list shows "no results" empty state
 *
 *   ProcessPicker (3 — mock http-client GET)
 *    22. loading state visible until fetch resolves
 *    23. loaded options populate select and search filters
 *    24. fetch error → empty state (no crash)
 *
 *   EdgeEditor (3)
 *    25. label input emits {label} patch
 *    26. condition body present; default checkbox emits {isDefault}
 *    27. JSON round-trip of edge data through patch sequence is loss-free
 *
 *   JSON round-trip (1)
 *    28. JSON round-trip of a 9-node hybrid graph (batch1+2+3) is loss-free
 */

import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, fireEvent, screen, act, waitFor } from '@testing-library/react';
import { ReactFlowProvider } from '@xyflow/react';

// ---- Mock http-client BEFORE importing the SDK modules ----
const mockGet = vi.fn();
const mockPost = vi.fn();
vi.mock('~/shared/services/http-client', () => ({
  get: (...args: any[]) => mockGet(...args),
  post: (...args: any[]) => mockPost(...args),
}));

// ---- Mock the core-bpm components used by FormBindingSection ----
// These are non-SDK helpers; we stub them to keep this test focused on the
// section's own contract (formRef wiring + collapsible state).
vi.mock('~/plugins/core-bpm/components/PagePickerSelect', () => ({
  PagePickerSelect: ({ value, onChange }: { value: string; onChange: (v: string) => void }) => (
    <input
      data-testid="mock-page-picker"
      value={value}
      onChange={(e) => onChange(e.target.value)}
    />
  ),
}));
vi.mock('~/plugins/core-bpm/components/VariableMappingEditor', () => ({
  VariableMappingEditor: () => <div data-testid="mock-variable-mapping" />,
}));
vi.mock('~/plugins/core-bpm/components/FieldPermissionMatrix', () => ({
  FieldPermissionMatrix: () => <div data-testid="mock-field-permission" />,
}));

import {
  NodeRegistry,
  useFlowStore,
} from '~/plugins/core-designer/components/flow-designer-sdk';
import {
  BPM_SDK_BATCH3_NODE_TYPES,
  buildBpmSdkBatch3NodeDefinitions,
  registerBpmSdkBatch3,
  registerBpmSdkAll,
  CallActivityNode,
  CallActivityEditor,
  BpmSequenceFlowEdgeEditor,
  MultiInstanceSection,
  FormBindingSection,
  HookConfigSection,
  AssigneePicker,
  ProcessPicker,
  // batch1/2 helpers for cross-batch JSON round-trip
  BPM_SDK_BATCH1_NODE_TYPES,
  BPM_SDK_BATCH2_NODE_TYPES,
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

describe('bpm-designer-sdk batch3 (B2b)', () => {
  beforeEach(() => {
    mockGet.mockReset();
    mockPost.mockReset();
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

    it('1) registerBpmSdkBatch3 registers callActivity', () => {
      registerBpmSdkBatch3(nodes);
      const registered = nodes
        .getAll()
        .map((d) => d.type)
        .sort();
      expect(registered).toEqual([...BPM_SDK_BATCH3_NODE_TYPES].sort());
    });

    it('2) callActivity exposes component + propertyEditor (G2)', () => {
      registerBpmSdkBatch3(nodes);
      const def = nodes.get('callActivity')!;
      expect(def).toBeDefined();
      expect(def.component).toBeDefined();
      expect(def.propertyEditor).toBeDefined();
      expect(def.category).toMatch(/^bpm\./);
      expect(def.defaultConfig).toMatchObject({ calledProcessVersion: 'latest' });
    });

    it('3) callActivity exposes minInputs:1, minOutputs:1', () => {
      registerBpmSdkBatch3(nodes);
      const def = nodes.get('callActivity')!;
      expect(def.validation).toMatchObject({ minInputs: 1, minOutputs: 1 });
    });

    it('3b) registerBpmSdkAll aggregates batch1+2+3 to 9 types', () => {
      registerBpmSdkAll(nodes);
      const all = nodes.getAll().map((d) => d.type);
      expect(all).toHaveLength(9);
      [
        ...BPM_SDK_BATCH1_NODE_TYPES,
        ...BPM_SDK_BATCH2_NODE_TYPES,
        ...BPM_SDK_BATCH3_NODE_TYPES,
      ].forEach((t) => expect(all).toContain(t));
    });

    it('3c) buildBpmSdkBatch3NodeDefinitions returns a stable single-item list', () => {
      const defs1 = buildBpmSdkBatch3NodeDefinitions();
      const defs2 = buildBpmSdkBatch3NodeDefinitions();
      expect(defs1.map((d) => d.type)).toEqual(defs2.map((d) => d.type));
      expect(defs1).toHaveLength(1);
    });
  });

  // ---------- CallActivityNode rendering ----------
  describe('CallActivityNode rendering', () => {
    it('4) renders ⊙ glyph + label', () => {
      renderNode(
        CallActivityNode,
        nodeProps({
          id: 'ca1',
          type: 'callActivity',
          data: { label: 'SubProcess', config: {} },
        }),
      );
      expect(screen.getByTestId('bpm-sdk-call-activity')).toBeInTheDocument();
      expect(screen.getByText('SubProcess')).toBeInTheDocument();
    });

    it('5) renders calledProcessKey subtitle when set', () => {
      renderNode(
        CallActivityNode,
        nodeProps({
          id: 'ca2',
          type: 'callActivity',
          data: { label: 'Sub', config: { calledProcessKey: 'order.fulfill' } },
        }),
      );
      expect(screen.getByText('order.fulfill')).toBeInTheDocument();
    });

    it('6) completed badge under monitor=completed', () => {
      act(() => {
        useFlowStore.getState().setMonitorMode(true);
        useFlowStore.getState().setMonitorData({
          ca3: { status: 'completed', updatedAt: Date.now() },
        });
      });
      renderNode(
        CallActivityNode,
        nodeProps({
          id: 'ca3',
          type: 'callActivity',
          data: { label: 'X', config: {} },
        }),
      );
      expect(screen.getByTestId('bpm-sdk-completed-badge')).toBeInTheDocument();
    });
  });

  // ---------- CallActivityEditor — G2 patches ----------
  describe('CallActivityEditor (G2 NodePropertyEditorProps)', () => {
    beforeEach(() => {
      // ProcessPicker fetches on mount; default to empty for these tests.
      mockGet.mockResolvedValue({ code: 0, data: [] });
    });

    it('7) description textarea emits {description} patch', () => {
      const patches: Array<Record<string, unknown>> = [];
      render(
        <CallActivityEditor
          nodeId="ca"
          config={{}}
          onChange={(p) => patches.push(p)}
        />,
      );
      fireEvent.change(screen.getByTestId('bpm-sdk-callactivity-description'), {
        target: { value: 'invoke fulfillment subprocess' },
      });
      expect(patches[patches.length - 1]).toEqual({
        description: 'invoke fulfillment subprocess',
      });
    });

    it('8) version mode select emits {calledProcessVersion} patch', () => {
      const patches: Array<Record<string, unknown>> = [];
      render(
        <CallActivityEditor
          nodeId="ca"
          config={{ calledProcessKey: 'x' }}
          onChange={(p) => patches.push(p)}
        />,
      );
      fireEvent.change(screen.getByTestId('bpm-sdk-callactivity-version-mode'), {
        target: { value: 'fixed' },
      });
      expect(patches[patches.length - 1]).toEqual({ calledProcessVersion: 'fixed' });
    });

    it('9) ProcessPicker selection emits {calledProcessKey} patch', async () => {
      mockGet.mockResolvedValueOnce({
        code: 0,
        data: [
          { pid: 'p1', processKey: 'order', processName: 'Order', version: 1, status: 'active' },
        ],
      });
      const patches: Array<Record<string, unknown>> = [];
      render(
        <CallActivityEditor
          nodeId="ca"
          config={{}}
          onChange={(p) => patches.push(p)}
        />,
      );
      await waitFor(() => {
        expect(
          screen.getByTestId('bpm-sdk-process-picker-select').querySelectorAll('option').length,
        ).toBeGreaterThan(1);
      });
      fireEvent.change(screen.getByTestId('bpm-sdk-process-picker-select'), {
        target: { value: 'order' },
      });
      expect(patches[patches.length - 1]).toEqual({ calledProcessKey: 'order' });
    });
  });

  // ---------- MultiInstanceSection ----------
  describe('MultiInstanceSection', () => {
    it('10) empty config: only collapsed toggle rendered', () => {
      render(<MultiInstanceSection onChange={() => {}} />);
      // toggle exists, but collapsed: no enabled checkbox in DOM
      expect(screen.getByTestId('bpm-sdk-mi-toggle')).toBeInTheDocument();
      expect(screen.queryByTestId('multiinstance-enabled')).not.toBeInTheDocument();
    });

    it('11) enabled=true exposes all 5 fields', () => {
      render(
        <MultiInstanceSection
          config={{ enabled: true, sequential: false }}
          onChange={() => {}}
        />,
      );
      expect(screen.getByTestId('multiinstance-enabled')).toBeInTheDocument();
      expect(screen.getByTestId('multiinstance-sequential')).toBeInTheDocument();
      expect(screen.getByTestId('multiinstance-collection')).toBeInTheDocument();
      expect(screen.getByTestId('multiinstance-element-variable')).toBeInTheDocument();
      expect(screen.getByTestId('multiinstance-completion-condition')).toBeInTheDocument();
      expect(screen.getByTestId('multiinstance-cardinality')).toBeInTheDocument();
    });

    it('12) sequential radio emits sequential:true', () => {
      const changes: any[] = [];
      render(
        <MultiInstanceSection
          config={{ enabled: true, sequential: false }}
          onChange={(c) => changes.push(c)}
        />,
      );
      fireEvent.click(screen.getByTestId('multiinstance-sequential'));
      const last = changes[changes.length - 1];
      expect(last.sequential).toBe(true);
      expect(last.enabled).toBe(true);
    });
  });

  // ---------- FormBindingSection ----------
  describe('FormBindingSection', () => {
    it('13) empty bindings: section starts collapsed', () => {
      render(<FormBindingSection bindings={[]} onChange={() => {}} />);
      // toggle exists but page-picker NOT in DOM
      expect(screen.getByTestId('form-binding-toggle')).toBeInTheDocument();
      expect(screen.queryByTestId('mock-page-picker')).not.toBeInTheDocument();
    });

    it('14) expanded with no formRef shows page-picker only', () => {
      render(<FormBindingSection bindings={[]} onChange={() => {}} />);
      fireEvent.click(screen.getByTestId('form-binding-toggle'));
      expect(screen.getByTestId('mock-page-picker')).toBeInTheDocument();
      expect(screen.queryByTestId('bpm-sdk-form-binding-save-strategy')).not.toBeInTheDocument();
    });

    it('15) clearing formRef yields onChange([])', () => {
      const changes: any[] = [];
      render(
        <FormBindingSection
          bindings={[
            {
              formRef: 'page.x',
              formType: 'page',
              saveStrategy: 'business_only',
              versionStrategy: 'latest',
              permissionMode: 'merge',
              builtinVariables: { decision: 'decision', comment: 'comment' },
            },
          ]}
          onChange={(b) => changes.push(b)}
        />,
      );
      // simulate page picker clearing the formRef
      fireEvent.change(screen.getByTestId('mock-page-picker'), { target: { value: '' } });
      expect(changes[changes.length - 1]).toEqual([]);
    });
  });

  // ---------- HookConfigSection ----------
  describe('HookConfigSection', () => {
    it('16) zero hooks: header text contains (0) and body collapsed', () => {
      render(<HookConfigSection hooks={[]} onChange={() => {}} />);
      const toggle = screen.getByTestId('hook-section-toggle');
      expect(toggle.textContent).toContain('(0)');
      expect(screen.queryByTestId('hook-section-body')).not.toBeInTheDocument();
    });

    it('17) addHook emits new pre_execute http_callback hook', () => {
      const changes: any[] = [];
      render(<HookConfigSection hooks={[]} onChange={(h) => changes.push(h)} />);
      fireEvent.click(screen.getByTestId('hook-section-toggle'));
      fireEvent.click(screen.getByTestId('hook-add-btn'));
      const next = changes[changes.length - 1];
      expect(next).toHaveLength(1);
      expect(next[0].hookType).toBe('pre_execute');
      expect(next[0].hookConfig.actionType).toBe('http_callback');
    });

    it('18) switching action type to script swaps the sub-config UI', () => {
      const changes: any[] = [];
      const initial = [
        {
          hookType: 'pre_execute',
          executionOrder: 0,
          hookConfig: { actionType: 'http_callback' },
          failStrategy: 'block',
          async: false,
          enabled: true,
        } as any,
      ];
      const { rerender } = render(
        <HookConfigSection hooks={initial} onChange={(h) => changes.push(h)} />,
      );
      expect(screen.getByTestId('hook-http-url')).toBeInTheDocument();
      fireEvent.change(screen.getByTestId('hook-action-type-0'), {
        target: { value: 'script' },
      });
      const next = changes[changes.length - 1];
      expect(next[0].hookConfig.actionType).toBe('script');
      // rerender with the new state so we can confirm UI swap
      rerender(<HookConfigSection hooks={next} onChange={(h) => changes.push(h)} />);
      expect(screen.queryByTestId('hook-http-url')).not.toBeInTheDocument();
    });
  });

  // ---------- AssigneePicker ----------
  describe('AssigneePicker (remote-data picker)', () => {
    it('19) loading state visible while fetcher pending', async () => {
      // never-resolve promise to keep loading state alive
      mockGet.mockReturnValue(new Promise(() => {}));
      render(<AssigneePicker type="role" value={[]} onChange={() => {}} />);
      fireEvent.click(screen.getByTestId('bpm-sdk-assignee-open'));
      // debounce 300ms — advance virtual timers not used; rely on waitFor.
      await waitFor(() =>
        expect(screen.getByTestId('bpm-sdk-assignee-loading')).toBeInTheDocument(),
      );
    });

    it('20) selecting an option toggles into value[]', async () => {
      mockGet.mockResolvedValue({
        code: 0,
        data: [{ pid: 'r1', name: 'Manager', code: 'mgr' }],
      });
      const changes: string[][] = [];
      render(<AssigneePicker type="role" value={[]} onChange={(v) => changes.push(v)} />);
      fireEvent.click(screen.getByTestId('bpm-sdk-assignee-open'));
      await waitFor(() =>
        expect(screen.getByTestId('bpm-sdk-assignee-option-r1')).toBeInTheDocument(),
      );
      fireEvent.click(screen.getByTestId('bpm-sdk-assignee-option-r1'));
      expect(changes[changes.length - 1]).toEqual(['r1']);
    });

    it('21) empty option list shows no-results empty state', async () => {
      mockGet.mockResolvedValue({ code: 0, data: [] });
      render(<AssigneePicker type="role" value={[]} onChange={() => {}} />);
      fireEvent.click(screen.getByTestId('bpm-sdk-assignee-open'));
      await waitFor(() =>
        expect(screen.getByTestId('bpm-sdk-assignee-empty')).toBeInTheDocument(),
      );
    });
  });

  // ---------- ProcessPicker ----------
  describe('ProcessPicker', () => {
    it('22) loading state visible until fetch resolves', () => {
      mockGet.mockReturnValue(new Promise(() => {}));
      render(<ProcessPicker value="" onChange={() => {}} />);
      const sel = screen.getByTestId('bpm-sdk-process-picker-select') as HTMLSelectElement;
      expect(sel.disabled).toBe(true);
    });

    it('23) loaded options populate select and search filters', async () => {
      mockGet.mockResolvedValueOnce({
        code: 0,
        data: [
          { pid: 'p1', processKey: 'order', processName: 'Order', version: 1, status: 'active' },
          { pid: 'p2', processKey: 'leave', processName: 'Leave', version: 2, status: 'active' },
        ],
      });
      render(<ProcessPicker value="" onChange={() => {}} />);
      await waitFor(() => {
        const sel = screen.getByTestId('bpm-sdk-process-picker-select');
        expect(sel.querySelectorAll('option').length).toBeGreaterThan(2);
      });
      // search filter
      fireEvent.change(screen.getByTestId('bpm-sdk-process-picker-search'), {
        target: { value: 'order' },
      });
      const sel = screen.getByTestId('bpm-sdk-process-picker-select');
      const visibleKeys = Array.from(sel.querySelectorAll('option'))
        .map((o) => (o as HTMLOptionElement).value)
        .filter(Boolean);
      expect(visibleKeys).toEqual(['order']);
    });

    it('24) fetch error → empty state (no crash)', async () => {
      mockGet.mockRejectedValueOnce(new Error('network'));
      render(<ProcessPicker value="" onChange={() => {}} />);
      await waitFor(() =>
        expect(screen.getByTestId('bpm-sdk-process-picker-empty')).toBeInTheDocument(),
      );
    });
  });

  // ---------- EdgeEditor ----------
  describe('BpmSequenceFlowEdgeEditor', () => {
    it('25) label input emits {label} patch', () => {
      const patches: any[] = [];
      render(
        <BpmSequenceFlowEdgeEditor
          edgeId="e1"
          data={{ label: '' } as any}
          onChange={(p) => patches.push(p)}
        />,
      );
      fireEvent.change(screen.getByTestId('bpm-sdk-edge-label-input'), {
        target: { value: 'approve' },
      });
      expect(patches[patches.length - 1]).toEqual({ label: 'approve' });
    });

    it('26) condition body present + default checkbox emits {isDefault}', () => {
      const patches: any[] = [];
      render(
        <BpmSequenceFlowEdgeEditor
          edgeId="e1"
          data={{ label: '' } as any}
          onChange={(p) => patches.push(p)}
        />,
      );
      // ConditionExpressionBody mounts
      expect(screen.getByTestId('bpm-sdk-condition-editor')).toBeInTheDocument();
      fireEvent.click(screen.getByTestId('bpm-sdk-edge-default-checkbox'));
      expect(patches[patches.length - 1]).toEqual({ isDefault: true });
    });

    it('27) edge data round-trips through patch sequence', () => {
      let current: any = { label: '', isDefault: false };
      const onChange = (patch: any) => {
        current = { ...current, ...patch };
      };
      const { rerender } = render(
        <BpmSequenceFlowEdgeEditor edgeId="e" data={current} onChange={onChange} />,
      );
      fireEvent.change(screen.getByTestId('bpm-sdk-edge-label-input'), {
        target: { value: 'reject' },
      });
      rerender(<BpmSequenceFlowEdgeEditor edgeId="e" data={current} onChange={onChange} />);
      fireEvent.click(screen.getByTestId('bpm-sdk-edge-default-checkbox'));
      expect(current).toMatchObject({ label: 'reject', isDefault: true });
    });
  });

  // ---------- JSON round-trip ----------
  describe('cross-batch JSON round-trip', () => {
    it('28) 9-node hybrid graph (batch1+2+3) exports/imports loss-free', () => {
      const data = {
        nodes: [
          { id: 'n1', type: 'startEvent', position: { x: 0, y: 0 }, data: { label: 'S' } },
          { id: 'n2', type: 'endEvent', position: { x: 100, y: 0 }, data: { label: 'E' } },
          { id: 'n3', type: 'parallelGateway', position: { x: 50, y: 0 }, data: {} },
          { id: 'n4', type: 'serviceTask', position: { x: 60, y: 0 }, data: {} },
          { id: 'n5', type: 'exclusiveGateway', position: { x: 70, y: 0 }, data: {} },
          { id: 'n6', type: 'inclusiveGateway', position: { x: 80, y: 0 }, data: {} },
          { id: 'n7', type: 'receiveTask', position: { x: 90, y: 0 }, data: {} },
          { id: 'n8', type: 'userTask', position: { x: 110, y: 0 }, data: {} },
          {
            id: 'n9',
            type: 'callActivity',
            position: { x: 120, y: 0 },
            data: {
              label: 'Sub',
              config: { calledProcessKey: 'sub.x', calledProcessVersion: 'fixed' },
            },
          },
        ],
        edges: [],
      };
      act(() => {
        useFlowStore.getState().importData(data as any);
      });
      const exported = useFlowStore.getState().exportData();
      const types = exported.nodes.map((n: any) => n.type).sort();
      expect(types).toEqual(
        [
          'callActivity',
          'endEvent',
          'exclusiveGateway',
          'inclusiveGateway',
          'parallelGateway',
          'receiveTask',
          'serviceTask',
          'startEvent',
          'userTask',
        ].sort(),
      );
      const ca = exported.nodes.find((n: any) => n.id === 'n9')!;
      expect((ca.data as any).config.calledProcessKey).toBe('sub.x');
      expect((ca.data as any).config.calledProcessVersion).toBe('fixed');
    });
  });
});
