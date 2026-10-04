import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { diffCommand } from '../../src/commands/diff.js';

let dir: string;
let output: ReturnType<typeof vi.spyOn>;
const fetchMock = vi.fn();
const options = { target: 'http://fixture.invalid', user: 'fixture@example.test', password: 'fixture-secret' };
const response = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status });
function resources(kind: string, records: unknown[]) {
  writeFileSync(join(dir, 'config', `${kind}.json`), JSON.stringify(records));
}
function text() { return output.mock.calls.map(call => call.join(' ')).join('\n'); }

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), 'aura-cli-diff-'));
  mkdirSync(join(dir, 'config'));
  writeFileSync(join(dir, 'plugin.json'), JSON.stringify({ pluginId: 'demo', namespace: 'demo', version: '1.0.0' }));
  output = vi.spyOn(console, 'log').mockImplementation(() => {});
  vi.spyOn(process, 'exit').mockImplementation(() => { throw new Error('CLI exit'); });
  vi.stubGlobal('fetch', fetchMock);
  vi.stubEnv('AURA_USER', '');
  vi.stubEnv('AURA_PASSWORD', '');
  fetchMock.mockReset().mockResolvedValueOnce(response({ data: { jwt: 'fixture-token' } }));
});
afterEach(() => {
  rmSync(dir, { recursive: true, force: true });
  vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.unstubAllEnvs();
});

describe('diff command production behavior', () => {
  it('reports an empty plugin in sync without fetching unrelated resources', async () => {
    await diffCommand(dir, options);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(text()).toContain('No differences found');
  });
  it.each([
    { kind: 'models', endpoint: '/api/meta/models', key: 'code' },
    { kind: 'fields', endpoint: '/api/meta/fields', key: 'code' },
    { kind: 'commands', endpoint: '/api/meta/commands', key: 'code' },
    { kind: 'permissions', endpoint: '/api/admin/permissions', key: 'code' },
    { kind: 'menus', endpoint: '/api/admin/menus', key: 'code' },
    { kind: 'pages', endpoint: '/api/pages', key: 'pageKey' },
  ])('compares $kind with the correct API and identifier', async ({ kind, endpoint, key }) => {
    resources(kind, [{ [key]: 'same', description: 'Same' }, { [key]: 'new' },
      { [key]: 'changed', description: 'New description' }, {}]);
    fetchMock.mockResolvedValueOnce(response({ data: [{ [key]: 'same', description: 'Same' },
      { [key]: 'changed', description: 'Old description' }, { [key]: 'unrelated' }, {}] }));
    await diffCommand(dir, options);
    expect(fetchMock).toHaveBeenLastCalledWith(`${options.target}${endpoint}?size=1000`, {
      headers: { Authorization: 'Bearer fixture-token', 'Content-Type': 'application/json' },
    });
    expect(text()).toContain('same:'); expect(text()).toContain('identical');
    expect(text()).toContain('new (local only)'); expect(text()).toContain('description changed');
    expect(text()).toContain('Total: 2 difference(s) found');
    expect(text()).not.toContain('unrelated:'); expect(text()).not.toContain('fixture-token');
    expect(text()).not.toContain(options.password);
  });
  it('accepts paginated records and compares scalar values consistently', async () => {
    resources('models', [{ code: 'same', displayName: 42 }]);
    fetchMock.mockResolvedValueOnce(response({ data: { records: [{ code: 'same', displayName: '42' }] } }));
    await diffCommand(dir, options);
    expect(text()).toContain('No differences found');
  });
  it('falls back to environment credentials when explicit flags are omitted', async () => {
    vi.stubEnv('AURA_USER', 'env@example.test'); vi.stubEnv('AURA_PASSWORD', 'env-secret');
    await diffCommand(dir, { target: options.target });
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ email: 'env@example.test', password: 'env-secret' });
    expect(text()).not.toContain('env-secret');
  });
  it('prefers explicit credentials and uses the documented default user', async () => {
    vi.stubEnv('AURA_USER', 'env@example.test'); vi.stubEnv('AURA_PASSWORD', 'env-secret');
    await diffCommand(dir, options);
    expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({ email: options.user, password: options.password });
    fetchMock.mockResolvedValueOnce(response({ data: { jwt: 'fixture-token' } }));
    vi.stubEnv('AURA_USER', ''); vi.stubEnv('AURA_PASSWORD', '');
    await diffCommand(dir, { target: options.target });
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ email: 'admin@auraboot.com', password: '' });
  });
  it.each([{ payload: {}, status: 401, message: 'Authentication failed: 401' },
    { payload: {}, status: 200, message: 'Failed to get auth token' }])('rejects unusable login responses: %j', async ({ payload, status, message }) => {
    fetchMock.mockReset().mockResolvedValueOnce(response(payload, status));
    await expect(diffCommand(dir, options)).rejects.toThrow('CLI exit');
    expect(process.exit).toHaveBeenCalledWith(1); expect(text()).toContain(message);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
  it.each([{ status: 403 }, { status: 500 }])('fails instead of inventing differences on remote HTTP $status', async ({ status }) => {
    resources('models', [{ code: 'demo_order' }]);
    fetchMock.mockResolvedValueOnce(response({}, status));
    await expect(diffCommand(dir, options)).rejects.toThrow('CLI exit');
    expect(process.exit).toHaveBeenCalledWith(1);
    expect(text()).toContain(`Failed to fetch remote resources (${status})`);
    expect(text()).not.toContain('new (local only)');
  });
  it('fails on remote transport errors', async () => {
    resources('models', [{ code: 'demo_order' }]);
    fetchMock.mockRejectedValueOnce(new Error('Connection refused'));
    await expect(diffCommand(dir, options)).rejects.toThrow('CLI exit');
    expect(text()).toContain('Connection refused');
    expect(text()).not.toContain('new (local only)');
  });
  it.each([{ data: null }, { data: { records: 'invalid' } }, {}])('rejects malformed remote collections: %j', async payload => {
    resources('models', [{ code: 'demo_order' }]);
    fetchMock.mockResolvedValueOnce(response(payload));
    await expect(diffCommand(dir, options)).rejects.toThrow('CLI exit');
    expect(text()).toContain('Invalid remote resource response');
  });
  it('reports invalid local input before authenticating', async () => {
    writeFileSync(join(dir, 'config', 'models.json'), '{ invalid');
    await expect(diffCommand(dir, options)).rejects.toThrow('CLI exit');
    expect(text()).toContain('Invalid JSON in models.json');
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
