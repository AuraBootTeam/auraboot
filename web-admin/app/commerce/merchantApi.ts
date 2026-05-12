import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import type { CommerceLoadState, MerchantCommerceContext } from './types';

function resultMessage(result: {
  message?: string;
  desc?: string;
  code?: string | number;
}): string {
  return (
    result.message ||
    result.desc ||
    `Merchant API request failed (${String(result.code ?? 'unknown')})`
  );
}

export async function fetchMerchantContext(
  request?: Request,
): Promise<CommerceLoadState<MerchantCommerceContext>> {
  const result = await fetchResult<MerchantCommerceContext>(
    '/api/commerce/merchant/context',
    { method: 'get' },
    request,
  );

  if (!ResultHelper.isSuccess(result) || !result.data) {
    return { data: null, error: resultMessage(result) };
  }

  return { data: result.data, error: null };
}
