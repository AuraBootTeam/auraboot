import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const fetchResult = vi.hoisted(() => vi.fn());
vi.mock('~/shared/services/http-client/HttpClient', () => ({ fetchResult }));

describe('PDA replay scheduling boundary', () => {
  const listeners = new Map<string, (event: Event) => void>();
  const workerListeners = new Map<string, (event: MessageEvent) => void>();
  const controller = {};

  beforeEach(() => {
    vi.resetModules();
    vi.useFakeTimers();
    listeners.clear();
    workerListeners.clear();
    fetchResult.mockReset().mockResolvedValue({ code: '0', data: { records: [] } });
    vi.stubGlobal('caches', {
      open: vi.fn().mockResolvedValue({ keys: vi.fn().mockResolvedValue([]) }),
    });
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: true });
    Object.defineProperty(navigator, 'serviceWorker', {
      configurable: true,
      value: {
        register: vi.fn().mockResolvedValue({}),
        controller,
        addEventListener: vi.fn((name: string, listener: (event: MessageEvent) => void) =>
          workerListeners.set(name, listener),
        ),
      },
    });
    vi.spyOn(window, 'addEventListener').mockImplementation((name, listener) => {
      if (typeof listener === 'function') listeners.set(name, listener as (event: Event) => void);
    });
  });

  it('accepts outbox notifications only from the current same-origin service worker', async () => {
    window.history.replaceState({}, '', '/inventory/pda-pick');
    const replay = await import('../offlineReplay');
    replay.startPdaOfflineShell();
    const handler = workerListeners.get('message')!;
    expect(handler).toBeTypeOf('function');
    expect(listeners.has('message')).toBe(false);
    const message = {
      origin: window.location.origin,
      source: controller,
      data: { type: 'PDA_OUTBOX_STASHED' },
    };
    for (const rejected of [
      { ...message, origin: 'https://untrusted.example' },
      { ...message, source: {} },
      { ...message, source: null },
      { ...message, data: { type: 'OTHER' } },
    ]) {
      handler(rejected as MessageEvent);
      await vi.advanceTimersByTimeAsync(0);
      expect(fetchResult).not.toHaveBeenCalled();
    }
    handler(message as MessageEvent);
    await vi.advanceTimersByTimeAsync(0);
    expect(fetchResult).toHaveBeenCalledWith('/api/dynamic/inv_pda_offline_queue/list', {
      method: 'get',
      params: { pageNum: 1, pageSize: 100 },
    });
    fetchResult.mockClear();
    Object.defineProperty(navigator.serviceWorker, 'controller', { value: null });
    handler(message as MessageEvent);
    await vi.advanceTimersByTimeAsync(0);
    expect(fetchResult).not.toHaveBeenCalled();
  });

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it.each(['/quote', '/login', '/p/qo_quote_list', '/inventory/pda-pickings'])(
    'does not query an inventory model from %s',
    async (pathname) => {
      window.history.replaceState({}, '', pathname);
      const replay = await import('../offlineReplay');
      replay.startPdaOfflineShell();
      await vi.advanceTimersByTimeAsync(3000);
      await replay.replayNow('manual-probe');
      expect(fetchResult).not.toHaveBeenCalled();
    },
  );

  it('sweeps on an active PDA page and stops scheduling after leaving it', async () => {
    window.history.replaceState({}, '', '/inventory/pda-pick');
    const replay = await import('../offlineReplay');
    replay.startPdaOfflineShell();
    await replay.replayNow('route-entry');
    expect(fetchResult).toHaveBeenCalledWith('/api/dynamic/inv_pda_offline_queue/list', {
      method: 'get',
      params: { pageNum: 1, pageSize: 100 },
    });
    fetchResult.mockClear();
    window.history.replaceState({}, '', '/p/qo_quote_list');
    listeners.get('online')?.(new Event('online'));
    await replay.replayNow('manual-probe');
    expect(fetchResult).not.toHaveBeenCalled();
  });

  it('does not schedule replay after the authenticated PDA lifecycle is suspended', async () => {
    window.history.replaceState({}, '', '/inventory/pda-putaway');
    const replay = await import('../offlineReplay');
    replay.startPdaOfflineShell();
    await replay.replayNow('route-entry');
    fetchResult.mockClear();
    replay.suspendPdaOfflineShell();
    listeners.get('online')?.(new Event('online'));
    await replay.replayNow('manual-probe');
    expect(fetchResult).not.toHaveBeenCalled();
  });

  it.each([
    ['0', 'inv:pda_mark_replayed'],
    ['CONFLICT', 'inv:pda_mark_rejected'],
  ])('preserves the original command identity and %s outcome', async (code, marked) => {
    window.history.replaceState({}, '', '/inventory/pda-putaway');
    const row = {
      pid: 'queue-1',
      created_at: new Date().toISOString(),
      inv_pq_status: 'queued',
      inv_pq_command: 'inv:putaway_confirm',
      inv_pq_target_pid: 'task-1',
      inv_pq_create_key: 'capture-1',
      inv_pq_payload: JSON.stringify({ quantity: 2 }),
    };
    fetchResult.mockImplementation(async (url: string) =>
      url.endsWith('/list')
        ? { code: '0', data: { records: [row] } }
        : url.endsWith('/inv:putaway_confirm')
          ? { code, message: 'CAS conflict', data: null }
          : { code: '0', data: null },
    );
    const replay = await import('../offlineReplay');
    replay.startPdaOfflineShell();
    await replay.replayNow('route-entry');
    expect(fetchResult).toHaveBeenCalledWith('/api/meta/commands/execute/inv:putaway_confirm', {
      method: 'post',
      body: {
        clientRequestId: 'capture-1',
        payload: { quantity: 2 },
        targetRecordPid: 'task-1',
        operationType: 'update',
      },
    });
    expect(fetchResult).toHaveBeenCalledWith(`/api/meta/commands/execute/${marked}`, {
      method: 'post',
      body: expect.objectContaining({
        targetRecordPid: 'queue-1',
        operationType: 'update',
      }),
    });
  });
});
