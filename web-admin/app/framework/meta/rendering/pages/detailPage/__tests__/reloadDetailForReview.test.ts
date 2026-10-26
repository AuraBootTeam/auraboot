import { describe, expect, it, vi, beforeEach } from 'vitest';
import { reloadDetailForReview } from '../reloadDetailForReview';
import { DataSourceManager } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';
import { fetchResult } from '~/shared/services/http-client';
vi.mock('~/shared/services/http-client', () => ({ fetchResult: vi.fn() }));
beforeEach(() => {
  vi.mocked(fetchResult).mockReset();
});
function manager(requiredIdentity = false) {
  const context = createExpressionContext({});
  if (requiredIdentity) (context as unknown as Record<string, unknown>).$page = { recordPid: '' };
  const source = new DataSourceManager(context);
  source.register('history', {
    type: 'api',
    endpoint: '/api/example/history',
    autoFetch: false,
    ...(requiredIdentity ? { dependOn: ['$page.recordPid'] } : {}),
  });
  return source;
}
describe('strict detail review read reuses the actual DMS contract', () => {
  it('rejects an actual DMS error even though its reload promise resolves', async () => {
    vi.mocked(fetchResult).mockResolvedValue({
      code: '500',
      success: false,
      httpStatus: 500,
      message: 'Read failed',
    } as never);
    const source = manager();
    try {
      await expect(source.reload('history')).resolves.toBeUndefined();
      expect(source.getState('history')?.error).toBeInstanceOf(Error);
      await expect(
        reloadDetailForReview(vi.fn().mockResolvedValue(undefined), source, ['history']),
      ).rejects.toBeInstanceOf(Error);
      expect(fetchResult).toHaveBeenCalledTimes(2);
    } finally {
      source.clear();
    }
  });
  it('accepts a confirmed fresh empty result instead of treating empty as failed', async () => {
    vi.mocked(fetchResult).mockResolvedValue({ code: '0', data: [] } as never);
    const source = manager(),
      strict = vi.fn().mockResolvedValue(undefined);
    try {
      await expect(reloadDetailForReview(strict, source, ['history'])).resolves.toBeUndefined();
      expect(strict).toHaveBeenCalledOnce();
      expect(fetchResult).toHaveBeenCalledOnce();
      expect(source.getState('history')?.lastFetch).toEqual(expect.any(Number));
    } finally {
      source.clear();
    }
  });
  it('does not call a skipped missing-identity/cache state a new successful read', async () => {
    const source = manager(true);
    source.setData('history', []);
    const previous = source.getState('history')?.lastFetch;
    try {
      await expect(
        reloadDetailForReview(vi.fn().mockResolvedValue(undefined), source, ['history']),
      ).rejects.toThrow('did not confirm');
      expect(source.getState('history')?.lastFetch).toBe(previous);
      expect(fetchResult).not.toHaveBeenCalled();
    } finally {
      source.clear();
    }
  });
  it('stops before dependent reads if the existing strict master read rejects', async () => {
    const source = manager();
    try {
      await expect(
        reloadDetailForReview(vi.fn().mockRejectedValue(new Error('Master read failed')), source, [
          'history',
        ]),
      ).rejects.toThrow('Master read failed');
      expect(fetchResult).not.toHaveBeenCalled();
    } finally {
      source.clear();
    }
  });
});
