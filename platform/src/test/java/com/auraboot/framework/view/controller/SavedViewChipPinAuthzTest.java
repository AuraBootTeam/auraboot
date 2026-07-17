package com.auraboot.framework.view.controller;

import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Authz guard for the quick-filter chip-pin endpoints on {@link SavedViewController}.
 *
 * <p>Pinning a view you can see is a read-level action, so {@code pinAsChip} /
 * {@code unpinChip} / {@code getChipPins} must all declare
 * {@link MetaPermission#VIEW_READ}: an anonymous caller is 401 and a member
 * lacking view-read is 403 at the {@code PermissionInterceptor}. This test guards
 * that the baseline annotation cannot be silently dropped; HTTP enforcement itself
 * lives in the interceptor (mirroring {@code NotificationRuleControllerAuthzTest}).
 *
 * <p>The stricter <em>team</em>-pin gate ({@code VIEW_TEAM_MANAGE} + team
 * membership) is enforced in {@code SavedViewChipPinServiceImpl} and covered by
 * {@code SavedViewChipPinServiceTest} + {@code SavedViewChipPinTeamIT}.
 */
class SavedViewChipPinAuthzTest {

    /** Chip-pin endpoint methods — all read-gated. */
    private static final Set<String> READ_GATED = Set.of("pinAsChip", "unpinChip", "getChipPins");

    @Test
    void everyChipPinEndpointRequiresViewRead() {
        for (String name : READ_GATED) {
            Method m = findMethod(name);
            RequirePermission rp = m.getAnnotation(RequirePermission.class);
            assertNotNull(rp, "endpoint '" + name + "' must declare @RequirePermission");
            assertEquals(MetaPermission.VIEW_READ, rp.value(),
                    "endpoint '" + name + "' must require VIEW_READ");
        }
    }

    private static Method findMethod(String name) {
        for (Method m : SavedViewController.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new AssertionError("endpoint method not found: " + name);
    }
}
