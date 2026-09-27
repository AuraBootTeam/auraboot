package com.auraboot.framework.openplatform.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenPlatformApplicationRoleTest {

    @Test
    void ownerCanManageApplicationAndMembers() {
        OpenPlatformApplicationRole role = OpenPlatformApplicationRole.fromStorage("owner");

        assertTrue(role.canManageMembers());
        assertTrue(role.canDisableApplication());
        assertTrue(role.canManageRuntime());
        assertTrue(role.canReadOperations());
    }

    @Test
    void maintainerCanManageRuntimeButNotOwnership() {
        OpenPlatformApplicationRole role = OpenPlatformApplicationRole.fromStorage("maintainer");

        assertFalse(role.canManageMembers());
        assertFalse(role.canDisableApplication());
        assertTrue(role.canManageRuntime());
        assertTrue(role.canReadOperations());
    }

    @Test
    void viewerIsOperationsReadOnly() {
        OpenPlatformApplicationRole role = OpenPlatformApplicationRole.fromStorage("viewer");

        assertFalse(role.canManageMembers());
        assertFalse(role.canDisableApplication());
        assertFalse(role.canManageRuntime());
        assertTrue(role.canReadOperations());
    }
}
