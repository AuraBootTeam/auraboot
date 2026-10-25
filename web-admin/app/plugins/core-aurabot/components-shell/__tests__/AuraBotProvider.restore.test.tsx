import React, { useEffect } from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuraBotProvider, useAuraBot } from '../AuraBotProvider';
import { auraBotApi } from '../../services/auraBotApi';
const router = vi.hoisted(() => ({ params: {}, location: { pathname: '/p/c/ai_colleague_chat', search: '', hash: '', state: null, key: 'test' } }));
vi.mock('react-router', async () => ({ ...await vi.importActual('react-router'), useLocation: () => router.location, useParams: () => router.params }));
vi.mock('~/contexts/ApplicationCapabilitiesContext', () => ({ useApplicationCapabilities: () => ({ agent: 'installed' }) }));
vi.mock('../../services/auraBotApi', () => ({ auraBotApi: { listConversations: vi.fn(), getConversationMessages: vi.fn() } }));
function Chat() {
  const context = useAuraBot();
  useEffect(() => { context.setSelectedAgent('writer'); }, [context.setSelectedAgent]);
  useEffect(() => {
    if (context.state.selectedAgentCode === 'writer') void context.restoreAgentSession?.('writer');
  }, [context.state.selectedAgentCode, context.restoreAgentSession]);
  return <><output data-testid="chat-state">{JSON.stringify(context.state)}</output>
    <button onClick={() => context.setSelectedAgent('writer')}>Select same colleague</button>
    <button onClick={context.newSession}>New conversation</button></>;
}
const summary = { conversationId: 51, agentCode: 'writer', title: 'Write conversation', messageCount: 1 };
const history = [{ id: 81, senderType: 'human', content: 'Persisted approval request', createdAt: '2026-10-24T00:00:00Z' }];
beforeEach(() => {
  localStorage.clear(); vi.clearAllMocks(); localStorage.setItem('aurabot.lastConversationId', '51');
  vi.mocked(auraBotApi.listConversations).mockResolvedValue([summary] as any);
  vi.mocked(auraBotApi.getConversationMessages).mockResolvedValue(history as any);
});
describe('full-page colleague history restoration', () => {
  it('restores the remembered same-agent conversation while the global panel stays collapsed', async () => {
    render(<AuraBotProvider><Chat /></AuraBotProvider>);
    await waitFor(() => expect(screen.getByTestId('chat-state')).toHaveTextContent('Persisted approval request'));
    expect(screen.getByTestId('chat-state')).toHaveTextContent('"panelState":"collapsed"');
    fireEvent.click(screen.getByText('Select same colleague'));
    expect(screen.getByTestId('chat-state')).toHaveTextContent('Persisted approval request');
  });
  it('does not restore a remembered conversation belonging to another colleague', async () => {
    vi.mocked(auraBotApi.listConversations).mockResolvedValue([{ ...summary, agentCode: 'another-agent' }] as any);
    render(<AuraBotProvider><Chat /></AuraBotProvider>);
    await waitFor(() => expect(auraBotApi.listConversations).toHaveBeenCalled());
    expect(auraBotApi.getConversationMessages).not.toHaveBeenCalled();
    expect(screen.getByTestId('chat-state')).not.toHaveTextContent('Persisted approval request');
  });
  it('ignores delayed history after the user starts a new conversation', async () => {
    let resolveHistory!: (value: any) => void;
    vi.mocked(auraBotApi.getConversationMessages).mockImplementation(() => new Promise(resolve => { resolveHistory = resolve; }));
    render(<AuraBotProvider><Chat /></AuraBotProvider>);
    await waitFor(() => expect(auraBotApi.getConversationMessages).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByText('New conversation'));
    await act(async () => { resolveHistory(history); });
    expect(screen.getByTestId('chat-state')).not.toHaveTextContent('Persisted approval request');
    expect(screen.getByTestId('chat-state')).toHaveTextContent('"currentConversationId":null');
  });
});
