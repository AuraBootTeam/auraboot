package com.auraboot.framework.tenant.offboarding;

/** Member lifecycle transitions that can strand tenant-owned resources. */
public enum TenantMemberOffboardingAction {
    DEACTIVATE,
    SUSPEND,
    REMOVE
}
