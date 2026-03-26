package com.auraboot.framework.agent;

import com.auraboot.framework.agent.service.SkillAutoGenerator;
import com.auraboot.framework.agent.service.SkillBootstrapRunner;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Integration tests for SkillBootstrapRunner and SkillAutoGenerator.
 * Verifies that built-in skills (dsl.command, dsl.query) are created
 * for active tenants and that syncSkills is idempotent.
 */
class SkillBootstrapRunnerTest extends BaseIntegrationTest {

    @Autowired
    private SkillBootstrapRunner skillBootstrapRunner;

    @Autowired
    private SkillAutoGenerator skillAutoGenerator;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    @Test
    void run_completesWithoutException() {
        // SkillBootstrapRunner.run() should never throw — it catches all exceptions
        assertThatCode(() -> skillBootstrapRunner.run(null))
                .doesNotThrowAnyException();
    }

    @Test
    void syncSkills_createsBuiltinSkillsForTestTenant() {
        Long tenantId = getTestTenant().getId();

        // Sync skills for test tenant directly
        var result = skillAutoGenerator.syncSkills(tenantId);
        assertThat(result).isNotNull();
        assertThat(result.created() + result.updated()).isGreaterThanOrEqualTo(2);

        // Verify skills exist
        String sql = "SELECT skill_code, execution_mode FROM ab_agent_skill " +
                "WHERE tenant_id = #{params.tenantId} AND skill_code IN ('dsl.command', 'dsl.query') " +
                "AND (deleted_flag = FALSE OR deleted_flag IS NULL)";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(
                sql, Map.of("tenantId", tenantId));

        assertThat(rows).hasSizeGreaterThanOrEqualTo(2);
        assertThat(rows.stream().map(r -> r.get("skill_code")).toList())
                .contains("dsl.command", "dsl.query");
        assertThat(rows).allMatch(r -> "dsl_dispatch".equals(r.get("execution_mode")));
    }

    @Test
    void syncSkills_isIdempotent() {
        Long tenantId = getTestTenant().getId();

        // First sync: should create
        var result1 = skillAutoGenerator.syncSkills(tenantId);
        assertThat(result1).isNotNull();

        // Second sync: should update (not duplicate)
        var result2 = skillAutoGenerator.syncSkills(tenantId);
        assertThat(result2).isNotNull();
        // Second run should report updates (not creates) since skills already exist
        assertThat(result2.updated()).isGreaterThanOrEqualTo(2);
        assertThat(result2.created()).isEqualTo(0);

        // Verify exactly 1 copy of each skill
        String sql = "SELECT COUNT(*) AS cnt FROM ab_agent_skill " +
                "WHERE tenant_id = #{params.tenantId} AND skill_code = 'dsl.command' " +
                "AND (deleted_flag = FALSE OR deleted_flag IS NULL)";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(
                sql, Map.of("tenantId", tenantId));

        assertThat(((Number) rows.get(0).get("cnt")).intValue()).isEqualTo(1);
    }

    @Test
    void syncSkills_createsSkillsWithCorrectAttributes() {
        Long tenantId = getTestTenant().getId();
        skillAutoGenerator.syncSkills(tenantId);

        // Verify dsl.command attributes
        String sql = "SELECT skill_code, skill_name, skill_category, execution_mode, " +
                "actionability, is_builtin, skill_status " +
                "FROM ab_agent_skill " +
                "WHERE tenant_id = #{params.tenantId} AND skill_code = 'dsl.command' " +
                "AND (deleted_flag = FALSE OR deleted_flag IS NULL)";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(
                sql, Map.of("tenantId", tenantId));

        assertThat(rows).hasSize(1);
        Map<String, Object> cmd = rows.get(0);
        assertThat(cmd.get("skill_name")).isEqualTo("Execute DSL Command");
        assertThat(cmd.get("skill_category")).isEqualTo("crud");
        assertThat(cmd.get("execution_mode")).isEqualTo("dsl_dispatch");
        assertThat(cmd.get("actionability")).isEqualTo("execute");
        assertThat(cmd.get("is_builtin")).isEqualTo(true);
        assertThat(cmd.get("skill_status")).isEqualTo("active");
    }
}
