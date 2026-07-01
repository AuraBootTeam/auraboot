package com.auraboot.framework.workbench.controller;

import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Authz guard test for {@link AnnouncementController}.
 *
 * <p>Audit 2026-06-28 (tenant-mutation-authorization-audit): announcements are
 * tenant-wide official notices. The mutating endpoints (create / update / delete) must
 * require {@link MetaPermission#ANNOUNCEMENT_MANAGE} so a non-admin tenant member cannot
 * post, edit, or delete tenant announcements. The list endpoint stays open to tenant
 * members. This test guards that the annotation cannot be dropped; enforcement itself
 * lives in the PermissionInterceptor.
 */
class AnnouncementControllerAuthzTest {

    private static final Set<String> GATED_MUTATIONS = Set.of("create", "update", "delete");
    private static final Set<String> OPEN_READS = Set.of("list");

    @Test
    void everyMutationRequiresAnnouncementManage() {
        for (String name : GATED_MUTATIONS) {
            Method m = findMethod(name);
            RequirePermission rp = m.getAnnotation(RequirePermission.class);
            assertNotNull(rp, "endpoint '" + name + "' must declare @RequirePermission");
            assertEquals(MetaPermission.ANNOUNCEMENT_MANAGE, rp.value(),
                    "endpoint '" + name + "' must require ANNOUNCEMENT_MANAGE");
        }
    }

    @Test
    void readEndpointsStayOpen() {
        for (String name : OPEN_READS) {
            Method m = findMethod(name);
            assertNull(m.getAnnotation(RequirePermission.class),
                    "read endpoint '" + name + "' should remain open (no @RequirePermission)");
        }
    }

    private static Method findMethod(String name) {
        for (Method m : AnnouncementController.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new AssertionError("endpoint method not found: " + name);
    }
}
