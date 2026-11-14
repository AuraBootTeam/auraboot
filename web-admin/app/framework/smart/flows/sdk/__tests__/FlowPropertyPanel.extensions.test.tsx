import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import React from 'react';

// Keep the panel hermetic: i18n passthrough, stub heavy/unused deps.
vi.mock('~/utils/i18n', () => ({
  useSmartText: () => (k: unknown) => (typeof k === 'string' ? k : ''),
}));
vi.mock('~/utils/confirmDialog', () => ({ confirmDialog: vi.fn() }));
vi.mock('../core/PropertyField', () => ({ PropertyField: () => null }));

import { useFlowStore } from '../store/useFlowStore';
import { nodeRegistry } from '../nodes/NodeRegistry';
import { edgeRegistry } from '../edges/EdgeRegistry';
import { FlowPropertyPanel } from '../core/FlowPropertyPanel';
import type { NodePropertyEditorProps } from '../nodes/types';
import type { EdgePropertyEditorProps } from '../edges/types';

function CustomGatewayEditor({ onChange }: NodePropertyEditorProps) {
  return (
    <div>
      <span data-testid="custom-node-editor">bespoke gateway editor</span>
      <button type="button" onClick={() => onChange({ touched: true })}>
        patch
      </button>
    </div>
  );
}

function BespokeEdgeEditor({ onChange }: EdgePropertyEditorProps) {
  return (
    <button
      type="button"
      data-testid="bespoke-edge-editor"
      onClick={() => onChange({ condition: { type: 'expression', content: 'injected' } })}
    >
      edge editor
    </button>
  );
}

describe('FlowPropertyPanel extension points (G1 + G2)', () => {
  beforeEach(() => {
    useFlowStore.getState().reset();
    nodeRegistry.clear();
    edgeRegistry.clear();
  });

  it('G2: a node with propertyEditor renders the bespoke editor instead of configSchema', () => {
    nodeRegistry.register({
      type: 'exclusiveGateway',
      label: 'Gateway',
      icon: '',
      category: 'gateway',
      propertyEditor: CustomGatewayEditor,
    });
    const id = useFlowStore.getState().addNode({
      type: 'exclusiveGateway',
      position: { x: 0, y: 0 },
      data: { label: 'GW', config: {} },
    });
    act(() => useFlowStore.getState().selectNode(id));

    render(<FlowPropertyPanel />);
    expect(screen.getByTestId('custom-node-editor')).toBeTruthy();
  });

  it('renders string lucide icon names as icons rather than raw codes in the panel header', () => {
    nodeRegistry.register({
      type: 'action-send-webhook',
      label: '发送 Webhook',
      icon: 'Send',
      category: 'action',
    });
    const id = useFlowStore.getState().addNode({
      type: 'action-send-webhook',
      position: { x: 0, y: 0 },
      data: { label: '发送 Webhook', config: {} },
    });
    act(() => useFlowStore.getState().selectNode(id));

    render(<FlowPropertyPanel />);

    expect(screen.queryByText('Send')).toBeNull();
    expect(screen.getByText('发送 Webhook').closest('.p-4')?.querySelector('svg')).not.toBeNull();
  });

  it('renders provider availability metadata in the selected node inspector', () => {
    nodeRegistry.register({
      type: 'action-send-sms',
      label: '发送短信',
      icon: 'MessageSquareText',
      category: 'action',
      metadata: {
        availability: {
          unavailable: true,
          reason: '当前环境未配置真实短信 provider',
          providerSummary: '依赖：真实短信 provider · 未配置',
          source: 'decision-action-catalog',
          actionType: 'SEND_SMS',
        },
      },
    });
    const id = useFlowStore.getState().addNode({
      type: 'action-send-sms',
      position: { x: 0, y: 0 },
      data: { label: '发送短信', config: { actionType: 'send_sms' } },
    });
    act(() => useFlowStore.getState().selectNode(id));

    render(<FlowPropertyPanel />);

    expect(screen.getByTestId(`flow-node-availability-badge-${id}`)).toHaveTextContent(
      '不可用',
    );
    expect(screen.getByTestId(`flow-node-availability-${id}`)).toHaveTextContent(
      '当前环境未配置真实短信 provider',
    );
    expect(screen.getByTestId(`flow-node-availability-${id}`)).toHaveTextContent(
      '依赖：真实短信 provider · 未配置',
    );
  });

  it('G1: selecting an edge renders the built-in condition editor and edits the condition', () => {
    const a = useFlowStore.getState().addNode({
      type: 'a',
      position: { x: 0, y: 0 },
      data: { label: 'A', config: {} },
    });
    const b = useFlowStore.getState().addNode({
      type: 'b',
      position: { x: 100, y: 0 },
      data: { label: 'B', config: {} },
    });
    const e = useFlowStore.getState().addEdge({ source: a, target: b });
    act(() => useFlowStore.getState().selectEdge(e));

    render(<FlowPropertyPanel />);
    const textarea = screen.getByPlaceholderText('e.g. amount > 1000') as HTMLTextAreaElement;
    fireEvent.change(textarea, { target: { value: 'amount > 1000' } });

    const edge = useFlowStore.getState().edges.find((x) => x.id === e)!;
    expect(edge.data?.condition?.content).toBe('amount > 1000');
    expect(edge.data?.condition?.type).toBe('expression');
  });

  it('G1: a registered edge editor (injection) replaces the built-in one', () => {
    edgeRegistry.register({ type: 'conditional', editor: BespokeEdgeEditor });
    const a = useFlowStore.getState().addNode({
      type: 'a',
      position: { x: 0, y: 0 },
      data: { label: 'A', config: {} },
    });
    const b = useFlowStore.getState().addNode({
      type: 'b',
      position: { x: 100, y: 0 },
      data: { label: 'B', config: {} },
    });
    const e = useFlowStore.getState().addEdge({ source: a, target: b, type: 'conditional' });
    act(() => useFlowStore.getState().selectEdge(e));

    render(<FlowPropertyPanel />);
    const btn = screen.getByTestId('bespoke-edge-editor');
    fireEvent.click(btn);

    const edge = useFlowStore.getState().edges.find((x) => x.id === e)!;
    expect(edge.data?.condition?.content).toBe('injected');
  });

  it('exposes grouped property toggles for collapsed advanced fields', () => {
    nodeRegistry.register({
      type: 'timer',
      label: 'Timer',
      icon: '',
      category: 'trigger',
      configSchema: [
        { key: 'modelCode', label: 'Model', type: 'text', group: 'trigger_source' },
        { key: 'inactivityField', label: 'Date field', type: 'text', group: 'advanced' },
      ],
    });
    const id = useFlowStore.getState().addNode({
      type: 'timer',
      position: { x: 0, y: 0 },
      data: { label: 'Timer', config: {} },
    });
    act(() => useFlowStore.getState().selectNode(id));

    render(<FlowPropertyPanel />);

    const advancedToggle = screen.getByTestId('prop-group-toggle-advanced');
    expect(advancedToggle).toHaveAttribute('aria-expanded', 'false');

    fireEvent.click(advancedToggle);

    expect(advancedToggle).toHaveAttribute('aria-expanded', 'true');
  });
});
