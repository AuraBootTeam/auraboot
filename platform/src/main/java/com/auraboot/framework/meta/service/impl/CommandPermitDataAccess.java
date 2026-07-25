package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;

import java.util.Map;

/**
 * Executes the row-scope grade already decided by the command permit plan.
 *
 * <p>This class deliberately contains no policy lookup. When a command permit scope is present,
 * consulting Rule Center, the row-policy engine, or data-scope again would turn execution back into
 * a second authorization decision. Direct non-command calls receive {@code null} from
 * {@link #rowFilter(Long)} and keep the legacy policy path.</p>
 */
public final class CommandPermitDataAccess {

    private CommandPermitDataAccess() {
    }

    /** Whether the authoritative plan grade permits this already-loaded record. */
    public static boolean permitsRecord(Map<String, Object> record, Long userId) {
        String scope = MetaContext.getCommandPermitScope();
        if ("ALL".equals(scope)) {
            return true;
        }
        if (!"SELF".equals(scope) || record == null || userId == null) {
            return false;
        }
        Object owner = record.get("created_by");
        return owner != null && String.valueOf(userId).equals(String.valueOf(owner));
    }

    /**
     * SQL fragment for the authoritative plan grade, or {@code null} when no valid plan is active
     * and the caller must use the legacy engine.
     */
    public static String rowFilter(Long userId) {
        String scope = MetaContext.getCommandPermitScope();
        if (scope == null) {
            return null;
        }
        if ("ALL".equals(scope)) {
            return "";
        }
        if ("SELF".equals(scope) && userId != null) {
            return "AND created_by = " + userId;
        }
        return "AND 1 = 0";
    }
}
