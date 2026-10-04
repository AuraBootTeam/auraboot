package com.auraboot.framework.i18n.controller;

import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The i18n pack is shared tenant-wide infrastructure: mutation endpoints must
 * require the system-management authority so random tenant members cannot
 * rewrite every UI label (review semantics: system admins review, everyone
 * else reads via GET /api/i18n/{locale}).
 */
class I18nAdminControllerPermissionTest {

    @Test
    void adminControllerRequiresSystemManagement() {
        RequirePermission annotation = I18nAdminController.class.getAnnotation(RequirePermission.class);
        assertNotNull(annotation, "I18nAdminController must carry @RequirePermission");
        assertEquals(MetaPermission.SYSTEM_MANAGEMENT, annotation.value());
    }

    @Test
    void permissionConstantMatchesRegistryConvention() {
        // Namespaced literal kept in sync with the permission catalog.
        assertEquals("system_management", MetaPermission.SYSTEM_MANAGEMENT);
    }
}
