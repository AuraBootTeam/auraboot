package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.crosstenant.CrossTenantAclService;
import com.auraboot.framework.agent.crosstenant.CrossTenantGrantType;
import com.auraboot.framework.agent.provider.PlatformToolProvider;
import com.auraboot.framework.agent.provider.ProviderExecutionResult;
import com.auraboot.framework.agent.service.StepContext;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C.2 / Q11 — verifies the {@code platform.delegate_task} tool surfaces a
 * cross-tenant ACL denial as a structured tool error (not an exception)
 * so the LLM can reason about the failure mode.
 *
 * <p>Cases:
 * <ul>
 *   <li>A — cross-tenant + grant → tool returns {@code success=true} with the
 *           child run pid populated.</li>
 *   <li>B — cross-tenant + no grant → tool returns
 *           {@code error: cross_tenant_not_granted} with parent_tenant /
 *           child_tenant / reason fields. NO exception escapes the tool.</li>
 * </ul>
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("platform.delegate_task cross-tenant ACL (C.2 / Q11)")
class DelegateTaskCrossTenantAclIntegrationTest extends BaseIntegrationTest {

    @Autowired private PlatformToolProvider provider;
    @Autowired private CrossTenantAclService aclService;
    @Autowired private JdbcTemplate jdbc;

    private Long parentTenant;
    private Long childTenant;
    private String parentRunPid;

    @BeforeEach
    void setup() {
        long base = 9_810_000L + System.nanoTime() % 100_000;
        parentTenant = base;
        childTenant = base + 1L;
        // Caller MetaContext = childTenant — that's what delegate_task reads.
        MetaContext.setContext(childTenant, testUser.getId(),
                testUser.getPid(), testUser.getUserName());

        // Seed parent in parentTenant.
        String parentTaskPid = UniqueIdGenerator.generate();
        parentRunPid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_task (pid, tenant_id, title, task_status, "
                        + " assignee_type, assignee_id, created_at, updated_at, created_by) "
                        + "VALUES (?, ?, 'parent', 'in_progress', 'agent', 'aurabot', NOW(), NOW(), ?)",
                parentTaskPid, parentTenant, testUser.getId());
        jdbc.update("INSERT INTO ab_agent_run (pid, tenant_id, task_id, agent_id, run_status, "
                        + " started_at, created_at, updated_at, created_by) "
                        + "VALUES (?, ?, ?, 'aurabot', 'running', NOW(), NOW(), NOW(), ?)",
                parentRunPid, parentTenant, parentTaskPid, testUser.getId());
        // Bind run pid for the tool.
        StepContext.setRunPid(parentRunPid);
    }

    @AfterEach
    void cleanup() {
        StepContext.clearRunPid();
        for (Long t : List.of(parentTenant, childTenant)) {
            jdbc.update("DELETE FROM ab_agent_run WHERE tenant_id = ?", t);
            jdbc.update("DELETE FROM ab_agent_task WHERE tenant_id = ?", t);
        }
        jdbc.update("DELETE FROM ab_cross_tenant_spawn_audit "
                        + "WHERE parent_tenant_id IN (?, ?) OR child_tenant_id IN (?, ?)",
                parentTenant, childTenant, parentTenant, childTenant);
        jdbc.update("DELETE FROM ab_cross_tenant_grant WHERE parent_tenant_id = ?", parentTenant);
        aclService.invalidate(parentTenant, childTenant, CrossTenantGrantType.SPAWN_SUB_AGENT);
        MetaContext.clear();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeDelegateTask() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("subtaskMessage", "do something");
        // Tool dispatch via the ToolProvider SPI — same path the runtime
        // ToolLoopService uses; verifies the structured-error contract end-
        // to-end through the SPI envelope.
        ProviderExecutionResult outcome = provider.execute(childTenant,
                "platform.delegate_task", params);
        return (Map<String, Object>) outcome.getData();
    }

    @Test
    @DisplayName("A: cross-tenant + grant → tool returns success with childRunPid")
    void caseA_grant_allows_delegate_task() {
        jdbc.update("INSERT INTO ab_cross_tenant_grant "
                        + "(parent_tenant_id, child_tenant_id, grant_type, granted_by, granted_at) "
                        + "VALUES (?, ?, ?, ?, now())",
                parentTenant, childTenant, CrossTenantGrantType.SPAWN_SUB_AGENT, testUser.getId());
        aclService.invalidate(parentTenant, childTenant, CrossTenantGrantType.SPAWN_SUB_AGENT);

        Map<String, Object> result = invokeDelegateTask();
        assertThat(result.get("success")).isEqualTo(true);
        assertThat(result.get("childRunPid")).isNotNull();
    }

    @Test
    @DisplayName("B: cross-tenant + no grant → structured error, no exception escapes")
    void caseB_no_grant_returns_structured_error() {
        aclService.invalidate(parentTenant, childTenant, CrossTenantGrantType.SPAWN_SUB_AGENT);

        Map<String, Object> result = invokeDelegateTask();

        // Q11: structured error contract — error code + tenant ids + reason.
        assertThat(result.get("error")).isEqualTo("cross_tenant_not_granted");
        assertThat(result.get("parent_tenant")).isEqualTo(parentTenant);
        assertThat(result.get("child_tenant")).isEqualTo(childTenant);
        assertThat(result.get("reason")).isEqualTo("no_grant");
        assertThat(result.get("decision")).isEqualTo("denied_no_grant");
        // Critical: no `success=true` and no exception leaked.
        assertThat(result.get("success")).isNotEqualTo(true);
    }
}
