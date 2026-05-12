import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  fetchResult: vi.fn(),
}));

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: mocks.fetchResult,
}));

describe('commerce publicApi', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('fetches storefront bootstrap as a public request', async () => {
    mocks.fetchResult.mockResolvedValue({
      code: '0',
      data: {
        storeHandle: 'demo',
        storeName: 'Demo',
        locale: 'en-US',
        currencyCode: 'USD',
      },
    });

    const { fetchStorefrontBootstrap } = await import('./publicApi');
    const result = await fetchStorefrontBootstrap('demo', new Request('http://localhost/s/demo'));

    expect(result.error).toBeNull();
    expect(result.data?.storeHandle).toBe('demo');
    expect(mocks.fetchResult).toHaveBeenCalledWith(
      '/api/public/stores/{storeHandle}/bootstrap',
      {
        method: 'get',
        params: { storeHandle: 'demo' },
        skipAutoToken: true,
      },
      expect.any(Request),
    );
  });

  it('passes collection and search params to product listing', async () => {
    mocks.fetchResult.mockResolvedValue({
      code: '0',
      data: { items: [], total: 0 },
    });

    const { fetchStorefrontProducts } = await import('./publicApi');
    await fetchStorefrontProducts('demo', {
      collectionHandle: 'all',
      query: 'keyboard',
      pageSize: 12,
    });

    expect(mocks.fetchResult).toHaveBeenCalledWith(
      '/api/public/stores/{storeHandle}/products',
      {
        method: 'get',
        params: {
          storeHandle: 'demo',
          collectionHandle: 'all',
          query: 'keyboard',
          pageSize: 12,
        },
        skipAutoToken: true,
      },
      undefined,
    );
  });

  it('creates checkout through the public checkout contract', async () => {
    mocks.fetchResult.mockResolvedValue({
      code: '0',
      data: {
        id: 'chk_123',
        token: 'token',
        storeHandle: 'demo',
        status: 'draft',
      },
    });

    const { createCheckout } = await import('./publicApi');
    const result = await createCheckout({
      storeHandle: 'demo',
      cartId: 'cart_123',
    });

    expect(result.data?.id).toBe('chk_123');
    expect(mocks.fetchResult).toHaveBeenCalledWith(
      '/api/public/checkouts',
      {
        method: 'post',
        params: {
          storeHandle: 'demo',
          cartId: 'cart_123',
        },
        skipAutoToken: true,
      },
      undefined,
    );
  });

  it('normalizes failed public API responses into load state errors', async () => {
    mocks.fetchResult.mockResolvedValue({
      code: '404',
      message: 'Product not found',
      data: null,
    });

    const { fetchStorefrontProduct } = await import('./publicApi');
    const result = await fetchStorefrontProduct('demo', 'missing');

    expect(result.data).toBeNull();
    expect(result.error).toBe('Product not found');
  });
});
