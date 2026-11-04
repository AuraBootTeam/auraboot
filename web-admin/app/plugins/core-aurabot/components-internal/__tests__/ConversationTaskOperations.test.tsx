/** Hermetic UI contract tests; live SQL and browser evidence are separate. */
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { ConversationTaskOperations } from '../ConversationTaskOperations';
const { get } = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('~/shared/services/http-client', () => ({ get }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'zh-CN' }) }));
const task = {
  taskPid: 'task1',
  title: '核对客户验收',
  status: 'completed',
  agentCode: 'delivery',
  agentName: '项目交付同事',
  costStatus: 'unknown',
  usageCalls: 2,
  inputTokens: 1200,
  outputTokens: 45,
  approvalCount: 1,
  knownAmount: 0,
};
const page = { records: [task], total: 1, offset: 0, limit: 20, unparseableMetadataCount: 1 };
function show(allowed = true) {
  return render(
    <MemoryRouter>
      <ConversationTaskOperations allowed={allowed} />
    </MemoryRouter>,
  );
}
beforeEach(() => {
  get.mockReset();
  get.mockResolvedValue({ code: '0', data: page });
});
afterEach(cleanup);
it('makes no request without audit permission', () => {
  show(false);
  expect(screen.getByRole('status')).toHaveTextContent('运行审计权限');
  expect(get).not.toHaveBeenCalled();
});
it('qualifies unknown costs and incomplete metadata, then opens actual linked receipts', async () => {
  show();
  await screen.findByText(task.title);
  expect(screen.getByText('费用未知')).toBeVisible();
  expect(screen.queryByText('USD 0.000000')).not.toBeInTheDocument();
  expect(screen.getByRole('status')).toHaveTextContent('不代表完整任务历史');
  get.mockResolvedValueOnce({
    code: '0',
    data: {
      task: { ...task, agentPid: 'agent1', conversationId: 'conv1' },
      traces: [{ traceId: 'trace1', status: 'success', durationMs: 45 }],
      approvals: [{ pid: 'p1', status: 'approved', type: 'tool' }],
      actions: [{ pid: 'a1', status: 'succeeded', command: 'tpm:create_task' }],
      truncated: false,
    },
  });
  fireEvent.click(screen.getByText('联查回执'));
  expect(await screen.findByText('已批准', { exact: false })).toBeVisible();
  expect(screen.getByRole('link', { name: '查看调用 1' })).toHaveAttribute(
    'href',
    '/aurabot/traces/trace1',
  );
  expect(screen.getByRole('link', { name: '打开我有权访问的会话' })).toHaveAttribute(
    'href',
    '/p/c/ai_colleague_chat?agentPid=agent1&conversationId=conv1',
  );
  expect(screen.getByText('tpm:create_task').parentElement).toHaveTextContent('已完成');
});
it('hides stale rows on failed refresh and exposes an explicit read-only retry', async () => {
  show();
  await screen.findByText(task.title);
  get.mockRejectedValueOnce(new Error('offline'));
  fireEvent.click(screen.getByText('刷新记录'));
  await screen.findByRole('alert');
  expect(screen.queryByText(task.title)).not.toBeInTheDocument();
  fireEvent.click(screen.getByText('刷新记录'));
  await screen.findByText(task.title);
  expect(get).toHaveBeenCalledTimes(3);
});
it('sends exact search and pagination parameters and renders a genuine empty result', async () => {
  show();
  await screen.findByText(task.title);
  get.mockResolvedValueOnce({ code: '0', data: { records: [], total: 0, offset: 0, limit: 20 } });
  fireEvent.change(screen.getByRole('textbox'), { target: { value: '不存在' } });
  fireEvent.click(screen.getByRole('button', { name: '查询' }));
  await waitFor(() =>
    expect(get).toHaveBeenLastCalledWith('/api/admin/agent-runs/conversation-tasks', {
      query: '不存在',
      offset: 0,
      limit: 20,
    }),
  );
  expect(await screen.findByRole('status')).toHaveTextContent('没有匹配');
});
