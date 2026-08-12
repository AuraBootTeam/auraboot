package com.auraboot.framework.tenant.dto;

/** Active same-tenant member eligible to receive resources during offboarding. */
public record TenantMemberOffboardingCandidate(
        String memberPid,
        String displayName,
        String email) {
}
