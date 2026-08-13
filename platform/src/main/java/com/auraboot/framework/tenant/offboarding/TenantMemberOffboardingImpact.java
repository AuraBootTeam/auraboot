package com.auraboot.framework.tenant.offboarding;

/** Aggregated resource ownership impact returned at the public member boundary. */
public record TenantMemberOffboardingImpact(
        String resourceType,
        String displayName,
        long ownedCount,
        boolean transferable) {
}
