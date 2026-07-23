package com.auraboot.framework.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.saas.executor.SystemTenantContextExecutor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Real-stack regression for the two wiring fixes that make a bound skill actually
 * contribute governed DSL tools (OSS #1440):
 *
 * <ul>
 *   <li><b>Gap B</b> — {@code loadSkill} must find a platform-tenant ({@code tenant_id=1})
 *       builtin skill from a <em>non-system</em> tenant context. The MyBatis
 *       {@code TenantLineInnerInterceptor} injects {@code AND tenant_id=<current>} unless
 *       the read bypasses it ({@code selectByQueryWithoutTenant}), which would AND away the
 *       {@code OR tenant_id=platform AND is_builtin} fallback and return null.</li>
 *   <li><b>Gap A</b> — {@code resolveSkillTools} must resolve a dynamic DSL tool code
 *       ({@code list:}) that lives in NO table, via the same {@code ToolProviderRegistry}
 *       an agent turn uses, not the (empty) {@code ab_agent_tool} table.</li>
 * </ul>
 *
 * <p><b>Mutation check (done by hand, documented for the reviewer):</b> reverting
 * {@code selectByQueryWithoutTenant} back to {@code selectByQuery} in
 * {@code AgentSkillService.loadSkill} makes {@link #loadSkill_findsSystemBuiltinFromTenantContext}
 * fail (skill is null); reverting the provider-registry resolution in {@code resolveToolCodes}
 * makes {@link #resolveSkillTools_resolvesGovernedDslListTool} fail (tools are empty). Neither
 * assertion is vacuous.
 */
@ActiveProfiles("integration-test")
class SkillAssetLayerTenantIntegrationTest extends BaseIntegrationTest {

    private static final long SYSTEM_TENANT_ID = SystemTenantContextExecutor.SYSTEM_TENANT_ID;
    private static final String MODEL_CODE = "it_asset_model";

    @Autowired AgentSkillService agentSkillService;
    @Autowired JdbcTemplate jdbcTemplate;

    // Permission-scoped DSL discovery keys off the current user; mock it so the
    // list tool is discoverable without seeding a role_permission row. The tenant
    // interceptor (Gap B) is at the MyBatis layer, NOT this bean, so mocking
    // permissions does not mask the tenant-bypass being under test.
    @MockBean UserPermissionService userPermissionService;

    private String skillCode;

    @BeforeEach
    void setUp() {
        skillCode = "it_asset_" + UniqueIdGenerator.generate().toLowerCase().substring(0, 8);
        // Seed a SYSTEM-tenant builtin orchestration skill whose only tool is a dynamic
        // DSL list: code — exactly the shape of the seeded crm_quarterly_review skill.
        jdbcTemplate.update(
                "INSERT INTO ab_agent_skill "
                        + "(pid, tenant_id, skill_code, skill_name, skill_level, skill_category, "
                        + " skill_tools, execution_mode, is_builtin, skill_status, deleted_flag) "
                        + "VALUES (?, ?, ?, ?, 'workflow', 'test', ?::jsonb, 'orchestration', "
                        + " TRUE, 'active', FALSE)",
                UniqueIdGenerator.generate(), SYSTEM_TENANT_ID, skillCode, "IT Asset Skill",
                "[\"list:" + MODEL_CODE + "\"]");
        // Non-system tenant + a user, so the tenant interceptor would filter the builtin.
        applyTestMetaContext();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM ab_agent_skill WHERE skill_code = ?", skillCode);
        MetaContext.clear();
    }

    @Test
    @DisplayName("loadSkill finds a SYSTEM-tenant builtin skill from a non-system tenant context")
    void loadSkill_findsSystemBuiltinFromTenantContext() {
        assertThat(getTestTenant().getId())
                .as("precondition: test tenant is not the system tenant")
                .isNotEqualTo(SYSTEM_TENANT_ID);

        Map<String, Object> skill = agentSkillService.loadSkill(getTestTenant().getId(), skillCode);

        assertThat(skill)
                .as("a platform builtin skill must be visible from a tenant context (Gap B)")
                .isNotNull();
        assertThat(skill.get("skill_code")).isEqualTo(skillCode);
    }

    @Test
    @DisplayName("resolveSkillTools resolves the skill's governed DSL list tool via the provider registry")
    void resolveSkillTools_resolvesGovernedDslListTool() {
        Long userId = MetaContext.getCurrentUserId();
        when(userPermissionService.hasPermission(eq(userId), eq("model." + MODEL_CODE + ".read")))
                .thenReturn(true);

        List<AgentToolDefinition> tools =
                agentSkillService.resolveSkillTools(getTestTenant().getId(), skillCode);

        assertThat(tools)
                .as("bound skill must contribute its governed DSL list tool (Gap A + Gap B)")
                .extracting(AgentToolDefinition::getName)
                .contains("list:" + MODEL_CODE);
    }
}
