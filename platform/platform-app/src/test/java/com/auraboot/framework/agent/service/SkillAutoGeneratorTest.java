package com.auraboot.framework.agent.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for SkillAutoGenerator.
 * Verifies that syncSkills() creates exactly 2 built-in skills (dsl.command + dsl.query)
 * and that repeated calls are idempotent (update, not duplicate).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SkillAutoGeneratorTest extends BaseIntegrationTest {

    @Autowired
    private SkillAutoGenerator skillAutoGenerator;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    @Test
    @Order(1)
    void syncSkills_createsExactlyTwoBuiltinSkills() {
        Long tenantId = getTestTenant().getId();

        var result = skillAutoGenerator.syncSkills(tenantId);
        assertThat(result.created() + result.updated()).isEqualTo(2);

        // Verify dsl.command
        var cmdSkill = loadSkill(tenantId, "dsl.command");
        assertThat(cmdSkill).isNotNull();
        assertThat(cmdSkill.get("skill_name")).isEqualTo("Execute DSL Command");
        assertThat(cmdSkill.get("execution_mode")).isEqualTo("dsl_dispatch");
        assertThat(cmdSkill.get("is_builtin")).isEqualTo(true);
        assertThat(cmdSkill.get("actionability")).isEqualTo("execute");
        assertThat(cmdSkill.get("skill_category")).isEqualTo("crud");
        assertThat(cmdSkill.get("idempotency_mode")).isEqualTo("not_idempotent");
        assertThat(cmdSkill.get("output_type")).isEqualTo("text");
        assertThat(cmdSkill.get("skill_level")).isEqualTo("atomic");
        assertThat(cmdSkill.get("skill_status")).isEqualTo("active");

        // Verify dsl.query
        var qrySkill = loadSkill(tenantId, "dsl.query");
        assertThat(qrySkill).isNotNull();
        assertThat(qrySkill.get("skill_name")).isEqualTo("Query DSL Data");
        assertThat(qrySkill.get("execution_mode")).isEqualTo("dsl_dispatch");
        assertThat(qrySkill.get("is_builtin")).isEqualTo(true);
        assertThat(qrySkill.get("actionability")).isEqualTo("read_only");
        assertThat(qrySkill.get("skill_category")).isEqualTo("analysis");
        assertThat(qrySkill.get("output_type")).isEqualTo("structured_result");
        assertThat(qrySkill.get("render_hint")).isEqualTo("table");
        assertThat(qrySkill.get("idempotency_mode")).isEqualTo("safe");
    }

    @Test
    @Order(2)
    void syncSkills_idempotent_secondCallUpdates() {
        Long tenantId = getTestTenant().getId();

        // First call — creates
        skillAutoGenerator.syncSkills(tenantId);

        // Second call — should update, not create duplicates
        var result2 = skillAutoGenerator.syncSkills(tenantId);
        assertThat(result2.updated()).isEqualTo(2);
        assertThat(result2.created()).isEqualTo(0);

        // Verify no duplicates
        String countSql = "SELECT COUNT(*) AS cnt FROM ab_agent_skill " +
                "WHERE tenant_id = #{params.tenantId} AND is_builtin = TRUE " +
                "AND (deleted_flag = FALSE OR deleted_flag IS NULL)";
        var countRows = dynamicDataMapper.selectByQuery(countSql, Map.of("tenantId", tenantId));
        long count = ((Number) countRows.get(0).get("cnt")).longValue();
        assertThat(count).isEqualTo(2);
    }

    @Test
    @Order(3)
    void syncSkills_resultHasZeroSkipped() {
        Long tenantId = getTestTenant().getId();

        var result = skillAutoGenerator.syncSkills(tenantId);
        assertThat(result.skipped()).isEqualTo(0);
    }

    private Map<String, Object> loadSkill(Long tenantId, String skillCode) {
        String sql = "SELECT * FROM ab_agent_skill " +
                "WHERE tenant_id = #{params.tenantId} AND skill_code = #{params.skillCode} " +
                "AND (deleted_flag = FALSE OR deleted_flag IS NULL)";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(
                sql, Map.of("tenantId", tenantId, "skillCode", skillCode));
        return rows.isEmpty() ? null : rows.get(0);
    }
}
