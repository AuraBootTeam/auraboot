package com.auraboot.framework.agent;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.agent.service.AgentSkillService;
import com.auraboot.framework.agent.service.SkillAutoGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for SkillAutoGenerator — verifies atomic skill generation
 * from published DSL model definitions.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Transactional
@Rollback(true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SkillAutoGeneratorIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private SkillAutoGenerator skillAutoGenerator;

    @Autowired
    private AgentSkillService skillService;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    private Long tenantId;

    @BeforeEach
    void seedTestData() {
        tenantId = getTestTenant().getId();
    }

    @Test
    @Order(1)
    void testSyncSkills_generatesAtomicSkills() {
        SkillAutoGenerator.SyncResult result = skillAutoGenerator.syncSkills(tenantId);

        assertThat(result.created() + result.updated()).isEqualTo(2);

        String sql = "SELECT COUNT(*) AS cnt FROM ab_agent_skill " +
                "WHERE tenant_id = #{params.tenantId} AND skill_code LIKE '%.%'";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId));
        long count = ((Number) rows.get(0).get("cnt")).longValue();
        assertThat(count).isGreaterThan(0);
    }

    @Test
    @Order(2)
    void testSyncSkills_idempotent() {
        // First sync — creates skills
        SkillAutoGenerator.SyncResult first = skillAutoGenerator.syncSkills(tenantId);
        assertThat(first.created() + first.updated()).isEqualTo(2);

        // Second sync — should only update, not create
        SkillAutoGenerator.SyncResult second = skillAutoGenerator.syncSkills(tenantId);
        assertThat(second.created()).isEqualTo(0);
        assertThat(second.updated()).isEqualTo(2);
    }

    @Test
    @Order(3)
    void testSyncSkills_twoBuiltinSkillsGenerated() {
        skillAutoGenerator.syncSkills(tenantId);

        Map<String, Object> cmdSkill = skillService.loadSkill(tenantId, "dsl.command");
        assertThat(cmdSkill).isNotNull();
        assertThat(cmdSkill.get("skill_code")).isEqualTo("dsl.command");

        Map<String, Object> qrySkill = skillService.loadSkill(tenantId, "dsl.query");
        assertThat(qrySkill).isNotNull();
        assertThat(qrySkill.get("skill_code")).isEqualTo("dsl.query");
    }

    @Test
    @Order(4)
    void testDslQuerySkill_hasCorrectContract() {
        skillAutoGenerator.syncSkills(tenantId);

        Map<String, Object> contract = skillService.loadSkillContract(tenantId, "dsl.query");
        assertThat(contract).isNotNull();
        assertThat(contract.get("execution_mode")).isEqualTo("dsl_dispatch");
        assertThat(contract.get("actionability")).isEqualTo("read_only");
        assertThat(contract.get("output_type")).isEqualTo("structured_result");
        assertThat(contract.get("idempotency_mode")).isEqualTo("safe");
    }

    @Test
    @Order(5)
    void testDslCommandSkill_hasExecuteActionability() {
        skillAutoGenerator.syncSkills(tenantId);

        Map<String, Object> contract = skillService.loadSkillContract(tenantId, "dsl.command");
        assertThat(contract).isNotNull();
        assertThat(contract.get("actionability")).isEqualTo("execute");
        assertThat(contract.get("execution_mode")).isEqualTo("dsl_dispatch");
        assertThat(contract.get("idempotency_mode")).isEqualTo("not_idempotent");
    }

}
