import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  fetchResult: vi.fn(),
}));

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: mocks.fetchResult,
}));

describe('commerce merchantApi', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('fetches merchant context with the authenticated request token path', async () => {
    mocks.fetchResult.mockResolvedValue({
      code: '0',
      data: {
        tenantId: 101,
        selectedStore: {
          id: 'STORE_demo',
          handle: 'demo',
          name: 'Demo Store',
          status: 'active',
          storefrontPath: '/s/demo',
        },
        stores: [],
        operations: [],
      },
    });

    const { fetchMerchantContext } = await import('./merchantApi');
    const request = new Request('http://localhost/merchant');
    const result = await fetchMerchantContext(request);

    expect(result.error).toBeNull();
    expect(result.data?.selectedStore?.handle).toBe('demo');
    expect(mocks.fetchResult).toHaveBeenCalledWith(
      '/api/commerce/merchant/context',
      { method: 'get' },
      request,
    );
  });

  it('normalizes failed merchant context responses', async () => {
    mocks.fetchResult.mockResolvedValue({
      code: '403',
      message: 'Merchant tenant context is required',
      data: null,
    });

    const { fetchMerchantContext } = await import('./merchantApi');
    const result = await fetchMerchantContext();

    expect(result.data).toBeNull();
    expect(result.error).toBe('Merchant tenant context is required');
  });
});
