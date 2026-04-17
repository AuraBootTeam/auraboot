/**
 * BpmHistorySection.test.tsx
 *
 * Unit tests for the Task 14 history section:
 *   - null instance → component renders nothing, listAuditEvents is not invoked,
 *   - empty audit result → explicit empty testid surfaces,
 *   - heterogeneous events render in createdAt-descending order with the
 *     correct known-operation labels,
 *   - unknown operation degrades to raw string (no silent translation),
 *   - listAuditEvents rejection → error testid with the message.
 *
 * `listAuditEvents` is mocked so the component can be tested in isolation
 * without requiring a running backend or an I18nProvider.
 */

import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';

const listAuditEventsMock = vi.fn();

vi.mock('~/plugins/core-bpm/services/bpmWorkbenchService', async () => {
  const actual = await vi.importActual<
    typeof import('~/plugins/core-bpm/services/bpmWorkbenchService')
  >('~/plugins/core-bpm/services/bpmWorkbenchService');
  return {
    ...actual,
    listAuditEvents: (...args: unknown[]) => listAuditEventsMock(...args),
  };
});

import { BpmHistorySection } from '../BpmHistorySection';
import type {
  BpmAuditEvent,
  BpmInstanceForRecord,
} from '~/plugins/core-bpm/services/bpmWorkbenchService';

// Identity translator: surfaces the Chinese fallback copy.
const t = (_key: string, _params?: Record<string, unknown>, fallback?: string): string =>
  fallback ?? _key;

function buildInstance(overrides: Partial<BpmInstanceForRecord> = {}): BpmInstanceForRecord {
  return {
    instanceId: 'pi-history-1',
    processDefinitionId: 'leave_request',
    status: 'running',
    currentNodes: [],
    completedNodes: [],
    variables: {},
    ...overrides,
  };
}

function buildEvent(overrides: Partial<BpmAuditEvent> = {}): BpmAuditEvent {
  return {
    id: 1,
    pid: 'aud-ulid-01',
    userId: 'user-001',
    operation: 'process_start',
    processInstanceId: 'pi-history-1',
    taskId: null,
    processDefinitionKey: 'leave_request',
    version: 1,
    details: null,
    ipAddress: '127.0.0.1',
    result: 'success',
    errorMessage: null,
    createdAt: '2026-04-17T10:00:00Z',
    ...overrides,
  };
}

beforeEach(() => {
  listAuditEventsMock.mockReset();
});

afterEach(() => {
  document.body.innerHTML = '';
});

