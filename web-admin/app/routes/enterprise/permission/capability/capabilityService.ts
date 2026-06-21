import { get, put } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import type { CapabilityGroup } from './types';

function unwrap<T>(result: { code: string | number; desc?: string; data: T | null }, errorMsg: string): T {
  if (ResultHelper.isSuccess(result) && result.data !== null) {
    return result.data;
  }
  throw new Error(result.desc || errorMsg);
}

/**
 * Client for the permission v2 capability endpoints. roleId is a query param; for applySelection the
 * selected capability codes are the request body (http-client passes the 2nd arg as the body for PUT).
 */
export class CapabilityService {
  private baseUrl = '/api/permission/capabilities';

  async getForRole(roleId: string, request?: Request): Promise<CapabilityGroup[]> {
    const result = await get<CapabilityGroup[]>(
      `${this.baseUrl}?roleId=${encodeURIComponent(roleId)}`,
      undefined,
      undefined,
      request,
    );
    return unwrap(result, 'Failed to fetch capabilities');
  }

  async applySelection(
    roleId: string,
    selectedCapabilityCodes: string[],
    request?: Request,
  ): Promise<CapabilityGroup[]> {
    const result = await put<CapabilityGroup[]>(
      `${this.baseUrl}?roleId=${encodeURIComponent(roleId)}`,
      selectedCapabilityCodes,
      undefined,
      request,
    );
    return unwrap(result, 'Failed to apply capability selection');
  }
}

export const capabilityService = new CapabilityService();
