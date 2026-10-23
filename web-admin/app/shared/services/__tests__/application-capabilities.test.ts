import { afterEach, describe, expect, it, vi } from 'vitest';
import { agentFetch, discoverApplication } from '../application-capabilities';
import { filterAgentMenus, requiresAgentPath } from '../agent-surfaces';

afterEach(() => vi.unstubAllGlobals());
describe('application installation discovery', () => {
  it.each(['installed', 'not_installed'])('reads schema v1 %s independently of permissions', async state => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ schemaVersion: 1, agent: { state } }))));
    expect(await discoverApplication()).toEqual({ agent: state });
  });
  it.each([401, 403, 404, 503])('retains unknown HTTP %s', async status => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status })));
    expect(await discoverApplication()).toEqual({ agent: 'unknown' });
  });
  it.each([{ schemaVersion: '1', agent: { state: 'installed' } }, { schemaVersion: 2, agent: { state: 'not_installed' } }, {}, { schemaVersion: 1, agent: { state: 'ready' } }])('rejects unsupported contract %j', async body => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(body))));
    expect(await discoverApplication()).toEqual({ agent: 'unknown' });
  });
  it('blocks AI before streaming or mutating requests, preserves explicit feedback', async () => {
    const fetcher = vi.fn().mockImplementation(async () => new Response(JSON.stringify({ schemaVersion: 1, agent: { state: 'not_installed' } })));
    vi.stubGlobal('fetch', fetcher);
    await expect(agentFetch('/api/agent/dispatch', { method: 'POST' })).rejects.toThrow('$i18n:application.agent.not_installed');
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it('preserves the installed request and fetch options after fresh discovery', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ schemaVersion: 1, agent: { state: 'installed' } })))
      .mockResolvedValueOnce(new Response('stream'));
    vi.stubGlobal('fetch', fetcher);
    const init = { method: 'POST', body: 'original', signal: new AbortController().signal };
    expect(await (await agentFetch('/api/ai/aurabot/chat/stream', init)).text()).toBe('stream');
    expect(fetcher.mock.calls[1]).toEqual(['/api/ai/aurabot/chat/stream', init]);
  });
});
describe('existing AI resource surfaces', () => {
  it('matches dynamic and DSL deep links without suppressing ordinary designers', () => {
    for (const path of ['/aurabot/traces/trace-1', '/p/c/ai_colleagues?agentPid=a', '/meta/ai-modeling']) expect(requiresAgentPath(path)).toBe(true);
    for (const path of ['/p/customers', '/meta/models', '/studio', '/home']) expect(requiresAgentPath(path)).toBe(false);
  });
  it('filters only AI leaves and empty folders while preserving ordinary siblings', () => {
    const menus = [{ path: '#', submenu: [{ path: '/p/customers' }, { path: '/aurabot/dashboard' }] }, { path: '#ai', submenu: [{ path: '/p/c/ai_colleagues' }] }];
    expect(filterAgentMenus(menus, false)).toEqual([{ path: '#', submenu: [{ path: '/p/customers' }] }]);
    expect(filterAgentMenus(menus, true)).toEqual(menus);
  });
});
