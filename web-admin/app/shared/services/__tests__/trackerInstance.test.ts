import { afterEach, describe, expect, it, vi } from 'vitest';

// Mock the platform http-client before importing the module under test.
const postMock = vi.fn().mockResolvedValue({ code: '0', data: null, desc: '' });
vi.mock('~/shared/services/http-client', () => ({
  post: postMock,
}));

describe('trackerInstance', () => {
  afterEach(() => {
    vi.resetModules();
    vi.clearAllMocks();
  });

  it('getTracker() returns the same singleton on repeated calls', async () => {
    const { getTracker } = await import('../trackerInstance');
    const t1 = getTracker();
    const t2 = getTracker();
    expect(t1).toBe(t2);
  });

  it('a tracked pageview posts to the http-client with keepalive:true', async () => {
    const { getTracker } = await import('../trackerInstance');
    const tracker = getTracker();

    // Emit a pageview and immediately flush (batchSize default=10; call flush directly).
    tracker.pageview('/p/orders');
    await tracker.flush();

    expect(postMock).toHaveBeenCalledOnce();
    const [_url, _body, opts] = postMock.mock.calls[0];
    expect(opts).toMatchObject({ keepalive: true });
  });
});
