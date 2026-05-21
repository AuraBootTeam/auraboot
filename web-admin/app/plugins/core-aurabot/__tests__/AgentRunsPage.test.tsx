/**
 * AgentRunsPage.test.tsx
 *
 * Pins the Replay UI MVP list-page wiring against the AgentRunController
 * REST contract:
 *   - rendersListWithRows: 3 rows render after API responds
 *   - clickRow_opensDetailDrawer: clicking a row triggers the detail GET
 *     and shows drawer sections
 *   - filterByStatus_callsApiWithStatusParam: changing the status filter
 *     adds ?status=... to the next list call
 *   - paginationNextPage_callsApiWithPageParam: clicking "Next" bumps page
 *     in the list call
 *
 * Uses vi.mock on the api client module (no MSW dependency) and a
 * MemoryRouter shell so useSearchParams works under jsdom.
 */

import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

// ---- Mock api client ------------------------------------------------------
// vitest.setup.ts globally stubs useSearchParams to a no-op pair, which would
// freeze the page's URL-state filters. Re-mock react-router locally so
// useSearchParams is backed by real React state for this spec. (Per-file
// vi.mock overrides the setup-level mock for this module graph.)
const { listAgentRunsMock, getAgentRunDetailMock, getAgentRunRuntimeOpsMock } = vi.hoisted(() => ({
  listAgentRunsMock: vi.fn(),
  getAgentRunDetailMock: vi.fn(),
  getAgentRunRuntimeOpsMock: vi.fn(),
}));

vi.mock('../services/agentRunsApi', () => ({
  listAgentRuns: (...args: unknown[]) => listAgentRunsMock(...args),
  getAgentRunDetail: (...args: unknown[]) => getAgentRunDetailMock(...args),
  getAgentRunRuntimeOps: (...args: unknown[]) => getAgentRunRuntimeOpsMock(...args),
}));

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router');
  return actual;
});

import { MemoryRouter } from 'react-router';

import AgentRunsPage from '../pages/admin/agent-runs';

const SAMPLE_RUNS = [
  {
    runId: '01RUN0000000001',
    agentCode: 'aurabot',
    runStatus: 'succeeded',
    parentRunId: null,
    subtaskOrigin: null,
    costUsd: 0.0123,
    durationMs: 4321,
    createdAt: new Date(Date.now() - 60_000).toISOString(),
    completedAt: new Date(Date.now() - 30_000).toISOString(),
    intentSummary: 'fetch sales',
  },
  {
    runId: '01RUN0000000002',
    agentCode: 'aurabot',
    runStatus: 'failed',
    parentRunId: '01RUN0000000001',
    subtaskOrigin: 'spawn',
    costUsd: 0.05,
    durationMs: 1200,
    createdAt: new Date(Date.now() - 90_000).toISOString(),
    completedAt: null,
    intentSummary: 'analyze',
  },
  {
    runId: '01RUN0000000003',
    agentCode: 'planner',
    runStatus: 'running',
    parentRunId: null,
    subtaskOrigin: null,
    costUsd: null,
    durationMs: null,
    createdAt: new Date().toISOString(),
    completedAt: null,
    intentSummary: null,
  },
];

const PAGE_RESPONSE = {
  items: SAMPLE_RUNS,
  total: 45, // > PAGE_SIZE (20) so "Next" is enabled
  page: 0,
  size: 20,
};

const DETAIL_RESPONSE = {
  run: SAMPLE_RUNS[0],
  actions: [
    {
      pid: 'ACT001',
      resultContractId: 'rc-ACT001',
      stepIndex: 1,
      toolCallIndex: null,
      actionCode: 'sales.query',
      actionType: 'tool_call',
      intentSummary: 'list sales',
      targetModel: 'crm_account',
      targetRecordId: 'REC-PID-001',
      targetRecordPid: 'REC-PID-001',
      beforeSnapshot: null,
      afterSnapshot: null,
      fieldChanges: null,
      commandCode: null,
      commandResult: null,
      riskLevel: null,
      estimatedRisk: null,
      riskDeviation: null,
      reversalMode: null,
      actionStatus: 'succeeded',
      errorMessage: null,
      costUsd: 0.001,
      tokenUsage: 100,
      fidelity: null,
      skillCode: 'sales.list',
      parallelGroupId: null,
      parallelIndex: null,
      executedAt: new Date().toISOString(),
    },
  ],
  interruptLog: [],
  childRuns: [],
  bif: {
    pid: 'BIF001',
    intent: 'list sales',
    primaryObject: 'Sale',
    confidence: '{"score":0.9}',
    dispatchedSkill: 'sales.list',
    channel: 'chat',
  },
  traceId: null,
  conversationTurn: null,
  resultContracts: [],
};

const RUNTIME_OPS_RESPONSE = {
  runId: SAMPLE_RUNS[0].runId,
  limit: 20,
  summary: {
    approvalPending: 1,
    approvalTerminal: 2,
    pendingToolRunning: 1,
    pendingToolSucceeded: 3,
    pendingToolFailed: 0,
    durableRunning: 1,
    durableSucceeded: 4,
    durableFailed: 1,
    durableCompensationRequired: 1,
    durableCompensated: 2,
    checkpointCount: 5,
  },
  approvals: [
    {
      approvalPid: 'APPROVAL-1',
      runId: SAMPLE_RUNS[0].runId,
      approvalStatus: 'pending',
      createdAt: new Date().toISOString(),
    },
  ],
  pendingToolExecutions: [
    {
      executionKey: 'pending-key-1',
      status: 'RUNNING',
      commandCode: 'agent.pending_tool_execution:TOOL-1',
      createdAt: new Date().toISOString(),
    },
  ],
  pendingToolExecutionScope: 'tenant',
  durableToolExecutions: [
    {
      executionKey: 'durable-key-1',
      status: 'COMPENSATION_REQUIRED',
      commandCode: 'agent.tool_execution:RUN-1',
      outcome: '{"compensationReason":"not retryable"}',
      createdAt: new Date().toISOString(),
    },
  ],
  checkpoints: [
    {
      checkpointId: 'CP-1',
      checkpointType: 'PLAN',
      createdAt: new Date().toISOString(),
    },
  ],
};

