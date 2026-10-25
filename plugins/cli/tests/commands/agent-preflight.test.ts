import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { askCommand } from '../../src/commands/ask.js';
import { runCommand } from '../../src/commands/run.js';
import { statusCommand } from '../../src/commands/status.js';
import { ApiClient } from '../../src/client/api-client.js';

beforeEach(() => {
  vi.spyOn(ApiClient.prototype, 'requireAuth').mockResolvedValue();
  vi.spyOn(ApiClient.prototype, 'getBaseUrl').mockReturnValue('http://localhost:6431');
  vi.spyOn(ApiClient.prototype, 'getToken').mockReturnValue('unit-token');
  vi.spyOn(console, 'log').mockImplementation(() => {});
});
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

function discovery(state: string) { return new Response(JSON.stringify({ schemaVersion: 1, agent: { state } })); }

describe('AI command preflight through actual command entry points', () => {
  it.each(['not_installed', 'unknown'])('ask refuses %s before SSE and stdout', async state => {
    const fetcher = vi.fn().mockImplementation(async () => discovery(state));
    vi.stubGlobal('fetch', fetcher);
    await expect(askCommand('hello', { token: 'unit-token', agentMode: true })).rejects.toThrow();
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(console.log).not.toHaveBeenCalled();
  });
  it('dispatch refuses absent installation before backend writes', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => discovery('not_installed')));
    const post = vi.spyOn(ApiClient.prototype, 'post');
    await expect(runCommand('retained-task', { token: 'unit-token' })).rejects.toThrow('not installed');
    expect(post).not.toHaveBeenCalled();
  });
  it('installed dispatch retains its payload and output', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => discovery('installed')));
    const post = vi.spyOn(ApiClient.prototype, 'post').mockResolvedValue({ ok: true, status: 200, data: { runPid: 'run-123', agentCode: 'assistant', status: 'running' } });
    await runCommand('task-123', { token: 'unit-token', format: 'json', agentMode: true });
    expect(post).toHaveBeenCalledWith('/api/agent/dispatch', { taskPid: 'task-123' });
    expect(vi.mocked(console.log).mock.calls.flat().join(' ')).toContain('run-123');
  });
  it('base status preserves backend/auth and does not call the absent agent status API', async () => {
    const fetcher = vi.fn().mockImplementation(async input => String(input).includes('/application/capabilities')
      ? discovery('not_installed') : new Response('{}'));
    vi.stubGlobal('fetch', fetcher);
    await statusCommand({ token: 'unit-token', format: 'json' });
    const output = JSON.parse(vi.mocked(console.log).mock.calls[0][0]);
    expect(output.services.map((s: { status: string }) => s.status)).toEqual(['up', 'up', 'not_installed']);
    expect(fetcher.mock.calls.map(call => String(call[0]))).not.toContain(expect.stringContaining('/api/agent/status'));
  });
});
