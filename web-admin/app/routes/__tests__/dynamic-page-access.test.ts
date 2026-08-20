import { beforeEach, describe, expect, it, vi } from 'vitest';
import { resolveDynamicPageAccessError } from '~/shared/services/dynamic-page-access.server';
import { fetchResult } from '~/shared/services/http-client';

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: vi.fn(),
}));

describe('dynamic page access inheritance', () => {
  beforeEach(() => {
    vi.mocked(fetchResult).mockReset();
  });

  it('checks the parent menu path for list, create, edit, and detail routes', async () => {
    vi.mocked(fetchResult).mockResolvedValue({ code: '0', data: null } as never);
    const request = new Request('http://localhost/p/crm_lead_pool_recycle_rule_common/new');

    await expect(
      resolveDynamicPageAccessError(request, 'sales-token', 'crm_lead_pool_recycle_rule_common'),
    ).resolves.toBeNull();
    expect(fetchResult).toHaveBeenCalledWith(
      '/api/menu/by-path',
      expect.objectContaining({
        params: { path: '/p/crm_lead_pool_recycle_rule_common' },
        token: 'sales-token',
      }),
      request,
    );
  });

  it('blocks every child route when the parent menu lookup explicitly denies access', async () => {
    vi.mocked(fetchResult).mockResolvedValue({
      code: '403',
      message: 'Command permission denied',
    } as never);

    await expect(
      resolveDynamicPageAccessError(
        new Request('http://localhost/p/crm_lead_pool_recycle_rule_common/edit/record-1'),
        'sales-token',
        'crm_lead_pool_recycle_rule_common',
      ),
    ).resolves.toBe('Command permission denied');
  });

  it('preserves non-menu dynamic pages and anonymous compatibility', async () => {
    vi.mocked(fetchResult).mockResolvedValue({ code: '404', message: 'Not found' } as never);

    await expect(
      resolveDynamicPageAccessError(
        new Request('http://localhost/p/unmounted_model/new'),
        'token',
        'unmounted_model',
      ),
    ).resolves.toBeNull();
    await expect(
      resolveDynamicPageAccessError(
        new Request('http://localhost/p/unmounted_model/new'),
        null,
        'unmounted_model',
      ),
    ).resolves.toBeNull();
    expect(fetchResult).toHaveBeenCalledTimes(1);
  });
});
