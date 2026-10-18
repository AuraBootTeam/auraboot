import { act, cleanup, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { RootLoaderData } from '~/root-data';
vi.mock('~/shared/observability/clientErrorReporter', () => ({ reportClientError: vi.fn() }));
import { reportClientError } from '~/shared/observability/clientErrorReporter';
import { SessionMaintenance } from '../SessionMaintenance';
const snapshot = {
  user: { id: 'member-1', tenantId: 'school-1' },
  permissions: { permissionCodes: ['model.read'], roles: [{ code: 'teacher' }] },
  preferences: null,
  menus: [],
  locale: 'zh-CN',
  impersonation: null,
} as unknown as RootLoaderData;
const fresh = () => new Response(JSON.stringify({ code: '0', data: snapshot }));
const outage = () =>
  new Response(JSON.stringify({ code: 'session_context_unavailable', stage: 'identity' }), {
    status: 503,
    headers: { 'x-trace-id': '0123456789abcdef0123456789abcdef' },
  });
let fetchMock: ReturnType<typeof vi.fn>;
const setup = (overrides = {}) => {
  const props = {
    enabled: true,
    source: snapshot,
    snapshot,
    onRefresh: vi.fn(),
    onRejected: vi.fn(),
    onError: vi.fn(),
    ...overrides,
  };
  return {
    props,
    ...render(
      <>
        <SessionMaintenance {...props} />
        <input aria-label="draft" defaultValue="unsaved" />
      </>,
    ),
  };
};
const tick = async (ms = 60000) => {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
};
beforeEach(() => {
  vi.useFakeTimers();
  vi.clearAllMocks();
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: true });
  Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
  Object.defineProperty(document, 'fullscreenElement', { configurable: true, value: null });
  fetchMock = vi.fn().mockImplementation(async () => fresh());
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});
describe('platform session maintenance', () => {
  it('polls only session context without route reloads or business writes', async () => {
    const { props } = setup();
    await tick();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/runtime/session-context',
      expect.objectContaining({ credentials: 'same-origin', signal: expect.any(AbortSignal) }),
    );
    expect(props.onRefresh).toHaveBeenCalledWith(expect.objectContaining({ user: snapshot.user }));
    expect((screen.getByLabelText('draft') as HTMLInputElement).value).toBe('unsaved');
  });
  it('retains business content during a typed outage and recovers immediately on reconnect', async () => {
    fetchMock.mockImplementationOnce(async () => outage());
    const { props } = setup();
    const input = screen.getByLabelText('draft');
    await tick();
    expect(screen.getByTestId('background-refresh-unavailable')).toBeTruthy();
    expect(props.onRefresh).not.toHaveBeenCalled();
    await act(async () => {
      window.dispatchEvent(new Event('online'));
    });
    expect(props.onRefresh).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('background-refresh-unavailable')).toBeNull();
    expect(screen.getByLabelText('draft')).toBe(input);
  });
  it('reports one failure per unavailable transition with response trace identity', async () => {
    fetchMock.mockImplementation(async () => outage());
    setup();
    await tick(120000);
    expect(reportClientError).toHaveBeenCalledTimes(1);
    expect(reportClientError).toHaveBeenCalledWith(
      expect.objectContaining({
        traceId: '0123456789abcdef0123456789abcdef',
        message: expect.stringContaining('stage=identity'),
      }),
    );
  });
  it('defers network failures without applying a partial session snapshot', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));
    const { props } = setup();
    await tick();
    expect(screen.getByTestId('background-refresh-unavailable')).toBeTruthy();
    expect(props.onRefresh).not.toHaveBeenCalled();
    expect(props.onError).not.toHaveBeenCalled();
  });
  it.each([401, 403])('treats authoritative HTTP %s rejection as terminal', async (status) => {
    fetchMock.mockImplementation(async () => new Response('{}', { status }));
    const { props } = setup();
    await tick(180000);
    expect(props.onRejected).toHaveBeenCalledExactlyOnceWith(status);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
  it.each([500, 503])('does not disguise generic HTTP %s as a transport outage', async (status) => {
    fetchMock.mockImplementation(async () => new Response('{}', { status }));
    const { props } = setup();
    await tick();
    expect(props.onError).toHaveBeenCalledTimes(1);
    expect(screen.queryByTestId('background-refresh-unavailable')).toBeNull();
  });
  it('rejects invalid successful contracts instead of retaining silently', async () => {
    fetchMock.mockImplementation(
      async () => new Response(JSON.stringify({ code: '0', data: { user: snapshot.user } })),
    );
    const { props } = setup();
    await tick();
    expect(props.onError).toHaveBeenCalledTimes(1);
    expect(props.onRefresh).not.toHaveBeenCalled();
  });
  it('invalidates protected content when permissions are removed', async () => {
    fetchMock.mockImplementation(
      async () =>
        new Response(
          JSON.stringify({
            code: '0',
            data: { ...snapshot, permissions: { permissionCodes: [], roles: [] } },
          }),
        ),
    );
    const { props } = setup();
    await tick();
    expect(props.onRejected).toHaveBeenCalledExactlyOnceWith(403);
    expect(props.onRefresh).not.toHaveBeenCalled();
  });
  it('applies permission gains without reloading the business page', async () => {
    fetchMock.mockImplementation(
      async () =>
        new Response(
          JSON.stringify({
            code: '0',
            data: {
              ...snapshot,
              permissions: {
                ...snapshot.permissions,
                permissionCodes: ['model.read', 'model.write'],
              },
            },
          }),
        ),
    );
    const { props } = setup();
    await tick();
    expect(props.onRefresh).toHaveBeenCalledTimes(1);
    expect(props.onRejected).not.toHaveBeenCalled();
  });
  it('cancels a hung request at the maintenance deadline', async () => {
    fetchMock.mockImplementation(
      (_url, init) =>
        new Promise((_resolve, reject) =>
          init.signal.addEventListener('abort', () =>
            reject(new DOMException('aborted', 'AbortError')),
          ),
        ),
    );
    setup();
    await tick(70001);
    expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(true);
    expect(screen.getByTestId('background-refresh-unavailable')).toBeTruthy();
  });
  it('never overlaps maintenance requests even under repeated focus and online events', async () => {
    fetchMock.mockImplementation(() => new Promise(() => {}));
    setup();
    await tick();
    await act(async () => {
      window.dispatchEvent(new Event('focus'));
      window.dispatchEvent(new Event('online'));
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
  it('cancels on unmount and discards a late response from the previous source', async () => {
    let resolve!: (response: Response) => void;
    fetchMock.mockImplementation(
      () =>
        new Promise((r) => {
          resolve = r;
        }),
    );
    const view = setup();
    await tick();
    view.unmount();
    expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(true);
    await act(async () => {
      resolve(fresh());
    });
    expect(view.props.onRefresh).not.toHaveBeenCalled();
  });
  it('does not poll anonymous, impersonated or disabled sessions', async () => {
    const view = setup({ source: { ...snapshot, user: null } });
    await tick();
    view.unmount();
    const second = setup({
      source: { ...snapshot, impersonation: { sessionPid: 'operator-session' } },
    });
    await tick();
    second.unmount();
    setup({ enabled: false });
    await tick();
    expect(fetchMock).not.toHaveBeenCalled();
  });
  it('does not poll while offline or hidden and resumes on visibility', async () => {
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: false });
    setup();
    await tick();
    expect(fetchMock).not.toHaveBeenCalled();
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: true });
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' });
    await tick();
    expect(fetchMock).not.toHaveBeenCalled();
    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' });
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'));
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
  it('keeps outage feedback inside the current fullscreen element', async () => {
    const target = document.createElement('div');
    document.body.append(target);
    Object.defineProperty(document, 'fullscreenElement', { configurable: true, value: target });
    fetchMock.mockImplementation(async () => outage());
    setup();
    await tick();
    expect(target.contains(screen.getByTestId('background-refresh-unavailable'))).toBe(true);
    target.remove();
  });
  it('survives 24 hours of accelerated session polling without route refreshes', async () => {
    const { props } = setup();
    await tick(24 * 60 * 60000);
    expect(fetchMock).toHaveBeenCalledTimes(1440);
    expect(props.onRefresh).toHaveBeenCalledTimes(1440);
    expect(props.onRejected).not.toHaveBeenCalled();
  });
});
