package com.auraboot.framework.agent.crosstenant;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes one row to {@code ab_cross_tenant_spawn_audit} per cross-tenant
 * spawn decision (allowed or denied).
 *
 * <p>Propagation is {@link Propagation#REQUIRES_NEW} so the audit row
 * survives even when the surrounding spawn transaction rolls back — denied
 * spawns are exactly the case where the caller throws and the outer TX
 * unwinds; we still want the audit row in the database for the operator to
 * inspect.
 *
 * <p><b>Worktree-isolation:</b> this writer is invoked from inside
 * {@link com.auraboot.framework.agent.service.SubAgentRunner#spawn} and from
 * the admin controller's runtime path; both run on the host PG already
 * provisioned by the migration. No connection-pool tuning required.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrossTenantSpawnAuditWriter {

    private final JdbcTemplate jdbc;

    /**
     * Insert a single audit row. Tolerates {@code grantId == null} (denied
     * decisions have no grant) and {@code childRunPid == null} (spawn aborted
     * before the child row was created).
     *
     * <p>{@code REQUIRES_NEW} ensures the row is committed independently of
     * the caller's transaction outcome.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(Long grantId,
                      Long parentTenantId,
                      Long childTenantId,
                      String parentRunPid,
                      String childRunPid,
                      String decision,
                      String errorMessage) {
        try {
            jdbc.update(
                    "INSERT INTO ab_cross_tenant_spawn_audit "
                            + "(grant_id, parent_tenant_id, child_tenant_id, "
                            + " parent_run_pid, child_run_pid, decision, error_message) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    grantId, parentTenantId, childTenantId,
                    parentRunPid, childRunPid, decision, errorMessage);
        } catch (RuntimeException e) {
            // Best-effort: a failed audit write must not mask the underlying
            // ACL decision. Log loudly so operators can investigate; the
            // surrounding caller still throws / proceeds based on the
            // decision itself.
            log.error("CrossTenantSpawnAuditWriter: failed to insert audit row "
                            + "(parent_tenant={}, child_tenant={}, decision={}): {}",
                    parentTenantId, childTenantId, decision, e.getMessage(), e);
        }
    }
}
