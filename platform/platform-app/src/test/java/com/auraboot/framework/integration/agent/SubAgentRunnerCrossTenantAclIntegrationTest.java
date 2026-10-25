package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.crosstenant.CrossTenantAclDeniedException;
import com.auraboot.framework.agent.crosstenant.CrossTenantAclService;
import com.auraboot.framework.agent.crosstenant.CrossTenantDecision;
import com.auraboot.framework.agent.crosstenant.CrossTenantGrantType;
import com.auraboot.framework.agent.service.SubAgentRunner;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C.2 — verifies {@link SubAgentRunner#spawn} consults the cross-tenant ACL
 * before allowing a parent in tenant A to spawn a child under tenant B.
 *
 * <p>Cases:
 * <ul>
 *   <li>A — same-tenant spawn unaffected (regression guard against the new
 *           ACL gate slowing or breaking the hot path).</li>
 *   <li>B — cross-tenant + active grant → spawn succeeds, audit row written
 *           with decision='allowed' and child_run_pid populated.</li>
 *   <li>C — cross-tenant + no grant → throws CrossTenantAclDeniedException,
 *           audit row written with decision='denied_no_grant', no child run
 *           rows created.</li>
 *   <li>D — cross-tenant + expired grant → denied_expired (no child rows).</li>
 *   <li>E — cross-tenant + revoked grant → denied_no_grant (revoked rows
 *           are filtered out of the active-grant SELECT, see
 *           {@code CrossTenantAclServiceIntegrationTest#caseD_revoked}).</li>
 *   <li>F — Q9: SYSTEM_TENANT (id=1) crossing to a business tenant has no
 *           implicit bypass. Without a grant the spawn is denied; with a
 *           grant it succeeds. Same rules as any other tenant pair.</li>
 * </ul>
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("SubAgentRunner cross-tenant ACL (C.2)")
class SubAgentRunnerCrossTenantAclIntegrationTest extends BaseIntegrationTest {

    /**
     * Hard-coded SYSTEM tenant id used by the bootstrap path. Same value as
     * {@code SystemTenantConstants} would expose if it existed; kept inline
     * here because the test only needs the number and avoiding a constant
     * dependency keeps the worktree focused.
     */
    private static final long SYSTEM_TENANT_ID = 1L;

    @Autowired private SubAgentRunner subAgentRunner;
    @Autowired private CrossTenantAclService aclService;
    @Autowired private JdbcTemplate jdbc;

    private Long parentTenant;
    private Long childTenant;

    @BeforeEach
    void setup() {
        long base = 9_780_000L + System.nanoTime() % 100_000;
        parentTenant = base;
        childTenant = base + 1L;
        // Caller MetaContext is the child tenant — that's what
        // SubAgentRunner.spawn(tenantId=...) maps to.
        MetaContext.setContext(childTenant, testUser.getId(),
                testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void cleanup() {
        for (Long t : List.of(parentTenant, childTenant, SYSTEM_TENANT_ID)) {
            jdbc.update("DELETE FROM ab_agent_run WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM ab_agent_task WHERE tenant_id = ?", t);
        }
        jdbc.update("DELETE FROM ab_cross_tenant_spawn_audit "
                + "WHERE parent_tenant_id IN (?, ?, ?) OR child_tenant_id IN (?, ?, ?)",
                parentTenant, childTenant, SYSTEM_TENANT_ID,
                parentTenant, childTenant, SYSTEM_TENANT_ID);
        jdbc.update("DELETE FROM ab_cross_tenant_grant "
                + "WHERE parent_tenant_id IN (?, ?, ?)",
                parentTenant, childTenant, SYSTEM_TENANT_ID);
        // ACL caches across all keys we touched.
        aclService.invalidate(parentTenant, childTenant, CrossTenantGrantType.SPAWN_SUB_AGENT);
        aclService.invalidate(SYSTEM_TENANT_ID, childTenant, CrossTenantGrantType.SPAWN_SUB_AGENT);
        MetaContext.clear();
    }

    private String seedParentRun(Long parentTenantId) {
        String runPid = UniqueIdGenerator.generate();
        String taskPid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_task (pid, tenant_id, title, task_status, "
                        + " assignee_type, assignee_id, created_at, updated_at, created_by) "
                        + "VALUES (?, ?, 'parent', 'in_progress', 'agent', 'aurabot', NOW(), NOW(), ?)",
                taskPid, parentTenantId, testUser.getId());
        jdbc.update("INSERT INTO ab_agent_run (pid, tenant_id, task_id, agent_id, run_status, "
                        + " started_at, created_at, updated_at, created_by) "
                        + "VALUES (?, ?, ?, 'aurabot', 'running', NOW(), NOW(), NOW(), ?)",
                runPid, parentTenantId, taskPid, testUser.getId());
        return runPid;
    }

    private Long seedGrant(Long parent, Long child, Instant expiresAt, Instant revokedAt) {
        Long id = jdbc.queryForObject(
                "INSERT INTO ab_cross_tenant_grant "
                        + "(parent_tenant_id, child_tenant_id, grant_type, granted_by, "
                        + " granted_at, expires_at, revoked_at) "
                        + "VALUES (?, ?, ?, ?, now(), ?, ?) RETURNING id",
                Long.class,
                parent, child, CrossTenantGrantType.SPAWN_SUB_AGENT, testUser.getId(),
                expiresAt == null ? null : Timestamp.from(expiresAt),
                revokedAt == null ? null : Timestamp.from(revokedAt));
        aclService.invalidate(parent, child, CrossTenantGrantType.SPAWN_SUB_AGENT);
        return id;
    }

    private long auditCount(Long parent, Long child, String decision) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_cross_tenant_spawn_audit "
                        + "WHERE parent_tenant_id = ? AND child_tenant_id = ? AND decision = ?",
                Long.class, parent, child, decision);
        return n == null ? 0L : n;
    }

    // =========================================================================

    @Test
    @DisplayName("A: same-tenant spawn unaffected by ACL gate")
    void caseA_same_tenant_unaffected() {
        // Caller tenant == parent tenant — ACL skipped entirely. No grant
        // rows or audit rows are touched.
        String parentRun = seedParentRun(childTenant); // same as caller
        SubAgentRunner.SpawnResult r = subAgentRunner.spawn(
                childTenant, parentRun, "sess", "msg", "interrupt_subtask");

        assertThat(r.getChildRunPid()).isNotNull();
        // No audit rows for same-tenant.
        assertThat(auditCount(childTenant, childTenant, CrossTenantDecision.ALLOWED)).isZero();
    }

    @Test
    @DisplayName("B: cross-tenant + active grant → spawn succeeds + audit decision=allowed")
    void caseB_cross_tenant_with_grant() {
        Long grantId = seedGrant(parentTenant, childTenant, null, null);
        String parentRun = seedParentRun(parentTenant);

        SubAgentRunner.SpawnResult r = subAgentRunner.spawn(
                childTenant, parentRun, "sess", "delegated work", "delegate_task");

        assertThat(r.getChildRunPid()).isNotNull();
        // Audit row written with grant_id, decision='allowed', child_run_pid populated.
        List<Map<String, Object>> audits = jdbc.queryForList(
                "SELECT grant_id, decision, child_run_pid, parent_run_pid "
                        + "FROM ab_cross_tenant_spawn_audit "
                        + "WHERE parent_tenant_id = ? AND child_tenant_id = ?",
                parentTenant, childTenant);
        assertThat(audits).hasSize(1);
        assertThat(((Number) audits.get(0).get("grant_id")).longValue()).isEqualTo(grantId);
        assertThat(audits.get(0).get("decision")).isEqualTo(CrossTenantDecision.ALLOWED);
        assertThat(audits.get(0).get("child_run_pid")).isEqualTo(r.getChildRunPid());
        assertThat(audits.get(0).get("parent_run_pid")).isEqualTo(parentRun);
    }

    @Test
    @DisplayName("C: cross-tenant + no grant → throws denied_no_grant + audit row + no child rows")
    void caseC_cross_tenant_no_grant() {
        String parentRun = seedParentRun(parentTenant);

        assertThatThrownBy(() -> subAgentRunner.spawn(
                childTenant, parentRun, "sess", "msg", "delegate_task"))
                .isInstanceOf(CrossTenantAclDeniedException.class)
                .hasMessageContaining("denied_no_grant");

        // No child run rows in either tenant.
        Integer childRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_run WHERE parent_run_id = ?",
                Integer.class, parentRun);
        assertThat(childRows).isZero();
        // Audit row with grant_id NULL and decision='denied_no_grant'.
        assertThat(auditCount(parentTenant, childTenant, CrossTenantDecision.DENIED_NO_GRANT))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("D: cross-tenant + expired grant → denied_expired")
    void caseD_cross_tenant_expired() {
        seedGrant(parentTenant, childTenant, Instant.now().minus(1, ChronoUnit.HOURS), null);
        String parentRun = seedParentRun(parentTenant);

        assertThatThrownBy(() -> subAgentRunner.spawn(
                childTenant, parentRun, "sess", "msg", "delegate_task"))
                .isInstanceOf(CrossTenantAclDeniedException.class)
                .hasMessageContaining("denied_expired");

        assertThat(auditCount(parentTenant, childTenant, CrossTenantDecision.DENIED_EXPIRED))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("E: cross-tenant + revoked grant → denied (no active row)")
    void caseE_cross_tenant_revoked() {
        seedGrant(parentTenant, childTenant, null, Instant.now());
        String parentRun = seedParentRun(parentTenant);

        assertThatThrownBy(() -> subAgentRunner.spawn(
                childTenant, parentRun, "sess", "msg", "delegate_task"))
                .isInstanceOf(CrossTenantAclDeniedException.class);
        // Revoked rows fall through to denied_no_grant (no active row).
        assertThat(auditCount(parentTenant, childTenant, CrossTenantDecision.DENIED_NO_GRANT))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("F (Q9): SYSTEM_TENANT crossing to business tenant follows the same rules")
    void caseF_system_tenant_no_implicit_bypass() {
        // Caller tenant = childTenant (business), parent in SYSTEM_TENANT.
        String parentRun = seedParentRun(SYSTEM_TENANT_ID);

        // Without a grant, SYSTEM_TENANT → business is denied.
        assertThatThrownBy(() -> subAgentRunner.spawn(
                childTenant, parentRun, "sess", "msg", "delegate_task"))
                .isInstanceOf(CrossTenantAclDeniedException.class)
                .hasMessageContaining("denied_no_grant");

        // After granting (SYSTEM_TENANT → childTenant), spawn succeeds.
        seedGrant(SYSTEM_TENANT_ID, childTenant, null, null);
        SubAgentRunner.SpawnResult r = subAgentRunner.spawn(
                childTenant, parentRun, "sess", "msg", "delegate_task");
        assertThat(r.getChildRunPid()).isNotNull();
    }
}
