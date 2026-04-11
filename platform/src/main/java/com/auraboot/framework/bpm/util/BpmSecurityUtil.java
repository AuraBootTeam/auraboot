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
            return MetaContext.getCurrentUsername();
        }
        log.debug("No MetaContext available, returning 'system' as BPM actor");
        return "system";
    }
}
