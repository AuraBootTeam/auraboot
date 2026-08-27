import { fetchTimeoutSignal } from '~/utils/fetchTimeout';

export interface AccessPolicy {
  deploymentMode: 'single' | 'multi' | 'hybrid';
  userRegistrationPolicy: 'open' | 'invite_only' | 'closed';
  tenantProvisioningPolicy: 'disabled' | 'platform_managed' | 'self_service';
  partyCreationPolicy: 'disabled' | 'approval_required' | 'auto_approve';
  partyInvitationEnabled: boolean;
  actorSwitchEnabled: boolean;
}

export const CLOSED_ACCESS_POLICY: AccessPolicy = {
  deploymentMode: 'single',
  userRegistrationPolicy: 'closed',
  tenantProvisioningPolicy: 'disabled',
  partyCreationPolicy: 'approval_required',
  partyInvitationEnabled: false,
  actorSwitchEnabled: false,
};

export async function fetchAccessPolicy(): Promise<AccessPolicy> {
  try {
    const apiUrl =
      process.env.BFF_INTERNAL_URL || process.env.SPRING_BOOT_URL || 'http://127.0.0.1:6443';
    const response = await fetch(`${apiUrl}/api/auth/access-policy`, {
      signal: fetchTimeoutSignal(),
    });
    if (!response.ok) return CLOSED_ACCESS_POLICY;
    const result = await response.json();
    return result?.data ? (result.data as AccessPolicy) : CLOSED_ACCESS_POLICY;
  } catch {
    return CLOSED_ACCESS_POLICY;
  }
}

export function isPublicRegistrationOpen(policy: AccessPolicy): boolean {
  return policy.userRegistrationPolicy === 'open';
}

export function canSelfProvisionTenant(policy: AccessPolicy): boolean {
  return policy.deploymentMode !== 'single' && policy.tenantProvisioningPolicy === 'self_service';
}