function renderPage(initialEntries: string[] = ['/admin/agent-runs']) {
  return render(
    <MemoryRouter initialEntries={initialEntries}>
      <AgentRunsPage />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  listAgentRunsMock.mockReset();
  getAgentRunDetailMock.mockReset();
  getAgentRunRuntimeOpsMock.mockReset();
  listAgentRunsMock.mockResolvedValue(PAGE_RESPONSE);
  getAgentRunDetailMock.mockResolvedValue(DETAIL_RESPONSE);
  getAgentRunRuntimeOpsMock.mockResolvedValue(RUNTIME_OPS_RESPONSE);
});

afterEach(() => {
  document.body.innerHTML = '';
});

describe('AgentRunsPage', () => {
  it('rendersListWithRows', async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByTestId('runs-table')).toBeInTheDocument();
    });
    for (const r of SAMPLE_RUNS) {
      expect(screen.getByTestId(`run-row-${r.runId}`)).toBeInTheDocument();
    }
    // Status badges render
    expect(screen.getByTestId(`status-badge-${SAMPLE_RUNS[0].runId}`)).toHaveTextContent(
      'succeeded',
    );
  });

  it('clickRow_opensDetailDrawer', async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByTestId(`run-row-${SAMPLE_RUNS[0].runId}`)).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId(`run-row-${SAMPLE_RUNS[0].runId}`));

    await waitFor(() => {
      expect(getAgentRunDetailMock).toHaveBeenCalledWith(SAMPLE_RUNS[0].runId);
    });
    await waitFor(() => {
      expect(screen.getByTestId('drawer-section-metadata')).toBeInTheDocument();
    });
    expect(screen.getByTestId('drawer-section-actions')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('action-toggle-ACT001'));
    expect(screen.getByTestId('action-target-ACT001')).toHaveTextContent('Target PID');
    expect(screen.getByTestId('action-target-ACT001')).toHaveTextContent('REC-PID-001');
    expect(screen.getByTestId('drawer-section-interrupts')).toBeInTheDocument();
    expect(screen.getByTestId('drawer-section-child-runs')).toBeInTheDocument();
    expect(screen.getByTestId('drawer-section-bif')).toBeInTheDocument();
  });

  it('clickRow_loadsRuntimeDiagnostics', async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByTestId(`run-row-${SAMPLE_RUNS[0].runId}`)).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId(`run-row-${SAMPLE_RUNS[0].runId}`));

    await waitFor(() => {
      expect(getAgentRunRuntimeOpsMock).toHaveBeenCalledWith(SAMPLE_RUNS[0].runId, 20);
    });
    await waitFor(() => {
      expect(screen.getByTestId('drawer-section-runtime-diagnostics')).toBeInTheDocument();
    });
    expect(screen.getByTestId('runtime-summary-approvalPending')).toHaveTextContent('1');
    expect(screen.getByTestId('runtime-summary-pendingToolRunning')).toHaveTextContent('1');
    expect(screen.getByTestId('runtime-summary-durableCompensationRequired')).toHaveTextContent('1');
    expect(screen.getByTestId('runtime-summary-checkpointCount')).toHaveTextContent('5');
    expect(screen.getByTestId('runtime-row-approval-APPROVAL-1')).toHaveTextContent('pending');
    expect(screen.getByTestId('runtime-row-pending-pending-key-1')).toHaveTextContent('RUNNING');
    expect(screen.getByTestId('runtime-row-durable-durable-key-1')).toHaveTextContent(
      'COMPENSATION_REQUIRED',
    );
  });

  it('runtimeDiagnosticsFailure_keepsDrawerUsable', async () => {
    getAgentRunRuntimeOpsMock.mockRejectedValueOnce(new Error('runtime ops unavailable'));
    renderPage();
    await waitFor(() => {
      expect(screen.getByTestId(`run-row-${SAMPLE_RUNS[0].runId}`)).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId(`run-row-${SAMPLE_RUNS[0].runId}`));

    await waitFor(() => {
      expect(screen.getByTestId('drawer-section-metadata')).toBeInTheDocument();
    });
    await waitFor(() => {
      expect(screen.getByTestId('runtime-diagnostics-error')).toHaveTextContent(
        'runtime ops unavailable',
      );
    });
    expect(screen.getByTestId('drawer-section-actions')).toBeInTheDocument();
  });

  it('filterByStatus_callsApiWithStatusParam', async () => {
    renderPage();
    await waitFor(() => {
      expect(listAgentRunsMock).toHaveBeenCalled();
    });
    listAgentRunsMock.mockClear();

    fireEvent.change(screen.getByTestId('filter-status'), { target: { value: 'failed' } });

    await waitFor(() => {
      const lastCall = listAgentRunsMock.mock.calls.at(-1)?.[0];
      expect(lastCall).toBeDefined();
      expect(lastCall.status).toBe('failed');
    });
  });

  it('paginationNextPage_callsApiWithPageParam', async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByTestId('page-next')).toBeInTheDocument();
    });
    listAgentRunsMock.mockClear();

    fireEvent.click(screen.getByTestId('page-next'));

    await waitFor(() => {
      const lastCall = listAgentRunsMock.mock.calls.at(-1)?.[0];
      expect(lastCall).toBeDefined();
      expect(lastCall.page).toBe(1);
    });
  });
});
