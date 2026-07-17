package com.auraboot.framework.permission.service;

import com.auraboot.framework.permission.engine.model.PermissionExplanation;
import com.auraboot.framework.permission.entity.PermissionAuditLog;

import java.util.Collection;
import java.util.List;

/**
 * Service for recording and querying permission evaluation audit logs.
 *
 * <p>Only DENY decisions are persisted to avoid log spam on the permission
 * check hot path.  All writes are fire-and-forget (async).
 */
public interface PermissionAuditService {

    /**
     * Persist a DENY decision from the permission evaluation pipeline.
     *
     * <p>Called asynchronously — must not block the caller or propagate exceptions.
     *
     * @param tenantId    the tenant context at evaluation time
     * @param explanation the full evaluation result to log
     */
    void logEvaluation(Long tenantId, PermissionExplanation explanation);

    /**
     * Persist a DENY-style audit row when field-level permissions remove fields
     * from a dynamic data response.
     *
     * <p>The trace must contain field references only, never field values.
     */
    void logFieldGovernanceFilter(
            Long tenantId,
            Long memberId,
            String resourceCode,
            String actionCode,
            Long recordId,
            String recordPid,
            Collection<String> hiddenFields);

    /**
     * Return the most recent audit entries for a tenant (newest first).
     *
     * @param tenantId the tenant to query
     * @param limit    max number of entries to return
     */
    List<PermissionAuditLog> getRecentLogs(Long tenantId, int limit);

    /**
     * Return audit entries for a specific member (newest first).
     *
     * @param tenantId the tenant to query
     * @param memberId the member whose decisions to return
     * @param limit    max number of entries to return
     */
    List<PermissionAuditLog> getLogsByMember(Long tenantId, Long memberId, int limit);

    /**
     * Return audit entries for a specific resource (newest first).
     *
     * @param tenantId     the tenant to query
     * @param resourceCode the resource code to filter by
     * @param limit        max number of entries to return
     */
    List<PermissionAuditLog> getLogsByResource(Long tenantId, String resourceCode, int limit);

    /**
     * Return audit entries linked to a Rule Center trace ID (newest first).
     *
     * @param tenantId the tenant to query
     * @param traceId  the Rule Center decision trace ID to filter by
     * @param limit    max number of entries to return
     */
    List<PermissionAuditLog> getLogsByTraceId(Long tenantId, String traceId, int limit);
}
