import { afterEach, describe, expect, it, vi } from 'vitest';
import { discoverAgent, requireAgent } from '../../src/client/agent-capabilities.js';
import { ApiClient } from '../../src/client/api-client.js';
import { buildToolRegistry } from '../../src/mcp/server.js';

const client = { getBaseUrl: () => 'http://localhost:6431', getToken: () => 'test-token' };
afterEach(() => vi.unstubAllGlobals());

describe('verified application capability discovery', () => {
  it.each(['installed', 'not_installed'] as const)('accepts only schema v1 %s', async state => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ schemaVersion: 1, agent: { state } })));
    vi.stubGlobal('fetch', fetcher);
    expect(await discoverAgent(client)).toEqual({ state });
    expect(fetcher.mock.calls[0][0].pathname).toBe('/api/application/capabilities');
    expect(fetcher.mock.calls[0][1]).toMatchObject({ cache: 'no-store', headers: { Authorization: 'Bearer test-token' } });
  });
  it.each([401, 403, 404, 503])('keeps HTTP %s unknown', async status => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status })));
    expect((await discoverAgent(client)).state).toBe('unknown');
    await expect(requireAgent(client)).rejects.toThrow('Cannot verify agent availability');
  });
  it.each([
    { schemaVersion: '1', agent: { state: 'installed' } },
    { schemaVersion: 2, agent: { state: 'not_installed' } },
    { schemaVersion: 1, agent: { state: 'ready' } },
    {},
  ])('rejects unsupported contracts %j', async body => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => new Response(JSON.stringify(body))));
    expect((await discoverAgent(client)).state).toBe('unknown');
  });
  it('does not reuse installation facts across actions or sessions', async () => {
    const fetcher = vi.fn().mockImplementationOnce(async () => new Response(JSON.stringify({ schemaVersion: 1, agent: { state: 'installed' } })))
      .mockImplementationOnce(async () => new Response(JSON.stringify({ schemaVersion: 1, agent: { state: 'not_installed' } })));
    vi.stubGlobal('fetch', fetcher);
    await requireAgent(client);
    await expect(requireAgent(client)).rejects.toThrow('not installed');
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
  it('keeps network failures unknown and unauthenticated discovery local', async () => {
    const fetcher = vi.fn().mockRejectedValue(new Error('offline'));
    vi.stubGlobal('fetch', fetcher);
    expect((await discoverAgent(client)).state).toBe('unknown');
    expect((await discoverAgent({ ...client, getToken: () => null })).state).toBe('unknown');
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
});

describe('MCP assembly boundary independent of profiles', () => {
  const api = { ...client, post: vi.fn(), get: vi.fn() } as unknown as ApiClient;
  it.each(['not_installed', 'unknown'] as const)('keeps ordinary tools for %s', state => {
    const registry = buildToolRegistry(api, { profile: 'full', agentState: state });
    expect(registry.list().map(t => t.name)).toContain('query_entity');
    expect(registry.list().map(t => t.name)).toContain('create_model');
    for (const name of ['ask_aurabot', 'dispatch_agent', 'list_agents', 'list_tools']) expect(registry.get(name)).toBeUndefined();
    expect(registry.size()).toBe(11);
  });
  it('keeps full installed discovery and rejects stale direct AI execution before writes', async () => {
    const registry = buildToolRegistry(api, { profile: 'full', agentState: 'installed' });
    expect(registry.size()).toBe(15);
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => new Response(JSON.stringify({ schemaVersion: 1, agent: { state: 'not_installed' } }))));
    for (const [name, params] of [['ask_aurabot', { question: 'hello' }], ['dispatch_agent', { taskPid: 'task' }], ['list_agents', {}], ['list_tools', {}]] as const) {
      const result = await registry.get(name)!.handler(params);
      expect(result.isError).toBe(true);
      expect(result.content[0].text).toContain('not installed');
    }
    expect(api.post).not.toHaveBeenCalled();
    expect(api.get).not.toHaveBeenCalled();
  });
});
