import { it, expect } from 'vitest';
import { createTracker } from '../tracker';

it('batches and flushes via keepalive post at batchSize', async () => {
  const sent: any[] = [];
  const post = (url: string, body: any, opts: any) => {
    sent.push({ url, body, opts });
    return Promise.resolve({});
  };
  const t = createTracker({ post, getSessionId: () => 's1', batchSize: 2 });
  t.pageview('/p/c/a');
  t.pageview('/p/c/b'); // hits batchSize=2 -> auto flush
  await Promise.resolve();
  expect(sent).toHaveLength(1);
  expect(sent[0].url).toBe('/api/collect');
  expect(sent[0].opts.keepalive).toBe(true);
  expect(sent[0].body.events).toHaveLength(2);
  expect(sent[0].body.events[0].eventName).toBe('page_view');
});

it('flushes pending events when page becomes hidden', async () => {
  const sent: any[] = [];
  const post = (url: string, body: any, opts: any) => {
    sent.push({ url, body, opts });
    return Promise.resolve({});
  };
  const t = createTracker({ post, getSessionId: () => 's2', batchSize: 10 });
  t.init();
  t.pageview('/p/c/hidden');
  // Simulate visibilitychange to hidden
  Object.defineProperty(document, 'visibilityState', {
    value: 'hidden',
    configurable: true,
  });
  document.dispatchEvent(new Event('visibilitychange'));
  await Promise.resolve();
  expect(sent).toHaveLength(1);
  expect(sent[0].body.events[0].eventName).toBe('page_view');
  // Restore
  Object.defineProperty(document, 'visibilityState', {
    value: 'visible',
    configurable: true,
  });
});

it('does not throw when post rejects', async () => {
  const post = () => Promise.reject(new Error('network error'));
  const t = createTracker({ post, getSessionId: () => 's3', batchSize: 1 });
  // Should not throw even when post fails
  await expect(async () => {
    t.pageview('/error-path');
    await Promise.resolve();
  }).not.toThrow();
});
