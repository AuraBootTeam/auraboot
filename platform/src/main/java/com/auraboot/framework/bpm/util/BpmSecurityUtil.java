package com.auraboot.framework.bpm.util;

import com.auraboot.framework.application.tenant.MetaContext;
import lombok.extern.slf4j.Slf4j;

/**
 * BPM security utility for extracting current user identity.
 */
@Slf4j
public final class BpmSecurityUtil {

    private BpmSecurityUtil() {}

    /**
     * Get the current authenticated username from MetaContext.
     *
     * @return username, or "system" if no context is available
     */
    public static String getCurrentUserId() {
        if (MetaContext.exists()) {
            String username = MetaContext.getCurrentUsername();
            if (username != null) {
                return username;
            }
            log.debug("MetaContext has no username, returning 'system' as BPM actor");
            return "system";
        }
        log.debug("No MetaContext available, returning 'system' as BPM actor");
        return "system";
    }

    /**
     * Get the current authenticated user's numeric ID from MetaContext.
     *
     * @return userId as Long, or null if no context is available
     */
    public static Long getCurrentUserIdAsLong() {
        if (MetaContext.exists()) {
            return MetaContext.getCurrentUserId();
        }
        log.debug("No MetaContext available, returning null as BPM actor id");
        return null;
    }
}
