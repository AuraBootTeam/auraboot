/**
 * BPMNToolbar deploy-button regression test.
 *
 * Guards the UI-first "create new → save → deploy" flow:
 * after the save call resolves and writes the server-assigned id into
 * useBPMNStore.processDefinition, the deploy button must transition from
 * disabled to enabled without requiring a page reload with `?pid=`.
 *
 * This depends on setProcessDefinition writing response.pid back into
 * store.processDefinition.id (verified in useBPMNStore.test.ts).
 */

import React from 'react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, act } from '@testing-library/react';

import { BPMNToolbar } from '~/plugins/core-designer/components/bpmn-designer/components/BPMNToolbar';
import { useBPMNStore } from '~/plugins/core-designer/components/bpmn-designer/store/useBPMNStore';
import {
  BPMNNodeType,
  type BPMNNode,
  type BPMNProcessDefinition,
} from '~/plugins/core-designer/components/bpmn-designer/types';

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (key: string) => key }),
}));

function rfNode(id: string, type: BPMNNodeType): BPMNNode {
  return {
    id,
    type,
    position: { x: 0, y: 0 },
    // Simulate React Flow-enriched fields; the store strips them.
    measured: { width: 120, height: 60 },
    handleBounds: { source: [], target: [] },
    data: { type, label: id, config: { name: id } },
  } as unknown as BPMNNode;
}

describe('BPMNToolbar deploy button', () => {
  beforeEach(() => {
    useBPMNStore.getState().reset();
  });

  function renderToolbar() {
    return render(
      <BPMNToolbar
        processName="P"
        processKey="p"
        onProcessNameChange={() => {}}
        onProcessKeyChange={() => {}}
        onSave={() => {}}
        onValidate={() => {}}
        onImport={() => {}}
        onExport={() => {}}
        onDeploy={() => {}}
        onMonitorToggle={() => {}}
      />,
    );
  }

  it('is disabled before save (no processDefinition.id)', () => {
    // No processDefinition set.
    renderToolbar();
    const btn = screen.getByTestId('bpmn-btn-deploy');
    expect(btn).toBeDisabled();
  });

  it('enables after save writes pid back into the store', () => {
    renderToolbar();
    const btn = screen.getByTestId('bpmn-btn-deploy');
    expect(btn).toBeDisabled();

    // Simulate the save() handler writing the API response into the store.
    const saved: BPMNProcessDefinition = {
      id: 'pd-abc-123', // backend assigns pid; service maps pid → id
      name: 'P',
      key: 'p',
      version: 1,
      status: 'draft',
      nodes: [rfNode('start', BPMNNodeType.START_EVENT)],
      edges: [],
    };
    act(() => {
      useBPMNStore.getState().setProcessDefinition(saved);
    });

    // isDirty cleared + id present → button enabled.
    expect(useBPMNStore.getState().processDefinition?.id).toBe('pd-abc-123');
    expect(useBPMNStore.getState().isDirty).toBe(false);
    expect(btn).not.toBeDisabled();
  });
});
