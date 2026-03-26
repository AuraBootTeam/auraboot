package com.auraboot.framework.bpm.util;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * BPM security utility for extracting current user identity.
 */
public final class BpmSecurityUtil {

    private BpmSecurityUtil() {}

    /**
     * Get the current authenticated user ID from Spring Security context.
     *
     * @return user ID, or "system" if not authenticated
     */
    public static String getCurrentUserId() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()) {
                return auth.getName();
            }
        } catch (Exception ignored) {
        }
        return "system";
    }
}
