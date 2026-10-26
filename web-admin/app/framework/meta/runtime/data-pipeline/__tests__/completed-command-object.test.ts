import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchResult } from '~/shared/services/http-client';
import { DataSourceManager } from '../DataSourceManager';
import { createExpressionContext } from '../../expression/context';
import type { DataSourceConfig } from '../../../schemas/types';

vi.mock('~/shared/services/http-client', () => ({ fetchResult: vi.fn() }));
const fetch = vi.mocked(fetchResult);
const managers: DataSourceManager[] = [];
function source(extra: Partial<DataSourceConfig> = {}) {
  const manager = new DataSourceManager(createExpressionContext({ state: { target: 'scope-1' } } as any));
  managers.push(manager);
  manager.register('snapshot', {
    type: 'api', method: 'post', autoFetch: false, adaptor: 'commandObject',
    endpoint: '/api/meta/commands/execute/example:read_snapshot',
    params: { targetRecordPid: '${state.target}', payload: {} }, ...extra,
  });
  return manager;
}
beforeEach(() => {
  fetch.mockReset();
  vi.spyOn(console, 'error').mockImplementation(() => {});
});
afterEach(() => {
  for (const manager of managers.splice(0)) manager.clear();
  vi.restoreAllMocks();
});

describe('Completed command objects through the real named-source manager', () => {
  it('unwraps exactly one completed envelope and preserves object metadata and null values', async () => {
    const payload = { scope: 'scope-1', value: null, enabled: false, samples: [{ value: '9007199254740993.1' }] };
    fetch.mockResolvedValueOnce({ code: '0', data: { phaseReached: 'completed', data: payload } } as any);
    const manager = source();
    expect(await manager.fetch('snapshot')).toEqual(payload);
    expect(manager.getData('snapshot')).toEqual(payload);
    expect(manager.getState('snapshot')?.error).toBeNull();
    expect(fetch).toHaveBeenCalledExactlyOnceWith('/api/meta/commands/execute/example:read_snapshot', {
      method: 'post', params: { targetRecordPid: 'scope-1', payload: {} },
    });
  });

  it('uses only an explicitly configured object path', async () => {
    const payload = { snapshot: { value: 0, enabled: false }, other: { value: 99 } };
    fetch.mockResolvedValueOnce({ code: '0', data: { phaseReached: 'completed', data: payload } } as any);
    expect(await source({ dataPath: 'snapshot' }).fetch('snapshot')).toEqual(payload.snapshot);
  });

  it.each([
    { phaseReached: 'handler', data: { value: 1 } },
    { data: { value: 1 } },
    { phaseReached: 'completed', data: null },
    { phaseReached: 'completed', data: [] },
    { phaseReached: 'completed' },
    { code: '0', data: { phaseReached: 'completed', data: { value: 1 } } },
  ])('rejects incomplete, missing, array and double-wrapped results without an empty success', async command => {
    fetch.mockResolvedValueOnce({ code: '0', data: command } as any);
    const manager = source();
    expect(await manager.fetch('snapshot')).toBeNull();
    expect(manager.getState('snapshot')?.error).toBeInstanceOf(Error);
    expect(manager.getData('snapshot')).toBeNull();
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it.each(['missing', '', '__proto__.value', 'constructor.name'])('does not fall back from an invalid or missing path: %s', async dataPath => {
    fetch.mockResolvedValueOnce({ code: '0', data: { phaseReached: 'completed', data: { value: 7 } } } as any);
    const manager = source({ dataPath });
    expect(await manager.fetch('snapshot')).toBeNull();
    expect(manager.getState('snapshot')?.error).toBeInstanceOf(Error);
  });

  it('rejects accessor paths without invoking them', async () => {
    let calls = 0;
    const payload = { get snapshot() { calls++; return { value: 7 }; } };
    fetch.mockResolvedValueOnce({ code: '0', data: { phaseReached: 'completed', data: payload } } as any);
    const manager = source({ dataPath: 'snapshot' });
    expect(await manager.fetch('snapshot')).toBeNull();
    expect(manager.getState('snapshot')?.error).toBeInstanceOf(Error);
    expect(calls).toBe(0);
  });

  it('keeps a real ResultData permission failure as a failed named-source read', async () => {
    fetch.mockResolvedValueOnce({ code: '403', message: 'Denied', data: null } as any);
    const manager = source();
    expect(await manager.fetch('snapshot')).toBeNull();
    expect(manager.getState('snapshot')?.error).toMatchObject({ status: 403 });
    expect(fetch).toHaveBeenCalledTimes(1);
  });
});
