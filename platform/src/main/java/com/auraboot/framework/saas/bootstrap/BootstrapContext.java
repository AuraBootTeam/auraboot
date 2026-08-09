package com.auraboot.framework.saas.bootstrap;

/**
 * Stable identifiers exposed to post-bootstrap extensions before the system is
 * marked initialized.
 */
public record BootstrapContext(
        Long systemTenantId,
        Long defaultTenantId,
        Long adminUserId,
        String adminUserPid) {
}
