import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApplicationCapabilitiesProvider, AgentRouteBoundary, useApplicationCapabilities } from '../ApplicationCapabilitiesContext';

vi.unmock('react-router');
const auth = vi.hoisted(() => ({ user: { id: 'actor-a', tenantId: 'tenant-a', jwt: 'session-a' }, isAuthenticated: true }));
vi.mock('../AuthContext', () => ({ useAuth: () => auth }));
vi.mock('../I18nContext', () => ({ useI18n: () => ({ t: (key: string) => key }) }));
function Probe() {
  const state = useApplicationCapabilities();
  return <><span data-testid="snapshot">{state.loading ? 'loading' : state.agent}</span><button onClick={state.refresh}>refresh</button></>;
}
function view(path = '/home') {
  return <MemoryRouter initialEntries={[path]}><ApplicationCapabilitiesProvider><Probe /><AgentRouteBoundary><span>ordinary content</span></AgentRouteBoundary></ApplicationCapabilitiesProvider></MemoryRouter>;
}
const response = (state: string) => new Response(JSON.stringify({ schemaVersion: 1, agent: { state } }));
beforeEach(() => { auth.user = { id: 'actor-a', tenantId: 'tenant-a', jwt: 'session-a' }; });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe('session scoped application discovery', () => {
  it('keeps ordinary content usable while discovery is pending', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => new Promise(() => {})));
    render(view());
    expect(screen.getByText('ordinary content')).toBeTruthy();
    expect(screen.getByTestId('snapshot').textContent).toBe('loading');
  });
  it('blocks AI deep links on absence and retries into the installed screen', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(response('not_installed')).mockResolvedValueOnce(response('installed'));
    vi.stubGlobal('fetch', fetcher);
    render(view('/aurabot/dashboard'));
    await waitFor(() => expect(screen.getByTestId('agent-availability').getAttribute('data-state')).toBe('not_installed'));
    expect(screen.queryByText('ordinary content')).toBeNull();
    fireEvent.click(screen.getByText('application.agent.retry'));
    await screen.findByText('ordinary content');
    expect(screen.getByTestId('snapshot').textContent).toBe('installed');
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
  it('keeps HTTP failure unknown with a recovery action', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 503 })));
    render(view('/p/c/ai_colleagues'));
    await waitFor(() => expect(screen.getByTestId('agent-availability').getAttribute('data-state')).toBe('unknown'));
    expect(screen.getByText('application.agent.retry')).toBeTruthy();
  });
  it('ignores a late old-session response after tenant/session changes', async () => {
    let resolveOld!: (value: Response) => void;
    const fetcher = vi.fn().mockImplementationOnce(() => new Promise<Response>(resolve => { resolveOld = resolve; }))
      .mockResolvedValueOnce(response('not_installed'));
    vi.stubGlobal('fetch', fetcher);
    const rendered = render(view());
    auth.user = { id: 'actor-b', tenantId: 'tenant-b', jwt: 'session-b' };
    rendered.rerender(view());
    await waitFor(() => expect(screen.getByTestId('snapshot').textContent).toBe('not_installed'));
    await act(async () => { resolveOld(response('installed')); });
    expect(screen.getByTestId('snapshot').textContent).toBe('not_installed');
  });
  it('preserves a mounted AI draft but makes it inert during same-session rediscovery', async () => {
    let resolveRefresh!: (value: Response) => void;
    const fetcher = vi.fn().mockResolvedValueOnce(response('installed'))
      .mockImplementationOnce(() => new Promise<Response>(resolve => { resolveRefresh = resolve; }));
    vi.stubGlobal('fetch', fetcher);
    render(<MemoryRouter initialEntries={['/aurabot/providers']}><ApplicationCapabilitiesProvider><AgentRouteBoundary><input aria-label="provider draft" defaultValue="" /></AgentRouteBoundary></ApplicationCapabilitiesProvider></MemoryRouter>);
    const input = await screen.findByRole('textbox', { name: 'provider draft' });
    fireEvent.change(input, { target: { value: 'Unsaved provider draft' } });
    fireEvent(window, new Event('focus'));
    await screen.findByTestId('agent-rediscovery');
    expect(screen.getByRole('textbox', { name: 'provider draft', hidden: true })).toBe(input);
    expect((input as HTMLInputElement).value).toBe('Unsaved provider draft');
    expect(input.closest('[inert]')).not.toBeNull();
    await act(async () => { resolveRefresh(response('installed')); });
    expect(screen.getByRole('textbox', { name: 'provider draft' })).toBe(input);
    expect((input as HTMLInputElement).value).toBe('Unsaved provider draft');
    expect(input.closest('[inert]')).toBeNull();
  });
  it('rediscovers on reconnect rather than retaining an old installation fact', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response('installed')).mockResolvedValueOnce(response('not_installed')));
    render(view());
    await waitFor(() => expect(screen.getByTestId('snapshot').textContent).toBe('installed'));
    fireEvent(window, new Event('online'));
    await waitFor(() => expect(screen.getByTestId('snapshot').textContent).toBe('not_installed'));
  });
});
