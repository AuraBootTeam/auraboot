package com.auraboot.framework.notification.controller;

import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Authz guard test for {@link NotificationRuleController}.
 *
 * <p>Audit 2026-06-28 (tenant-mutation-authorization-audit): notification rules are
 * tenant-wide alerting config. The mutating endpoints (create / update / delete /
 * toggle) must require {@link MetaPermission#NOTIFICATION_RULE_MANAGE} so a non-admin
 * tenant member cannot silently disable or rewrite alerting. Read endpoints
 * (list / get / test-evaluate) stay open to tenant members. This test guards that the
 * annotation cannot be dropped; enforcement itself lives in the PermissionInterceptor.
 */
class NotificationRuleControllerAuthzTest {

    /** Endpoint methods that mutate tenant-wide rule state — must be gated. */
    private static final Set<String> GATED_MUTATIONS = Set.of("create", "update", "delete", "toggle");

    /** Read-only endpoint methods — intentionally left open to tenant members. */
    private static final Set<String> OPEN_READS = Set.of("list", "get", "test");

    @Test
    void everyMutationRequiresNotificationRuleManage() {
        for (String name : GATED_MUTATIONS) {
            Method m = findMethod(name);
            RequirePermission rp = m.getAnnotation(RequirePermission.class);
            assertNotNull(rp, "endpoint '" + name + "' must declare @RequirePermission");
            assertEquals(MetaPermission.NOTIFICATION_RULE_MANAGE, rp.value(),
                    "endpoint '" + name + "' must require NOTIFICATION_RULE_MANAGE");
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
        for (Method m : NotificationRuleController.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new AssertionError("endpoint method not found: " + name);
    }
}
