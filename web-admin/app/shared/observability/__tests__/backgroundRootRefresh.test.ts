import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ClientLoaderFunctionArgs } from 'react-router';
import type { RootLoaderData } from '~/root-data';
vi.mock('../clientErrorReporter', () => ({ reportClientError: vi.fn() }));
import {
  loadRootOnClient,
  runBackgroundRootRefresh,
  ROOT_DEPENDENCY_UNAVAILABLE,
  setBackgroundRefreshUnavailable,
  backgroundRefreshUnavailable,
} from '../backgroundRootRefresh';

const snapshot = {
  user: { id: 'teacher-1' },
  permissions: { permissionCodes: ['display'] },
  locale: 'zh-CN',
} as unknown as RootLoaderData;
const unavailable = {
  status: 503,
  statusText: 'Service Unavailable',
  internal: false,
  data: { code: ROOT_DEPENDENCY_UNAVAILABLE, requestId: 'route-1', stage: 'branding' },
};
const args = (error?: unknown, request = new Request(location.origin + '/xy/display')) =>
  ({
    request,
    serverLoader:
      error === undefined ? vi.fn().mockResolvedValue(snapshot) : vi.fn().mockRejectedValue(error),
  }) as unknown as ClientLoaderFunctionArgs;
async function background(input: ClientLoaderFunctionArgs, current = () => true) {
  let result: RootLoaderData | undefined;
  await runBackgroundRootRefresh({ url: location.href, snapshot, isCurrent: current }, async () => {
    result = await loadRootOnClient(input);
  });
  return result;
}
beforeEach(() => {
  history.replaceState(null, '', '/xy/display');
  setBackgroundRefreshUnavailable(false);
});
describe('background root refresh', () => {
  it('retains the displayed snapshot only for a typed dependency outage during background refresh', async () => {
    expect(await background(args(unavailable))).toBe(snapshot);
    expect(backgroundRefreshUnavailable()).toBe(true);
    expect(await background(args())).toBe(snapshot);
    expect(backgroundRefreshUnavailable()).toBe(false);
  });
  it('retains the display on browser transport failure after a successful probe', async () => {
    expect(await background(args(new TypeError('Failed to fetch')))).toBe(snapshot);
    expect(backgroundRefreshUnavailable()).toBe(true);
  });
  it.each([401, 403, 404, 500, 503])(
    'never retains stale authorization or generic HTTP %s failures',
    async (status) => {
      const error = { ...unavailable, status, data: { code: 'configuration_error' } };
      await expect(background(args(error))).rejects.toBe(error);
    },
  );
  it('keeps first loads and navigation failures authoritative', async () => {
    await expect(loadRootOnClient(args(unavailable))).rejects.toBe(unavailable);
    await expect(
      background(args(unavailable, new Request(location.origin + '/another-page'))),
    ).rejects.toBe(unavailable);
  });
  it('does not reuse a snapshot after identity disposal or cancellation', async () => {
    await expect(background(args(unavailable), () => false)).rejects.toBe(unavailable);
    const controller = new AbortController();
    controller.abort();
    await expect(
      background(args(unavailable, new Request(location.href, { signal: controller.signal }))),
    ).rejects.toBe(unavailable);
  });
  it('does not hide configuration or programming exceptions', async () => {
    for (const error of [
      new Error('Branding contract missing'),
      new TypeError('Cannot read properties of undefined'),
    ]) {
      await expect(background(args(error))).rejects.toBe(error);
    }
  });
  it('applies revoked permissions and a subsequent auth rejection after recovery', async () => {
    await background(args(unavailable));
    const updated = { ...snapshot, permissions: { permissionCodes: [] } };
    const input = args();
    input.serverLoader = vi.fn().mockResolvedValue(updated);
    expect(await background(input)).toEqual(updated);
    const rejected = { ...unavailable, status: 401, data: null };
    await expect(background(args(rejected))).rejects.toBe(rejected);
  });
  it('survives 1440 minute-equivalent refresh cycles with outages and recovery', async () => {
    for (let minute = 0; minute < 1440; minute++) {
      const failing = minute % 120 === 60;
      expect(await background(args(failing ? unavailable : undefined))).toBe(snapshot);
      expect(backgroundRefreshUnavailable()).toBe(failing);
    }
  });
});
