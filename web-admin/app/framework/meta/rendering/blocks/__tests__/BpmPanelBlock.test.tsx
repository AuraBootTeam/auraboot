/**
 * BpmPanelBlock.test.tsx
 *
 * Verifies the Task 10 skeleton for the `bpm-panel` detail block:
 *   - loading / empty / error / ready state transitions,
 *   - section placeholder rendering based on config.sections,
 *   - businessKeyField resolution precedence.
 *
 * The underlying `bpmWorkbenchService` module is fully mocked so this remains
 * a pure unit test.
 */

import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, act } from '@testing-library/react';

const getInstanceForRecordMock = vi.fn();

vi.mock('~/plugins/core-bpm/services/bpmWorkbenchService', () => ({
  getInstanceForRecord: (...args: unknown[]) => getInstanceForRecordMock(...args),
}));

// BpmDiagramSection pulls in @xyflow/react which requires browser-only APIs
// (ResizeObserver, DOMMatrix) that jsdom does not ship. The panel-level test
// only cares that the diagram slot gets mounted with the instance; its
// internals have dedicated coverage in BpmDiagramSection.test.tsx.
vi.mock('~/plugins/core-bpm/components/panel/BpmDiagramSection', () => ({
  BpmDiagramSection: ({ instance }: { instance: { instanceId: string } | null }) => (
    <div data-testid="bpm-diagram-stub" data-instance-id={instance?.instanceId ?? ''} />
  ),
}));

// BpmOperationsSection depends on useAuth + loads pending tasks via a second
// backend call; its behaviour is covered by BpmOperationsSection.test.tsx. At
// the panel level we only care that the slot is mounted with the resolved
// instance and that the `onActionComplete` callback wire triggers a reload.
vi.mock('~/plugins/core-bpm/components/panel/BpmOperationsSection', () => ({
  BpmOperationsSection: ({
    instance,
  }: {
    instance: { instanceId: string } | null;
    onActionComplete?: () => void;
  }) => (
    <div
      data-testid="bpm-operations-stub"
      data-instance-id={instance?.instanceId ?? ''}
    />
  ),
}));

// BpmHistorySection issues its own audit-trail fetch on mount, which would
// break these unit tests by calling `listAuditEvents` against a partial
// bpmWorkbenchService mock. Its behaviour is covered by
// BpmHistorySection.test.tsx; at the panel level we only need to assert the
// slot is mounted with the resolved instance.
vi.mock('~/plugins/core-bpm/components/panel/BpmHistorySection', () => ({
  BpmHistorySection: ({ instance }: { instance: { instanceId: string } | null }) => (
    <div data-testid="bpm-history-stub" data-instance-id={instance?.instanceId ?? ''} />
  ),
}));

import { BpmPanelBlock } from '../BpmPanelBlock';

const READY_INSTANCE = {
  instanceId: 'pi-123',
  processDefinitionId: 'pd-abc',
  status: 'running',
  currentNodes: [],
  completedNodes: [],
  variables: {},
};

