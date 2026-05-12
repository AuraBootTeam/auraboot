import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import type {
  CheckoutSession,
  CommerceLoadState,
  CompleteCheckoutInput,
  CompleteCheckoutResult,
  CreateCheckoutInput,
  CreateStorefrontCartInput,
  StorefrontBootstrap,
  StorefrontCart,
  StorefrontProductDetail,
  StorefrontProductList,
} from './types';

function resultMessage(result: {
  message?: string;
  desc?: string;
  code?: string | number;
}): string {
  return (
    result.message ||
    result.desc ||
    `Commerce API request failed (${String(result.code ?? 'unknown')})`
  );
}

async function publicGet<T>(
  path: string,
  params: Record<string, unknown>,
  request?: Request,
): Promise<CommerceLoadState<T>> {
  const result = await fetchResult<T>(
    path,
    {
      method: 'get',
      params,
      skipAutoToken: true,
    },
    request,
  );

  if (!ResultHelper.isSuccess(result) || !result.data) {
    return { data: null, error: resultMessage(result) };
  }

  return { data: result.data, error: null };
}

async function publicPost<T>(
  path: string,
  params: Record<string, unknown>,
  request?: Request,
): Promise<CommerceLoadState<T>> {
  const result = await fetchResult<T>(
    path,
    {
      method: 'post',
      params,
      skipAutoToken: true,
    },
    request,
  );

  if (!ResultHelper.isSuccess(result) || !result.data) {
    return { data: null, error: resultMessage(result) };
  }

  return { data: result.data, error: null };
}

export function fetchStorefrontBootstrap(
  storeHandle: string,
  request?: Request,
): Promise<CommerceLoadState<StorefrontBootstrap>> {
  return publicGet<StorefrontBootstrap>(
    '/api/public/stores/{storeHandle}/bootstrap',
    { storeHandle },
    request,
  );
}

export function fetchStorefrontProducts(
  storeHandle: string,
  params: {
    collectionHandle?: string;
    query?: string;
    cursor?: string;
    pageSize?: number;
  } = {},
  request?: Request,
): Promise<CommerceLoadState<StorefrontProductList>> {
  return publicGet<StorefrontProductList>(
    '/api/public/stores/{storeHandle}/products',
    {
      storeHandle,
      ...params,
    },
    request,
  );
}

export function fetchStorefrontProduct(
  storeHandle: string,
  handle: string,
  request?: Request,
): Promise<CommerceLoadState<StorefrontProductDetail>> {
  return publicGet<StorefrontProductDetail>(
    '/api/public/stores/{storeHandle}/products/{handle}',
    { storeHandle, handle },
    request,
  );
}

export function createStorefrontCart(
  storeHandle: string,
  input: CreateStorefrontCartInput,
  request?: Request,
): Promise<CommerceLoadState<StorefrontCart>> {
  return publicPost<StorefrontCart>(
    '/api/public/stores/{storeHandle}/cart',
    {
      storeHandle,
      ...input,
    },
    request,
  );
}

export function createCheckout(
  input: CreateCheckoutInput,
  request?: Request,
): Promise<CommerceLoadState<CheckoutSession>> {
  return publicPost<CheckoutSession>('/api/public/checkouts', { ...input }, request);
}

export function completeCheckout(
  checkoutId: string,
  input: CompleteCheckoutInput,
  request?: Request,
): Promise<CommerceLoadState<CompleteCheckoutResult>> {
  return publicPost<CompleteCheckoutResult>(
    '/api/public/checkouts/{checkoutId}/complete',
    {
      checkoutId,
      ...input,
    },
    request,
  );
}
