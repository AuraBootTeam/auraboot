import React, { useEffect } from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuraBotProvider, useAuraBot } from '../AuraBotProvider';
import { auraBotApi } from '../../services/auraBotApi';
const router = vi.hoisted(() => ({ location: { pathname: '/', search: '' }, params: {} }));
vi.mock('react-router', async () => ({ ...await vi.importActual('react-router'), useLocation: () => router.location, useParams: () => router.params }));
vi.mock('~/contexts/ApplicationCapabilitiesContext', () => ({ useApplicationCapabilities: () => ({ agent: 'installed' }) }));
vi.mock('../../services/auraBotApi', () => ({ auraBotApi: { listConversations: vi.fn(), getConversationMessages: vi.fn(), getConversationExecution: vi.fn(), chatStream: vi.fn() } }));
const first = [{id:1,conversationId:51,seq:1,sender:'user',type:'text',content:'Persisted request',createdAt:'2026-11-04T00:00:00Z'}];
const completed = [...first,{id:2,conversationId:51,seq:2,sender:'assistant',type:'text',content:'Persisted final answer',createdAt:'2026-11-04T00:01:00Z'}];
function Chat() {
 const c=useAuraBot();
 useEffect(()=>{c.setSelectedAgent('writer');},[c.setSelectedAgent]);
 useEffect(()=>{if(c.state.selectedAgentCode==='writer')void c.restoreAgentSession('writer');},[c.state.selectedAgentCode,c.restoreAgentSession]);
 return <><output data-testid="state">{JSON.stringify({ ...c.state,execution:c.executionState })}</output><button onClick={()=>c.sendMessage('Do not overlap')}>Send</button><button onClick={c.reloadExecutionState}>Reload status</button><button onClick={c.newSession}>New session</button></>;
}
beforeEach(()=>{
 vi.clearAllMocks();localStorage.clear();localStorage.setItem('aurabot.lastConversationId','51');
 vi.mocked(auraBotApi.listConversations).mockResolvedValue([{conversationId:51,agentCode:'writer',title:'Owned'}] as any);
 vi.mocked(auraBotApi.getConversationMessages).mockResolvedValue(first as any);
});
describe('executing conversation recovery',()=>{
 it('blocks new messages while the remote turn executes, then restores persisted history without replay',async()=>{
  vi.mocked(auraBotApi.getConversationExecution).mockResolvedValueOnce({state:'running'}).mockResolvedValue({state:'idle'});
  render(<AuraBotProvider><Chat /></AuraBotProvider>);
  await waitFor(()=>expect(screen.getByTestId('state')).toHaveTextContent('"execution":"running"'));
  fireEvent.click(screen.getByText('Send'));expect(auraBotApi.chatStream).not.toHaveBeenCalled();
  vi.mocked(auraBotApi.getConversationMessages).mockResolvedValue(completed as any);
  await waitFor(()=>expect(screen.getByTestId('state')).toHaveTextContent('Persisted final answer'),{timeout:2500});
  expect(screen.getByTestId('state')).toHaveTextContent('"isLoading":false');
  expect(auraBotApi.chatStream).not.toHaveBeenCalled();
 });
 it('fails closed when status cannot be read and permits an explicit read-only retry',async()=>{
  vi.mocked(auraBotApi.getConversationExecution).mockRejectedValueOnce(new Error('offline')).mockResolvedValue({state:'idle'});
  render(<AuraBotProvider><Chat /></AuraBotProvider>);
  await waitFor(()=>expect(screen.getByTestId('state')).toHaveTextContent('"execution":"unavailable"'));
  fireEvent.click(screen.getByText('Send'));expect(auraBotApi.chatStream).not.toHaveBeenCalled();
  fireEvent.click(screen.getByText('Reload status'));
  await waitFor(()=>expect(screen.getByTestId('state')).toHaveTextContent('"execution":"idle"'));
 });
 it('does not hydrate the old result after an explicit new conversation',async()=>{
  let finish!: (v:{state:'idle'})=>void;
  vi.mocked(auraBotApi.getConversationExecution).mockResolvedValueOnce({state:'running'}).mockImplementation(()=>new Promise(resolve=>{finish=resolve;}));
  render(<AuraBotProvider><Chat /></AuraBotProvider>);
  await waitFor(()=>expect(auraBotApi.getConversationExecution).toHaveBeenCalledTimes(2),{timeout:2500});
  fireEvent.click(screen.getByText('New session'));
  await act(async()=>{finish({state:'idle'});});
  expect(screen.getByTestId('state')).not.toHaveTextContent('Persisted final answer');
  expect(screen.getByTestId('state')).toHaveTextContent('"currentConversationId":null');
 });
});
