import { internalRequest } from '~/shared/services/http-client/internal-api';
import { ResultHelper, type Result } from '~/utils/type';
import type { CapabilityGroup, CapabilitySelectionPreview } from './types';

function unwrap<T>(result: Result<T>, message: string): T {
  if (ResultHelper.isSuccess(result) && result.data != null) return result.data;
  throw new Error(result.desc || message);
}
function record(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}
function strings(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string');
}
function assertCapabilities(value: unknown): void {
  if (!Array.isArray(value)) throw new Error('Invalid capability list');
  const codes = new Set<string>();
  for (const cap of value) {
    if (
      !record(cap) ||
      typeof cap.code !== 'string' ||
      !cap.code ||
      codes.has(cap.code) ||
      typeof cap.label !== 'string' ||
      !(cap.group === null || typeof cap.group === 'string') ||
      typeof cap.granted !== 'boolean' ||
      typeof cap.sensitive !== 'boolean' ||
      typeof cap.conventionDerived !== 'boolean' ||
      !strings(cap.includes) ||
      !strings(cap.missingCodes) ||
      !['none', 'partial', 'full'].includes(String(cap.authorizationState)) ||
      typeof cap.displayOrder !== 'number' ||
      typeof cap.displayGroupOrder !== 'number'
    )
      throw new Error('Invalid capability response');
    for (const field of ['description', 'tier'])
      if (!(cap[field] === null || typeof cap[field] === 'string'))
        throw new Error(`Invalid capability ${field}`);
    for (const field of ['localizedLabels', 'localizedDescriptions']) {
      const labels = cap[field];
      if (!record(labels) || Object.values(labels).some((label) => typeof label !== 'string'))
        throw new Error(`Invalid capability ${field}`);
    }
    if (!(cap.unlockedMenus === null || strings(cap.unlockedMenus)))
      throw new Error('Invalid capability menus');
    codes.add(cap.code);
  }
}
function groups(value: CapabilityGroup[]): CapabilityGroup[] {
  if (!Array.isArray(value)) throw new Error('Invalid capability groups');
  for (const group of value) {
    if (!record(group) || !(group.group === null || typeof group.group === 'string'))
      throw new Error('Invalid capability group');
    assertCapabilities(group.capabilities);
  }
  return value;
}
function query(rolePid: string, revokePartial: string[] = []) {
  if (typeof rolePid !== 'string' || !rolePid) throw new Error('A public role PID is required');
  return { rolePid, revokePartial };
}

/** PID queries and repeated revocations retain the shared SSR/session transport. */
export class CapabilityService {
  async getForRole(rolePid: string, request?: Request): Promise<CapabilityGroup[]> {
    return groups(
      unwrap(
        await internalRequest('get', '/api/permission/capabilities', {
          query: query(rolePid),
          request,
        }),
        'Failed to fetch capabilities',
      ),
    );
  }
  async previewSelection(
    rolePid: string,
    selectedCapabilityCodes: string[],
    revokePartial: string[] = [],
  ): Promise<CapabilitySelectionPreview> {
    const plan = unwrap(
      await internalRequest('post', '/api/permission/capabilities/preview', {
        query: query(rolePid, revokePartial),
        body: selectedCapabilityCodes,
      }),
      'Failed to preview permission changes',
    );
    if (
      !record(plan) ||
      !strings(plan.grantedCodes) ||
      !strings(plan.revokedCodes) ||
      !strings(plan.preservedCodes) ||
      !strings(plan.relatedMenus)
    )
      throw new Error('Invalid capability preview');
    assertCapabilities(plan.resultingCapabilities);
    return plan;
  }
  async applySelection(
    rolePid: string,
    selectedCapabilityCodes: string[],
    request?: Request,
    revokePartial: string[] = [],
  ): Promise<CapabilityGroup[]> {
    return groups(
      unwrap(
        await internalRequest('put', '/api/permission/capabilities', {
          query: query(rolePid, revokePartial),
          body: selectedCapabilityCodes,
          request,
        }),
        'Failed to apply capability selection',
      ),
    );
  }
}
export const capabilityService = new CapabilityService();
