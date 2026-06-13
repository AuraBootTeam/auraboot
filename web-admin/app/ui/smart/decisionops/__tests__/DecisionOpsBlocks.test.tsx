import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DecisionTableWorkbenchBlock } from '../DecisionTableWorkbenchBlock';
import { ExecutionLogTraceBlock } from '../ExecutionLogTraceBlock';
import { DecisionRolloutMonitorBlock } from '../DecisionRolloutMonitorBlock';
import { DecisionDefinitionCatalogBlock } from '../DecisionDefinitionCatalogBlock';
import { DecisionModelFieldCatalogBlock } from '../DecisionModelFieldCatalogBlock';
import { StatusBadge } from '../DecisionOpsBlockUtils';

const { mockGet } = vi.hoisted(() => ({
  mockGet: vi.fn(),
}));

vi.mock('~/shared/services/ApiService', () => ({
  getApiService: () => ({
    get: mockGet,
  }),
}));

describe('DecisionOps custom blocks', () => {
  beforeEach(() => {
    mockGet.mockReset();
  });

  it('runs the DMN table preview from edited sample JSON', () => {
    render(<DecisionTableWorkbenchBlock />);

    fireEvent.change(screen.getByLabelText('hit-policy'), { target: { value: 'UNIQUE' } });
    fireEvent.change(screen.getByLabelText('测试上下文'), {
      target: { value: '{"record":{"priority":"LOW","amount":20}}' },
    });
    fireEvent.click(screen.getByRole('button', { name: '预览' }));

    expect(screen.getByText('已命中')).toBeInTheDocument();
    expect(screen.getByText('{"result":"NORMAL","deadlineHours":24}')).toBeInTheDocument();
  });

  it('does not call a recent logs endpoint before traceId is provided', () => {
    mockGet.mockResolvedValue({ success: true, data: { records: [] } });

    render(<ExecutionLogTraceBlock />);

    expect(screen.getByText(/输入 traceId 后查询执行日志/)).toBeInTheDocument();
    expect(mockGet).not.toHaveBeenCalled();
  });

  it('queries execution logs by traceId and renders the returned record', async () => {
    mockGet.mockResolvedValue({
      success: true,
      data: {
        records: [
          {
            pid: 'log-1',
            traceId: 'trace-123',
            decisionCode: 'complaint_sla_deadline',
            status: 'SUCCESS',
            latencyMs: 12,
          },
        ],
      },
    });

    render(<ExecutionLogTraceBlock />);
    fireEvent.change(screen.getByPlaceholderText('输入 traceId'), {
      target: { value: 'trace-123' },
    });

    await waitFor(() => {
      expect(mockGet).toHaveBeenCalledWith(
        '/decision/logs',
        { traceId: 'trace-123' },
        expect.any(AbortSignal),
      );
    });
    expect(await screen.findByText('complaint_sla_deadline')).toBeInTheDocument();
  });

  it('renders rollout API failures as an in-page error state', async () => {
    mockGet.mockResolvedValue({ success: false, message: 'rollout API unavailable', code: '1' });

    render(<DecisionRolloutMonitorBlock />);

    expect(await screen.findByText('数据加载失败')).toBeInTheDocument();
    expect(screen.getByText('rollout API unavailable')).toBeInTheDocument();
  });

  it('renders rollout statuses as operator-facing labels instead of raw enum codes', () => {
    render(<StatusBadge value="CANDIDATE_READY" />);

    expect(screen.getByText('候选就绪')).toBeInTheDocument();
    expect(screen.queryByText('CANDIDATE_READY')).not.toBeInTheDocument();
  });

  it('renders decision definition catalog API failures inside the DSL page block', async () => {
    mockGet.mockResolvedValue({ success: false, message: 'definition API unavailable', code: '1' });

    render(<DecisionDefinitionCatalogBlock />);

    expect(await screen.findByText('决策定义目录')).toBeInTheDocument();
    expect(screen.getByText('definition API unavailable')).toBeInTheDocument();
  });

  it('renders model field catalog API failures inside the DSL page block', async () => {
    mockGet.mockResolvedValue({ success: false, message: 'field API unavailable', code: '1' });

    render(<DecisionModelFieldCatalogBlock />);

    expect(await screen.findByText('决策字段目录')).toBeInTheDocument();
    expect(screen.getByText('field API unavailable')).toBeInTheDocument();
  });
});
