import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ExecutionLogTraceBlock } from '../ExecutionLogTraceBlock';

const routerMocks = vi.hoisted(() => ({
  navigate: vi.fn(),
}));
const http = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  delete: vi.fn(),
}));

vi.mock('react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router')>();
  return {
    ...actual,
    useNavigate: () => routerMocks.navigate,
  };
});

vi.mock('~/shared/services/ApiService', () => ({
  getApiService: () => http,
}));

const recentLog = {
  pid: 'log-1',
  traceId: 'trace-1',
  decisionCode: 'sla_deadline',
  selectedVersion: 2,
  status: 'MATCHED',
  callerType: 'AUTOMATION',
  callerRef: 'policy_1',
  rolloutArm: 'CANDIDATE',
  rolloutBucket: 12,
  durationMs: 18,
  createdAt: '2026-06-10T10:00:00Z',
  matchedRulesJson: [{ ruleId: 'R-101' }],
};

function mockLogApi() {
  http.get.mockImplementation((endpoint: string, params?: Record<string, unknown>) => {
    if (endpoint === '/decision/logs/recent') {
      return Promise.resolve({
        data: {
          records: [recentLog],
          total: 1,
          size: params?.size ?? 50,
          current: 1,
          pages: 1,
        },
      });
    }
    if (endpoint === '/decision/logs') {
      return Promise.resolve({
        data: [
          {
            ...recentLog,
            pid: 'log-0',
            decisionCode: 'eligibility_gate',
            status: 'NOT_MATCHED',
            createdAt: '2026-06-10T09:59:00Z',
          },
          recentLog,
        ],
      });
    }
    if (endpoint === '/decision/logs/log-1') {
      return Promise.resolve({ data: recentLog });
    }
    return Promise.resolve({ data: {} });
  });
}

describe('ExecutionLogTraceBlock', () => {
  beforeEach(() => {
    routerMocks.navigate.mockReset();
    http.get.mockReset();
    http.post.mockReset();
    http.delete.mockReset();
    mockLogApi();
  });

  it('loads DSL list logs with URL policyCode as keyword and applies advanced filters', async () => {
    render(
      <MemoryRouter initialEntries={['/p/decisionops_execution_logs?policyCode=policy_1']}>
        <ExecutionLogTraceBlock block={{ props: { mode: 'list', pageSize: 50 } }} />
      </MemoryRouter>,
    );

    await waitFor(() =>
      expect(http.get).toHaveBeenCalledWith('/decision/logs/recent', expect.objectContaining({
        keyword: 'policy_1',
        page: 0,
        size: 50,
      })),
    );
    expect(await screen.findByTestId('elta-row-log-1')).toHaveTextContent('sla_deadline');

    fireEvent.change(screen.getByLabelText('log-status'), { target: { value: 'MATCHED' } });
    fireEvent.change(screen.getByLabelText('log-caller-type'), { target: { value: 'AUTOMATION' } });
    fireEvent.change(screen.getByLabelText('log-matched'), { target: { value: 'true' } });
    fireEvent.change(screen.getByLabelText('log-rollout-arm'), { target: { value: 'CANDIDATE' } });
    fireEvent.change(screen.getByLabelText('log-min-duration'), { target: { value: '10' } });
    fireEvent.click(screen.getByTestId('elta-apply'));

    await waitFor(() =>
      expect(http.get).toHaveBeenLastCalledWith('/decision/logs/recent', expect.objectContaining({
        keyword: 'policy_1',
        status: 'MATCHED',
        callerType: 'AUTOMATION',
        matched: true,
        rolloutArm: 'CANDIDATE',
        minDurationMs: 10,
      })),
    );
  });

  it('renders a fixed execution log table with stable column definitions and truncated cells', async () => {
    render(
      <MemoryRouter initialEntries={['/p/decisionops_execution_logs']}>
        <ExecutionLogTraceBlock block={{ props: { mode: 'list' } }} />
      </MemoryRouter>,
    );

    const row = await screen.findByTestId('elta-row-log-1');
    const table = row.closest('table');

    expect(table).toHaveClass('elta-table');
    expect(table?.querySelectorAll('colgroup col')).toHaveLength(9);
    expect(table?.querySelector('.elta-col-actions')).toBeTruthy();
    expect(row.querySelectorAll('.elta-cell-text').length).toBeGreaterThanOrEqual(7);
  });

  it('opens a trace chain drawer without returning to the old console', async () => {
    render(
      <MemoryRouter initialEntries={['/p/decisionops_execution_logs']}>
        <ExecutionLogTraceBlock block={{ props: { mode: 'list' } }} />
      </MemoryRouter>,
    );

    fireEvent.click(await screen.findByTestId('elta-open-trace-log-1'));

    await waitFor(() =>
      expect(http.get).toHaveBeenCalledWith('/decision/logs', { traceId: 'trace-1' }),
    );
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('eligibility_gate');
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('R-101');
    expect(routerMocks.navigate).not.toHaveBeenCalledWith('/decision-ops');
  });

  it('loads a DSL detail route by pid and expands the same trace chain', async () => {
    render(
      <MemoryRouter initialEntries={['/p/decisionops_execution_logs/view/log-1']}>
        <Routes>
          <Route
            path="/p/decisionops_execution_logs/view/:recordPid"
            element={<ExecutionLogTraceBlock block={{ props: { mode: 'detail' } }} />}
          />
        </Routes>
      </MemoryRouter>,
    );

    await waitFor(() => expect(http.get).toHaveBeenCalledWith('/decision/logs/log-1', undefined));
    await waitFor(() =>
      expect(http.get).toHaveBeenCalledWith('/decision/logs', { traceId: 'trace-1' }),
    );
    expect(screen.getByTestId('elta-trace-drawer')).toHaveTextContent('sla_deadline');
  });
});
