package com.auraboot.framework.tenant.offboarding;

/** Public-identity context supplied to every member offboarding handler. */
public record TenantMemberOffboardingContext(
        Long tenantId,
        String sourceMemberPid,
        Long sourceUserId,
        String targetMemberPid,
        Long targetUserId,
        Long operatorUserId,
        TenantMemberOffboardingAction action) {
}
