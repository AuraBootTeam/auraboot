import React from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuraBotProvider, useAuraBot } from '../AuraBotProvider';
import { auraBotApi } from '../../services/auraBotApi';
const router = vi.hoisted(() => ({ params: {}, location: { pathname: '/', search: '', hash: '', state: null, key: 'test' } }));
vi.mock('react-router', async () => ({ ...await vi.importActual('react-router'), useLocation: () => router.location, useParams: () => router.params }));
vi.mock('~/contexts/ApplicationCapabilitiesContext', () => ({ useApplicationCapabilities: () => ({ agent: 'installed' }) }));
vi.mock('../../services/auraBotApi', () => ({ auraBotApi: { ensureConversation: vi.fn(), listConversations: vi.fn(), chatStream: vi.fn(), executeStream: vi.fn() } }));
function Chat() {
  const c = useAuraBot();
  return <><output data-testid="state">{JSON.stringify(c.state)}</output>
    <button onClick={() => void c.sendMessage('Create record')}>Propose</button>
    <button onClick={() => void c.confirmTool('decision')}>Approve</button>
    <button onClick={() => void c.cancelTool('decision')}>Cancel</button></>;
}
let callbacks: any;
let finish!: () => void;
beforeEach(() => {
  localStorage.clear(); vi.clearAllMocks(); callbacks = undefined;
  vi.mocked(auraBotApi.ensureConversation).mockResolvedValue({ conversationId: 81, agentCode: 'default', title: 'Approval' } as any);
  vi.mocked(auraBotApi.listConversations).mockResolvedValue([]);
  vi.mocked(auraBotApi.chatStream).mockImplementation(async (_request, c) => {
    c.onConfirmRequired?.('decision', 'agent_approval_gate', 'Review', { input: { name: 'Record' } }, '01APPROVAL');
    c.onDone?.('');
  });
  vi.mocked(auraBotApi.executeStream).mockImplementation((_request, c) => {
    callbacks = c; return new Promise<void>(resolve => { finish = resolve; });
  });
});
async function propose() {
  render(<AuraBotProvider><Chat /></AuraBotProvider>);
  fireEvent.click(screen.getByText('Propose'));
  await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('confirm_card'));
}
describe('approval decision feedback', () => {
  it('renders the resumed structured result before any history reload', async () => {
    await propose(); fireEvent.click(screen.getByText('Approve'));
    await waitFor(() => expect(auraBotApi.executeStream).toHaveBeenCalled());
    await act(async () => {
      callbacks.onResultContract?.({ outputType: 'structured_result', actionability: 'write', status: 'success',
        skillCode: 'aurabot:model:create', renderHint: 'card', data: { data: { displayName: 'Ledger', modelCode: 'ledger' } } });
      callbacks.onDone('', 'trace-result'); finish();
    });
    const messages = JSON.parse(screen.getByTestId('state').textContent || '{}').messages;
    expect(messages.filter((message: any) => message.type === 'result_contract')).toHaveLength(1);
    expect(messages.find((message: any) => message.type === 'result_contract').resultContract.data.data.displayName).toBe('Ledger');
  });

  it('replaces the same pending tool instead of duplicating its decision feedback', async () => {
    vi.mocked(auraBotApi.chatStream).mockImplementation(async (_request, c) => {
      c.onToolStart?.('decision', 'aurabot_model_create', { code: 'approval_visual_case' });
      c.onToolResult?.('decision', { success: false, approvalRequired: true }, false);
      c.onConfirmRequired?.('decision', 'aurabot_model_create', 'Review', { code: 'approval_visual_case' },
        '01APPROVAL', { _aurabot_skill: true, riskLevel: 'HIGH', preview: { code: 'approval_visual_case' } });
      c.onDone?.('');
    });
    render(<AuraBotProvider><Chat /></AuraBotProvider>);
    fireEvent.click(screen.getByText('Propose'));
    await waitFor(() => expect(screen.getByTestId('state')).toHaveTextContent('skill_preview_card'));
    const pending = JSON.parse(screen.getByTestId('state').textContent || '{}').messages.filter((m: any) => m.toolId === 'decision');
    expect(pending).toHaveLength(1);
    expect(pending[0].riskLevel).toBe('HIGH');
  });

  it('keeps unknown server diagnostics out of the approval UI', async () => {
    await propose(); fireEvent.click(screen.getByText('Cancel'));
    await waitFor(() => expect(auraBotApi.executeStream).toHaveBeenCalled());
    await act(async () => { callbacks.onError('SQL exception in ab_agent_approval pid=01HIDDEN'); finish(); });
    expect(screen.getByTestId('state')).toHaveTextContent('本次审批未完成，请刷新后检查状态或联系管理员。');
    expect(screen.getByTestId('state')).not.toHaveTextContent('SQL exception');
    expect(screen.getByTestId('state')).not.toHaveTextContent('01HIDDEN');
  });

  it('waits for server acknowledgement before reporting a successful cancellation', async () => {
    await propose(); fireEvent.click(screen.getByText('Cancel'));
    await waitFor(() => expect(auraBotApi.executeStream).toHaveBeenCalled());
    expect(screen.getByTestId('state')).not.toHaveTextContent('操作已取消');
    await act(async () => { callbacks.onDone(''); finish(); });
    expect(screen.getByTestId('state')).toHaveTextContent('操作已取消');
    expect(screen.getByTestId('state')).toHaveTextContent('"isLoading":false');
  });
  it('does not claim cancellation when the server rejects the approver', async () => {
    await propose(); fireEvent.click(screen.getByText('Cancel'));
    await waitFor(() => expect(auraBotApi.executeStream).toHaveBeenCalled());
    await act(async () => { callbacks.onError('Insufficient permission to decide this approval'); finish(); });
    expect(screen.getByTestId('state')).toHaveTextContent('你无权处理这项审批，请联系指定审批人。');
    expect(screen.getByTestId('state')).not.toHaveTextContent('操作已取消');
    expect(screen.getByTestId('state')).not.toHaveTextContent('Insufficient permission');
  });
  it('clears executing status and hides internal approval identity when an approval has expired', async () => {
    await propose(); fireEvent.click(screen.getByText('Approve'));
    await waitFor(() => expect(auraBotApi.executeStream).toHaveBeenCalled());
    await act(async () => { callbacks.onError('Approval already processed or expired: 01HIDDEN'); finish(); });
    expect(screen.getByTestId('state')).toHaveTextContent('该审批已过期或已处理，请刷新后重新查看。');
    expect(screen.getByTestId('state')).not.toHaveTextContent('01HIDDEN');
    const messages = JSON.parse(screen.getByTestId('state').textContent || '{}').messages;
    expect(messages.filter((message: any) => message.type === 'error')).toHaveLength(1);
    expect(messages.find((message: any) => message.toolId === 'decision')?.type).toBe('error');
    expect(messages.some((message: any) => message.type === 'tool_cancelled')).toBe(false);
    expect(screen.getByTestId('state')).not.toHaveTextContent('正在执行...');
    expect(screen.getByTestId('state')).not.toHaveTextContent('"type":"tool_executed"');
    expect(screen.getByTestId('state')).toHaveTextContent('"isLoading":false');
  });
});
