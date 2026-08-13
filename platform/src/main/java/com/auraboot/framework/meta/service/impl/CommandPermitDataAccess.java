package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;

import java.util.Map;

/**
 * Executes the row-scope grade already decided by the command permit plan.
 *
 * <p>This class deliberately contains no policy lookup. A command plan's grade is authoritative for
 * its target model only; another model touched by the handler receives {@code null} and evaluates
 * its own policy through the normal data-access path. The legacy explicit global bridge has no
 * target model and intentionally retains its process-wide scope semantics.</p>
 */
public final class CommandPermitDataAccess {

    private CommandPermitDataAccess() {
    }

    /** Whether the authoritative plan grade permits this already-loaded record. */
    public static boolean permitsRecord(Map<String, Object> record, Long userId) {
        return permitsRecordForScope(MetaContext.getCommandPermitScope(), record, userId);
    }

    /** Whether the command plan grade for {@code modelCode} permits this already-loaded record. */
    public static boolean permitsRecord(String modelCode, Map<String, Object> record, Long userId) {
        return permitsRecordForScope(
                MetaContext.getCommandPermitScopeFor(modelCode),
                MetaContext.getCommandPermitTargetFor(modelCode),
                record,
                userId);
    }

    private static boolean permitsRecordForScope(
            String scope,
            Map<String, Object> record,
            Long userId) {
        return permitsRecordForScope(scope, null, record, userId);
    }

    private static boolean permitsRecordForScope(
            String scope,
            String targetRecordPid,
            Map<String, Object> record,
            Long userId) {
        if ("ALL".equals(scope)) {
            return true;
        }
        if ("TARGET".equals(scope)) {
            Object recordPid = record != null ? record.get("pid") : null;
            return targetRecordPid != null
                    && recordPid != null
                    && targetRecordPid.equals(String.valueOf(recordPid));
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
        return rowFilterForScope(MetaContext.getCommandPermitScope(), userId);
    }

    /**
     * SQL fragment for the command plan grade when it applies to {@code modelCode}, or {@code null}
     * when that model must use its own policy.
     */
    public static String rowFilter(String modelCode, Long userId) {
        return rowFilterForScope(
                MetaContext.getCommandPermitScopeFor(modelCode),
                MetaContext.getCommandPermitTargetFor(modelCode),
                userId);
    }

    private static String rowFilterForScope(String scope, Long userId) {
        return rowFilterForScope(scope, null, userId);
    }

    private static String rowFilterForScope(String scope, String targetRecordPid, Long userId) {
        if (scope == null) {
            return null;
        }
        if ("ALL".equals(scope)) {
            return "";
        }
        if ("SELF".equals(scope) && userId != null) {
            return "AND created_by = " + userId;
        }
        if ("TARGET".equals(scope) && targetRecordPid != null && !targetRecordPid.isBlank()) {
            return "AND pid = '" + targetRecordPid.replace("'", "''") + "'";
        }
        return "AND 1 = 0";
    }
}
