package com.auraboot.framework.agentchat.handoff;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Computes permission boundaries for handoff hops.
 */
public final class HandoffPermissionPolicy {

    private HandoffPermissionPolicy() {
    }

    public static Set<String> intersect(Set<String> inheritedPermissions, Set<String> profilePermissions) {
        if (inheritedPermissions == null && profilePermissions == null) {
            return null;
        }
        if (inheritedPermissions == null) {
            return copy(profilePermissions);
        }
        if (profilePermissions == null) {
            return copy(inheritedPermissions);
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String permission : inheritedPermissions) {
            if (permission != null && profilePermissions.contains(permission)) {
                result.add(permission);
            }
        }
        return Collections.unmodifiableSet(result);
    }

    private static Set<String> copy(Set<String> permissions) {
        if (permissions == null) {
            return null;
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String permission : permissions) {
            if (permission != null && !permission.isBlank()) {
                result.add(permission);
            }
        }
        return Collections.unmodifiableSet(result);
    }
}