describe('BpmPanelBlock', () => {
  beforeEach(() => {
    getInstanceForRecordMock.mockReset();
  });

  // Vitest config uses `isolate: false` so DOM state leaks across tests
  // unless we reset the body between runs. Testing Library's named `cleanup`
  // export is not surfaced in the installed `@types` so we reset the body
  // directly - same pattern as BpmStatusSection.test.tsx.
  afterEach(() => {
    document.body.innerHTML = '';
  });

  it('renders the loading state while the instance request is pending', async () => {
    // Never resolve — the component should stay on the loading placeholder.
    let _resolve: ((value: typeof READY_INSTANCE | null) => void) | undefined;
    getInstanceForRecordMock.mockImplementation(
      () =>
        new Promise<typeof READY_INSTANCE | null>((resolve) => {
          _resolve = resolve;
        }),
    );

    render(
      <BpmPanelBlock
        block={{ blockType: 'bpm-panel' } as any}
        record={{ id: '42' }}
        recordId="42"
      />,
    );

    const panel = screen.getByTestId('bpm-panel');
    expect(panel).toHaveAttribute('data-state', 'loading');
    expect(screen.queryByTestId('bpm-section-status')).toBeNull();
  });

  it('renders the empty state when no instance exists for the business key', async () => {
    getInstanceForRecordMock.mockResolvedValue(null);

    render(
      <BpmPanelBlock
        block={{ blockType: 'bpm-panel' } as any}
        record={{ id: '42' }}
        recordId="42"
      />,
    );

    await waitFor(() => {
      const panel = screen.getByTestId('bpm-panel');
      expect(panel).toHaveAttribute('data-state', 'empty');
    });
    expect(screen.queryByTestId('bpm-section-status')).toBeNull();
  });

  it('renders all four section placeholders when an instance is present and no sections config given', async () => {
    getInstanceForRecordMock.mockResolvedValue(READY_INSTANCE);

    render(
      <BpmPanelBlock
        block={{ blockType: 'bpm-panel' } as any}
        record={{ id: 'rec-1' }}
        recordId="rec-1"
      />,
    );

    await waitFor(() => {
      const panel = screen.getByTestId('bpm-panel');
      expect(panel).toHaveAttribute('data-state', 'ready');
      expect(panel).toHaveAttribute('data-process-instance-id', 'pi-123');
    });
    expect(screen.getByTestId('bpm-section-status')).toBeInTheDocument();
    expect(screen.getByTestId('bpm-section-diagram')).toBeInTheDocument();
    expect(screen.getByTestId('bpm-section-operations')).toBeInTheDocument();
    expect(screen.getByTestId('bpm-section-history')).toBeInTheDocument();
  });

  it('only renders sections listed in config.sections', async () => {
    getInstanceForRecordMock.mockResolvedValue(READY_INSTANCE);

    render(
      <BpmPanelBlock
        block={{ blockType: 'bpm-panel', bpmPanel: { sections: ['status'] } } as any}
        record={{ id: 'rec-1' }}
        recordId="rec-1"
      />,
    );

    await waitFor(() => {
      expect(screen.getByTestId('bpm-section-status')).toBeInTheDocument();
    });
    expect(screen.queryByTestId('bpm-section-diagram')).toBeNull();
    expect(screen.queryByTestId('bpm-section-operations')).toBeNull();
    expect(screen.queryByTestId('bpm-section-history')).toBeNull();
  });

  it('renders the error state when getInstanceForRecord rejects', async () => {
    getInstanceForRecordMock.mockRejectedValue(new Error('boom'));

    render(
      <BpmPanelBlock
        block={{ blockType: 'bpm-panel' } as any}
        record={{ id: 'rec-1' }}
        recordId="rec-1"
      />,
    );

    await waitFor(() => {
      const panel = screen.getByTestId('bpm-panel');
      expect(panel).toHaveAttribute('data-state', 'error');
      expect(panel.textContent).toContain('boom');
    });
  });

  it('uses businessKeyField from record when provided, falls back to recordId otherwise', async () => {
    getInstanceForRecordMock.mockResolvedValue(READY_INSTANCE);

    // Case 1: businessKeyField present on record
    const { unmount } = render(
      <BpmPanelBlock
        block={{ blockType: 'bpm-panel', bpmPanel: { businessKeyField: 'orderNo' } } as any}
        record={{ id: '42', orderNo: 'ORDER-7' }}
        recordId="42"
      />,
    );
    await waitFor(() => {
      expect(getInstanceForRecordMock).toHaveBeenCalledWith('ORDER-7', undefined);
    });
    unmount();
    getInstanceForRecordMock.mockClear();

    // Case 2: no businessKeyField → recordId is used verbatim
    render(
      <BpmPanelBlock
        block={{ blockType: 'bpm-panel' } as any}
        record={{ id: '42' }}
        recordId="42"
      />,
    );
    await waitFor(() => {
      expect(getInstanceForRecordMock).toHaveBeenCalledWith('42', undefined);
    });
  });
});
