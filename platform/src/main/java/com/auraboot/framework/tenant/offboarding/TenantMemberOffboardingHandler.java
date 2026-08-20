package com.auraboot.framework.tenant.offboarding;

/**
 * Extension point for product modules that own resources on behalf of tenant members.
 * Implementations must be tenant-scoped and idempotent within the surrounding transaction.
 */
public interface TenantMemberOffboardingHandler {

    TenantMemberOffboardingImpact inspect(TenantMemberOffboardingContext context);

    void transfer(TenantMemberOffboardingContext context);
}
