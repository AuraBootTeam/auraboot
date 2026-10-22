package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.crosstenant.CrossTenantAclDeniedException;
import com.auraboot.framework.agent.crosstenant.CrossTenantAclService;
import com.auraboot.framework.agent.crosstenant.CrossTenantGrantType;
import com.auraboot.framework.agent.service.ChildRunOutcome;
import com.auraboot.framework.agent.service.ParentJoinService;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C.2 / Q12 — verifies {@link ParentJoinService#joinChildRun} consults the
 * cross-tenant ACL after C.2 wires it up. Companion to
 * {@link ParentJoinServiceJoinIntegrationTest#caseD_cross_tenant_rejected_immediately}
 * which already covers the no-grant deny path; here we add the grant-allows
 * positive path.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("ParentJoinService.joinChildRun cross-tenant ACL (C.2 / Q12)")
class JoinChildRunCrossTenantAclIntegrationTest extends BaseIntegrationTest {

    @Autowired private ParentJoinService parentJoinService;
    @Autowired private CrossTenantAclService aclService;
    @Autowired private JdbcTemplate jdbc;

    private Long parentTenant;
    private Long childTenant;

    @BeforeEach
    void setup() {
        long base = 9_800_000L + System.nanoTime() % 100_000;
        parentTenant = base;
        childTenant = base + 1L;
        MetaContext.setContext(parentTenant, testUser.getId(),
                testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void cleanup() {
        for (Long t : List.of(parentTenant, childTenant)) {
            jdbc.update("DELETE FROM ab_agent_run WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM ab_agent_task WHERE tenant_id = ?", t);
        }
        jdbc.update("DELETE FROM ab_cross_tenant_grant WHERE parent_tenant_id = ?", parentTenant);
        aclService.invalidate(parentTenant, childTenant, CrossTenantGrantType.SPAWN_SUB_AGENT);
        MetaContext.clear();
    }

    /** Seed parent (in parentTenant) + child (in childTenant), child already terminal. */
    private String[] seedTerminalCrossTenant() {
        String parentTaskPid = UniqueIdGenerator.generate();
        String parentRunPid = UniqueIdGenerator.generate();
        String childTaskPid = UniqueIdGenerator.generate();
        String childRunPid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_task (pid, tenant_id, title, task_status, "
                        + " assignee_type, assignee_id, created_at, updated_at, created_by) "
                        + "VALUES (?, ?, 'parent', 'in_progress', 'agent', 'aurabot', NOW(), NOW(), ?)",
                parentTaskPid, parentTenant, testUser.getId());
        jdbc.update("INSERT INTO ab_agent_run (pid, tenant_id, task_id, agent_id, run_status, "
                        + " started_at, created_at, updated_at, created_by) "
                        + "VALUES (?, ?, ?, 'aurabot', 'running', NOW(), NOW(), NOW(), ?)",
                parentRunPid, parentTenant, parentTaskPid, testUser.getId());
        jdbc.update("INSERT INTO ab_agent_task (pid, tenant_id, parent_id, title, task_status, "
                        + " assignee_type, assignee_id, created_at, updated_at, created_by) "
                        + "VALUES (?, ?, ?, 'child', 'in_progress', 'agent', 'aurabot', NOW(), NOW(), ?)",
                childTaskPid, childTenant, parentTaskPid, testUser.getId());
        jdbc.update("INSERT INTO ab_agent_run (pid, tenant_id, task_id, agent_id, run_status, "
                        + " parent_run_id, subtask_origin, "
                        + " input_tokens, output_tokens, total_cost, "
                        + " completed_at, started_at, created_at, updated_at, created_by) "
                        + "VALUES (?, ?, ?, 'aurabot', 'success', ?, 'delegate_task', "
                        + "        100, 50, 0.001, NOW(), NOW(), NOW(), NOW(), ?)",
                childRunPid, childTenant, childTaskPid, parentRunPid, testUser.getId());
        return new String[] {parentRunPid, childRunPid};
    }

    @Test
    @DisplayName("A: cross-tenant + grant → joinChildRun returns terminal outcome")
    void caseA_grant_allows_join() {
        jdbc.update("INSERT INTO ab_cross_tenant_grant "
                        + "(parent_tenant_id, child_tenant_id, grant_type, granted_by, granted_at) "
                        + "VALUES (?, ?, ?, ?, now())",
                parentTenant, childTenant, CrossTenantGrantType.SPAWN_SUB_AGENT, testUser.getId());
        aclService.invalidate(parentTenant, childTenant, CrossTenantGrantType.SPAWN_SUB_AGENT);

        String[] pair = seedTerminalCrossTenant();
        ChildRunOutcome out = parentJoinService.joinChildRun(pair[0], pair[1], 1000L);

        assertThat(out.terminalStatus()).isEqualTo("succeeded");
        assertThat(out.inputTokens()).isEqualTo(100L);
        assertThat(out.outputTokens()).isEqualTo(50L);
    }

    @Test
    @DisplayName("B: cross-tenant + no grant → CrossTenantAclDeniedException")
    void caseB_no_grant_denies_join() {
        aclService.invalidate(parentTenant, childTenant, CrossTenantGrantType.SPAWN_SUB_AGENT);

        String[] pair = seedTerminalCrossTenant();
        assertThatThrownBy(() -> parentJoinService.joinChildRun(pair[0], pair[1], 1000L))
                .isInstanceOf(CrossTenantAclDeniedException.class)
                .hasMessageContaining("denied_no_grant");
    }
}
