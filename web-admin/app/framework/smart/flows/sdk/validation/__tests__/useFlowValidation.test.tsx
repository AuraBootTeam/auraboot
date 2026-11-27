import { describe, it, expect, beforeEach, vi } from 'vitest';
import { renderHook, act } from '@testing-library/react';

vi.mock('~/utils/i18n', () => ({
  useSmartText: () => (k: unknown) => (typeof k === 'string' ? k : ''),
}));

import { useFlowStore } from '../../store/useFlowStore';
import { nodeRegistry } from '../../nodes/NodeRegistry';
import { useFlowValidation } from '../useFlowValidation';
import type { FlowData } from '../../store/types';

const triggerDef = {
  type: 'trigger',
  label: 'Trigger',
  icon: '',
  category: 'trigger',
  configSchema: [{ key: 'modelCode', label: 'Model', type: 'model' as const, required: true }],
};

function load(data: FlowData) {
  act(() => useFlowStore.getState().importData(data));
}

describe('useFlowValidation', () => {
  beforeEach(() => {
    useFlowStore.getState().reset();
    nodeRegistry.clear();
    nodeRegistry.registerAll([triggerDef]);
  });

  it('validate() returns invalid, sets store result, and selects the errored node', () => {
    load({
      nodes: [{ id: 't1', type: 'trigger', position: { x: 0, y: 0 }, data: { label: 't1', config: {} } }],
      edges: [],
    });

    const { result } = renderHook(() => useFlowValidation());
    let res!: ReturnType<typeof result.current.validate>;
    act(() => {
      res = result.current.validate();
    });

    expect(res.valid).toBe(false);
    expect(useFlowStore.getState().validationResult?.errors[0]).toMatchObject({
      nodeId: 't1',
      fieldKey: 'modelCode',
    });
    expect(useFlowStore.getState().selectedNodeId).toBe('t1');
  });

  it('validate() clears a stale result and returns valid when config is complete', () => {
    load({
      nodes: [
        { id: 't1', type: 'trigger', position: { x: 0, y: 0 }, data: { label: 't1', config: { modelCode: 'crm_lead' } } },
      ],
      edges: [],
    });
    act(() =>
      useFlowStore.getState().setValidationResult({
        valid: false,
        errors: [{ nodeId: 't1', fieldKey: 'modelCode', message: 'stale', type: 'error' }],
      }),
    );

    const { result } = renderHook(() => useFlowValidation());
    let res!: ReturnType<typeof result.current.validate>;
    act(() => {
      res = result.current.validate();
    });

    expect(res.valid).toBe(true);
    expect(useFlowStore.getState().validationResult).toBeNull();
  });
});