describe('BpmHistorySection', () => {
  it('renders nothing and does not fetch when instance is null', () => {
    const { container } = render(<BpmHistorySection instance={null} t={t} />);

    expect(container.firstChild).toBeNull();
    expect(listAuditEventsMock).not.toHaveBeenCalled();
  });

  it('renders the empty state when the audit trail is empty', async () => {
    listAuditEventsMock.mockResolvedValue([]);

    render(<BpmHistorySection instance={buildInstance()} t={t} />);

    await waitFor(() => {
      expect(screen.getByTestId('bpm-history-empty')).toBeInTheDocument();
    });
    expect(screen.getByTestId('bpm-history-empty')).toHaveTextContent('暂无审批记录');
    expect(listAuditEventsMock).toHaveBeenCalledTimes(1);
    expect(listAuditEventsMock).toHaveBeenCalledWith('pi-history-1');
    expect(screen.queryByTestId('bpm-history-container')).toBeNull();
  });

  it('renders events sorted newest-first with the correct operation labels and comment detail', async () => {
    listAuditEventsMock.mockResolvedValue([
      buildEvent({
        id: 101,
        operation: 'process_start',
        createdAt: '2026-04-17T09:00:00Z',
        userId: 'applicant-1',
      }),
      buildEvent({
        id: 102,
        operation: 'task_approve',
        createdAt: '2026-04-17T10:00:00Z',
        userId: 'manager-1',
        details: { comment: 'Looks good to me' },
      }),
      buildEvent({
        id: 103,
        operation: 'task_reject',
        createdAt: '2026-04-17T11:00:00Z',
        userId: 'director-1',
        details: { reason: 'Insufficient budget' },
      }),
    ]);

    render(<BpmHistorySection instance={buildInstance()} t={t} />);

    await waitFor(() => {
      expect(screen.getByTestId('bpm-history-container')).toBeInTheDocument();
    });

    const items = screen
      .getAllByTestId(/^bpm-history-event-\d+$/)
      // ignore the -label / -failure testids that are nested inside.
      .filter((el: HTMLElement) =>
        /^bpm-history-event-\d+$/.test(el.getAttribute('data-testid') ?? ''),
      );

    // Newest first: reject (11:00) → approve (10:00) → start (09:00).
    expect(items).toHaveLength(3);
    expect(items[0]).toHaveAttribute('data-operation', 'task_reject');
    expect(items[1]).toHaveAttribute('data-operation', 'task_approve');
    expect(items[2]).toHaveAttribute('data-operation', 'process_start');

    // Known labels surface from the i18n fallback map.
    expect(screen.getByTestId('bpm-history-event-101-label')).toHaveTextContent('启动流程');
    expect(screen.getByTestId('bpm-history-event-102-label')).toHaveTextContent('审批通过');
    expect(screen.getByTestId('bpm-history-event-103-label')).toHaveTextContent('驳回');

    // Structured details render for approve (comment) and reject (reason).
    expect(items[1]).toHaveTextContent('Looks good to me');
    expect(items[0]).toHaveTextContent('Insufficient budget');

    // Operator ids render raw (no name resolution yet).
    expect(items[0]).toHaveTextContent('director-1');
    expect(items[1]).toHaveTextContent('manager-1');
    expect(items[2]).toHaveTextContent('applicant-1');
  });

  it('falls back to raw operation string for unknown operation values', async () => {
    listAuditEventsMock.mockResolvedValue([
      buildEvent({
        id: 201,
        operation: 'weird_custom',
        createdAt: '2026-04-17T12:00:00Z',
      }),
    ]);

    render(<BpmHistorySection instance={buildInstance()} t={t} />);

    await waitFor(() => {
      expect(screen.getByTestId('bpm-history-event-201')).toBeInTheDocument();
    });

    const label = screen.getByTestId('bpm-history-event-201-label');
    // Raw backend operation surfaces verbatim - no silent translation,
    // no remap to a known operation.
    expect(label).toHaveTextContent('weird_custom');
    expect(label).not.toHaveTextContent('启动流程');
    expect(label).not.toHaveTextContent('审批通过');
  });

  it('surfaces the error state when listAuditEvents rejects', async () => {
    listAuditEventsMock.mockRejectedValue(new Error('audit service down'));

    render(<BpmHistorySection instance={buildInstance()} t={t} />);

    await waitFor(() => {
      expect(screen.getByTestId('bpm-history-error')).toBeInTheDocument();
    });
    expect(screen.getByTestId('bpm-history-error')).toHaveTextContent('audit service down');
    expect(screen.queryByTestId('bpm-history-container')).toBeNull();
  });

  it('renders a failure marker when the event result is not success', async () => {
    listAuditEventsMock.mockResolvedValue([
      buildEvent({
        id: 301,
        operation: 'task_approve',
        result: 'failure',
        errorMessage: 'SmartEngine threw OptimisticLockException',
        createdAt: '2026-04-17T13:00:00Z',
      }),
    ]);

    render(<BpmHistorySection instance={buildInstance()} t={t} />);

    await waitFor(() => {
      expect(screen.getByTestId('bpm-history-event-301-failure')).toBeInTheDocument();
    });
    expect(screen.getByTestId('bpm-history-event-301-failure')).toHaveTextContent(
      'SmartEngine threw OptimisticLockException',
    );
  });
});
