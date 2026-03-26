import type { ActionFunctionArgs } from 'react-router';
import { fetchResult } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { getTokenFromRequest } from '~/services/session';

export async function action({ request }: ActionFunctionArgs) {
  const token = await getTokenFromRequest(request);
  const formData = await request.formData();
  const action = formData.get('action') as string;

  try {
    let result;

    switch (action) {
      case 'getCities':
        const provinceCode = formData.get('provinceCode') as string;
        result = await fetchResult('/api/stores/address/cities', {
          method: 'get',
          params: { provinceCode },
          token,
        });
        break;

      case 'getDistricts':
        const cityCode = formData.get('cityCode') as string;
        result = await fetchResult('/api/stores/address/districts', {
          method: 'get',
          params: { cityCode },
          token,
        });
        break;

      case 'getStreets':
        const districtCode = formData.get('districtCode') as string;
        result = await fetchResult('/api/stores/address/streets', {
          method: 'get',
          params: { districtCode },
          token,
        });
        break;

      default:
        return new Response(JSON.stringify({ action, data: [] }), {
          headers: { 'Content-Type': 'application/json' },
        });
    }

    if (ResultHelper.isSuccess(result)) {
      return new Response(JSON.stringify({ action, data: result.data || [] }), {
        headers: { 'Content-Type': 'application/json' },
      });
    } else {
      return new Response(JSON.stringify({ action, data: [], error: result.desc }), {
        headers: { 'Content-Type': 'application/json' },
      });
    }
  } catch (error) {
    console.error('地址数据获取失败:', error);
    return new Response(JSON.stringify({ action, data: [], error: '获取数据失败' }), {
      headers: { 'Content-Type': 'application/json' },
    });
  }
}
