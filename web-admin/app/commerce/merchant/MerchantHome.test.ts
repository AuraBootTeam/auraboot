import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  fetchMerchantContext: vi.fn(),
}));

vi.mock('~/commerce/merchantApi', () => ({
  fetchMerchantContext: mocks.fetchMerchantContext,
}));

describe('MerchantHome loader', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.fetchMerchantContext.mockResolvedValue({
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
        operations: [
          { code: 'products', route: '/merchant/products', enabled: true },
          { code: 'orders', route: '/merchant/orders', enabled: true },
        ],
      },
      error: null,
    });
  });

  it('loads merchant context and derives the current section from the URL', async () => {
    const { loader } = await import('./MerchantHome');
    const request = new Request('http://localhost/merchant/products');

    const result = await loader({ request } as any);

    expect(result.section).toBe('products');
    expect(result.context.data?.selectedStore?.handle).toBe('demo');
    expect(mocks.fetchMerchantContext).toHaveBeenCalledWith(request);
  });

  it('uses overview section for the merchant root route', async () => {
    const { loader } = await import('./MerchantHome');

    const result = await loader({
      request: new Request('http://localhost/merchant'),
    } as any);

    expect(result.section).toBe('overview');
  });
});
