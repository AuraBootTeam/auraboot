package com.auraboot.framework.party.service;

import java.util.Set;

/**
 * Explicit Party-domain policy seam. Party is intentionally not a generic SQL
 * interceptor: each Party-aware domain opts into this service at its resource boundary.
 */
public interface PartyAuthorizationService {
    Set<Long> resolveActivePartyRoleIds(
            Long tenantId,
            Long tenantMemberId,
            Long actorPartyId,
            Long partyMembershipId);

    void requireCurrentActorCapability(String capabilityCode);

    void requireCurrentActorOwnsResource(Long resourcePartyId);
}
