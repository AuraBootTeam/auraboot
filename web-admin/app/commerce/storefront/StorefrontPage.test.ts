import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  fetchStorefrontBootstrap: vi.fn(),
  fetchStorefrontProduct: vi.fn(),
  fetchStorefrontProducts: vi.fn(),
}));

vi.mock('~/commerce/publicApi', () => ({
  fetchStorefrontBootstrap: mocks.fetchStorefrontBootstrap,
  fetchStorefrontProduct: mocks.fetchStorefrontProduct,
  fetchStorefrontProducts: mocks.fetchStorefrontProducts,
}));

describe('StorefrontPage loader', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.fetchStorefrontBootstrap.mockResolvedValue({
      data: {
        storeHandle: 'demo',
        storeName: 'Demo',
        locale: 'en-US',
        currencyCode: 'USD',
      },
      error: null,
    });
    mocks.fetchStorefrontProducts.mockResolvedValue({
      data: { items: [], total: 0 },
      error: null,
    });
    mocks.fetchStorefrontProduct.mockResolvedValue({
      data: {
        id: 'prod_1',
        handle: 'sample-product',
        title: 'Sample product',
      },
      error: null,
    });
  });

  it('loads bootstrap and product listing for the storefront home route', async () => {
    const { loader } = await import('./StorefrontPage');
    const result = await loader({
      params: { storeHandle: 'demo' },
      request: new Request('http://localhost/s/demo'),
    } as any);

    expect(result.kind).toBe('home');
    expect(result.storeHandle).toBe('demo');
    expect(mocks.fetchStorefrontBootstrap).toHaveBeenCalledWith('demo', expect.any(Request));
    expect(mocks.fetchStorefrontProducts).toHaveBeenCalledWith(
      'demo',
      {
        collectionHandle: undefined,
        query: undefined,
        pageSize: 12,
      },
      expect.any(Request),
    );
    expect(mocks.fetchStorefrontProduct).not.toHaveBeenCalled();
  });

  it('loads product detail for product routes', async () => {
    const { loader } = await import('./StorefrontPage');
    const result = await loader({
      params: { storeHandle: 'demo', '*': 'products/sample-product' },
      request: new Request('http://localhost/s/demo/products/sample-product'),
    } as any);

    expect(result.kind).toBe('product');
    expect(result.resourceHandle).toBe('sample-product');
    expect(mocks.fetchStorefrontProduct).toHaveBeenCalledWith(
      'demo',
      'sample-product',
      expect.any(Request),
    );
    expect(mocks.fetchStorefrontProducts).not.toHaveBeenCalled();
  });

  it('passes collection handle and search query to listing routes', async () => {
    const { loader } = await import('./StorefrontPage');
    const result = await loader({
      params: { storeHandle: 'demo', '*': 'collections/all' },
      request: new Request('http://localhost/s/demo/collections/all?q=keyboard'),
    } as any);

    expect(result.kind).toBe('collection');
    expect(result.resourceHandle).toBe('all');
    expect(mocks.fetchStorefrontProducts).toHaveBeenCalledWith(
      'demo',
      {
        collectionHandle: 'all',
        query: 'keyboard',
        pageSize: 12,
      },
      expect.any(Request),
    );
  });
});
