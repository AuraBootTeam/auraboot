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
  it.each(['abort', 'latest-error'])(
    'rejects %s while a superseding read is pending and releases its subscription',
    async (outcome) => {
      let resolveFirst!: (value: any) => void, resolveLatest!: (value: any) => void;
      const first = new Promise((resolve) => {
        resolveFirst = resolve;
      });
      const latest = new Promise((resolve) => {
        resolveLatest = resolve;
      });
      vi.mocked(fetchResult)
        .mockReturnValueOnce(first as never)
        .mockReturnValueOnce(latest as never);
      const source = manager(),
        controller = new AbortController();
      const reload = source.reload.bind(source),
        subscribe = source.subscribe.bind(source),
        stopped = vi.fn();
      let explicitRead: Promise<void> | undefined;
      vi.spyOn(source, 'reload').mockImplementation((ids) => {
        const pending = reload(ids);
        explicitRead ??= pending;
        return pending;
      });
      const subscription = vi.spyOn(source, 'subscribe').mockImplementation((id, listener) => {
        const stop = subscribe(id, listener);
        return () => {
          stopped();
          stop();
        };
      });
      const review = reloadDetailForReview(async () => {}, source, ['history'], controller.signal);
      const observed = review.then(
        () => undefined,
        (error) => error,
      );
      try {
        await Promise.resolve();
        await Promise.resolve();
        const superseding = source.reload('history');
        resolveFirst({ code: '0', data: [] });
        await explicitRead;
        await Promise.resolve();
        expect(subscription).toHaveBeenCalledOnce();
        if (outcome === 'abort') controller.abort();
        resolveLatest(
          outcome === 'abort'
            ? { code: '0', data: [] }
            : { code: '500', success: false, httpStatus: 500, message: 'Latest read failed' },
        );
        await superseding;
        expect(await observed).toBeInstanceOf(Error);
        if (outcome === 'abort') expect((await observed).message).toContain('cancelled');
        else expect(source.getState('history')?.error).toBeInstanceOf(Error);
        expect(stopped).toHaveBeenCalledOnce();
        expect(fetchResult).toHaveBeenCalledTimes(2);
      } finally {
        resolveLatest({ code: '0', data: [] });
        await observed;
        source.clear();
      }
    },
  );

  it('waits for a newer same-source read when context refresh supersedes the explicit reload', async () => {
    let resolveFirst!: (value: any) => void, resolveLatest!: (value: any) => void;
    const first = new Promise((resolve) => {
      resolveFirst = resolve;
    });
    const latest = new Promise((resolve) => {
      resolveLatest = resolve;
    });
    vi.mocked(fetchResult)
      .mockReturnValueOnce(first as never)
      .mockReturnValueOnce(latest as never);
    const source = manager();
    const reload = source.reload.bind(source);
    let explicitRead: Promise<void> | undefined;
    vi.spyOn(source, 'reload').mockImplementation((ids) => {
      const pending = reload(ids);
      explicitRead ??= pending;
      return pending;
    });
    let settled = false;
    const review = reloadDetailForReview(async () => {}, source, ['history']).finally(() => {
      settled = true;
    });
    const observed = review.then(
      () => undefined,
      (error) => error,
    );
    try {
      await Promise.resolve();
      await Promise.resolve();
      expect(fetchResult).toHaveBeenCalledTimes(1);
      const superseding = source.reload('history');
      resolveFirst({ code: '0', data: [] });
      await explicitRead;
      await Promise.resolve();
      expect(source.getState('history')?.loading).toBe(true);
      expect(settled).toBe(false);
      resolveLatest({ code: '0', data: [] });
      await superseding;
      expect(await observed).toBeUndefined();
      expect(source.getState('history')?.loading).toBe(false);
      expect(fetchResult).toHaveBeenCalledTimes(2);
    } finally {
      resolveLatest({ code: '0', data: [] });
      await observed;
      source.clear();
    }
  });
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
