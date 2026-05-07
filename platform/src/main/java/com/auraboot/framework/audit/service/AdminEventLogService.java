package com.auraboot.framework.audit.service;

import com.auraboot.framework.audit.entity.AdminEventLog;

import java.util.List;

/**
 * Service for recording cross-cutting administrative-action audit entries.
 *
 * <p>Domain code (e.g. {@code EnvironmentServiceImpl.lock}) calls
 * {@link #record(AdminEventLog)} after a successful (or failed) administrative
 * action; the audit row is persisted to {@code ab_admin_event_log} with
 * tenant scoping derived from {@link com.auraboot.framework.application.tenant.MetaContext}
 * if not already set on the entity.
 *
 * <p>Writes are <strong>fire-and-forget</strong>:
 * <ul>
 *   <li>The implementation must <em>never</em> throw or propagate exceptions
 *       to the caller. Audit-write failure must not break the action being
 *       audited.</li>
 *   <li>The implementation may run synchronously or via {@code @Async}.
 *       Callers must not depend on the row being immediately visible.</li>
 * </ul>
 */
public interface AdminEventLogService {

    /**
     * Persist an administrative-action audit entry.
     *
     * <p>Required fields on the input: {@code actionType}, {@code success}.
     * If {@code tenantId} is null, the implementation falls back to
     * {@link com.auraboot.framework.application.tenant.MetaContext#getCurrentTenantId()}.
     * If {@code actorUserId} is null, falls back to {@code MetaContext.getCurrentUserId()}.
     * If {@code createdAt} is null, the implementation sets it to "now".
     *
     * @param log entry to record (mutated to fill defaults; caller may discard the reference)
     */
    void record(AdminEventLog log);

    /**
     * Return the {@code limit} most recent entries for a tenant (newest first).
     *
     * @param tenantId tenant to query (must not be null)
     * @param limit    max number of entries to return; clamped to [1, 1000]
     */
    List<AdminEventLog> recentByTenant(Long tenantId, int limit);

    /**
     * Return entries scoped to a specific resource (newest first).
     *
     * @param tenantId     tenant to query
     * @param resourceType e.g. {@code environment}
     * @param resourcePid  pid of the resource
     * @param limit        max entries to return; clamped to [1, 1000]
     */
    List<AdminEventLog> byResource(Long tenantId, String resourceType, String resourcePid, int limit);
}
