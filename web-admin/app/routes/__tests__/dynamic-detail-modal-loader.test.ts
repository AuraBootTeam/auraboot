import { beforeEach, describe, expect, it, vi } from 'vitest';
import { RouterContextProvider, type LoaderFunctionArgs } from 'react-router';
import { loader } from '../p.$pageKey.view';
import { getTokenFromRequest } from '~/shared/services/session';
import { resolveDynamicPageAccessError } from '~/shared/services/dynamic-page-access.server';
vi.mock('~/shared/services/session', () => ({ getTokenFromRequest: vi.fn() }));
vi.mock('~/shared/services/dynamic-page-access.server', () => ({
  resolveDynamicPageAccessError: vi.fn(),
}));
function args(modal = true): LoaderFunctionArgs {
  const request = new Request(
    `http://localhost/p/anc_alert/view/A${modal ? '?presentation=modal' : ''}`,
  );
  return {
    request,
    url: new URL(request.url),
    pattern: '/p/:pageKey/view/:recordId',
    params: { pageKey: 'anc_alert', recordId: 'A' },
    context: new RouterContextProvider(),
  };
}
beforeEach(() => {
  vi.mocked(getTokenFromRequest).mockReset().mockResolvedValue('server-token');
  vi.mocked(resolveDynamicPageAccessError).mockReset();
});
describe('modal detail loader preserves route authorization/status', () => {
  it('uses the original page access check and returns a genuine 403 response init', async () => {
    vi.mocked(resolveDynamicPageAccessError).mockResolvedValue('Denied');
    const input = args();
    const result = await loader(input);
    expect(resolveDynamicPageAccessError).toHaveBeenCalledExactlyOnceWith(
      input.request,
      'server-token',
      'anc_alert',
    );
    expect(result).toMatchObject({
      data: { tableName: 'anc_alert', recordPid: 'A', accessError: 'Denied', httpStatus: 403 },
      init: { status: 403 },
    });
  });
  it.each([401, 404, 500])(
    'returns a correlated modal error with HTTP %s without throwing into the background boundary',
    async (status) => {
      vi.mocked(resolveDynamicPageAccessError).mockRejectedValue(
        new Response('Failed', { status }),
      );
      await expect(loader(args())).resolves.toMatchObject({
        data: { tableName: 'anc_alert', recordPid: 'A', httpStatus: status },
        init: { status },
      });
    },
  );
  it('keeps ordinary route success/denial shape and thrown response behavior', async () => {
    vi.mocked(resolveDynamicPageAccessError).mockResolvedValue(null);
    await expect(loader(args(false))).resolves.toMatchObject({
      tableName: 'anc_alert',
      recordPid: 'A',
      accessError: null,
    });
    vi.mocked(resolveDynamicPageAccessError).mockRejectedValue(
      new Response('Not found', { status: 404 }),
    );
    await expect(loader(args(false))).rejects.toMatchObject({ status: 404 });
  });
});
