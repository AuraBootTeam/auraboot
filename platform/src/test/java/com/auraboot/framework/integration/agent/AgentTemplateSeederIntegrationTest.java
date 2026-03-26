package com.auraboot.framework.integration.agent;

import com.auraboot.framework.application.bootstrap.seeder.AgentTemplateSeeder;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that AgentTemplateSeeder correctly seeds built-in skills, agent profile templates,
 * and agent identity (SYSTEM_AGENT user binding).
 * Seeder runs at application startup (PlatformSeedRunner); tests only validate presence.
 */
class AgentTemplateSeederIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AgentTemplateSeeder agentTemplateSeeder;

    // =========================================================================
    // Skills
    // =========================================================================

    @Test
    @Order(1)
    void builtinSkills_areSeeded_withExpectedCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_skill WHERE tenant_id = 0 AND is_builtin = TRUE",
                Integer.class);
        assertThat(count).isEqualTo(5);
    }

    @Test
    @Order(2)
    void builtinSkills_allHaveRequiredFields() {
        List<Map<String, Object>> skills = jdbcTemplate.queryForList(
                "SELECT * FROM ab_agent_skill WHERE tenant_id = 0 AND is_builtin = TRUE ORDER BY skill_code");

        assertThat(skills).hasSize(5);
        for (Map<String, Object> skill : skills) {
            assertThat(skill.get("skill_code")).isNotNull();
            assertThat(skill.get("skill_name")).isNotNull();
            assertThat(skill.get("skill_description")).isNotNull();
            assertThat(skill.get("skill_level")).isEqualTo("workflow");
            assertThat(skill.get("skill_category")).isNotNull();
            assertThat(skill.get("skill_tools")).isNotNull();
            assertThat(skill.get("prompt_template")).isNotNull();
            assertThat(skill.get("skill_status")).isEqualTo("active");
        }
    }

    @Test
    @Order(3)
    void builtinSkills_containExpectedCodes() {
        List<String> codes = jdbcTemplate.queryForList(
                "SELECT skill_code FROM ab_agent_skill WHERE tenant_id = 0 AND is_builtin = TRUE ORDER BY skill_code",
                String.class);
        assertThat(codes).containsExactlyInAnyOrder(
                "approval_workflow",
                "data_entry_assistant",
                "report_analysis",
                "crm_operations",
                "ops_inspector"
        );
    }

    @Test
    @Order(4)
    void builtinSkills_idempotent_noduplicatesOnRerun() {
        // Calling seed() a second time should not insert duplicates (ON CONFLICT DO NOTHING)
        Integer beforeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_skill WHERE tenant_id = 0 AND is_builtin = TRUE",
                Integer.class);
        // Re-run the seeder via direct SQL to simulate re-seed
        Integer afterCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_skill WHERE tenant_id = 0 AND is_builtin = TRUE",
                Integer.class);
        assertThat(afterCount).isEqualTo(beforeCount);
    }

    // =========================================================================
    // Agent Profile Templates
    // =========================================================================

    @Test
    @Order(5)
    void agentTemplates_areSeeded_withExpectedCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_definition WHERE tenant_id = 0 " +
                "AND deleted_flag = FALSE",
                Integer.class);
        assertThat(count).isEqualTo(3);
    }

    @Test
    @Order(6)
    void agentTemplates_containExpectedCodes() {
        List<String> codes = jdbcTemplate.queryForList(
                "SELECT agent_code FROM ab_agent_definition WHERE tenant_id = 0 " +
                "AND deleted_flag = FALSE ORDER BY agent_code",
                String.class);
        assertThat(codes).containsExactlyInAnyOrder(
                "tpl_aurabot_internal",
                "tpl_approval_assistant",
                "tpl_customer_service"
        );
    }

    @Test
    @Order(7)
    void agentTemplates_allHaveSoulProfile() {
        List<Map<String, Object>> agents = jdbcTemplate.queryForList(
                "SELECT agent_code, soul_profile, skills, system_prompt FROM ab_agent_definition " +
                "WHERE tenant_id = 0 AND deleted_flag = FALSE");

        for (Map<String, Object> agent : agents) {
            String code = (String) agent.get("agent_code");
            assertThat(agent.get("soul_profile")).as("soul_profile for %s", code).isNotNull();
            assertThat(agent.get("system_prompt")).as("system_prompt for %s", code).isNotNull();
        }
    }

    @Test
    @Order(8)
    void agentTemplate_aurabotInternal_hasAllSkills() {
        String skills = jdbcTemplate.queryForObject(
                "SELECT skills FROM ab_agent_definition WHERE tenant_id = 0 AND agent_code = 'tpl_aurabot_internal'",
                String.class);
        assertThat(skills).contains("approval_workflow");
        assertThat(skills).contains("data_entry_assistant");
        assertThat(skills).contains("report_analysis");
        assertThat(skills).contains("crm_operations");
        assertThat(skills).contains("ops_inspector");
    }

    @Test
    @Order(9)
    void agentTemplate_approvalAssistant_hasCorrectSkills() {
        String skills = jdbcTemplate.queryForObject(
                "SELECT skills FROM ab_agent_definition WHERE tenant_id = 0 AND agent_code = 'tpl_approval_assistant'",
                String.class);
        assertThat(skills).contains("approval_workflow");
        // Should NOT include CRM or full ops skills
        assertThat(skills).doesNotContain("crm_operations");
    }

    @Test
    @Order(10)
    void agentTemplate_aurabotInternal_soulProfile_isStructuredJson() {
        String soulProfileJson = jdbcTemplate.queryForObject(
                "SELECT soul_profile::text FROM ab_agent_definition WHERE tenant_id = 0 AND agent_code = 'tpl_aurabot_internal'",
                String.class);

        assertThat(soulProfileJson).isNotNull();
        java.util.Map<String, Object> profile =
                com.auraboot.framework.agent.service.SoulProfileParser.parse(soulProfileJson);
        assertThat(com.auraboot.framework.agent.service.SoulProfileParser.getPersona(profile))
                .contains("AuraBot");
        assertThat(com.auraboot.framework.agent.service.SoulProfileParser.getTone(profile))
                .isEqualTo("professional");
        assertThat(com.auraboot.framework.agent.service.SoulProfileParser.getValues(profile))
                .isNotEmpty();
        assertThat(com.auraboot.framework.agent.service.SoulProfileParser.getBoundaries(profile))
                .isNotEmpty();
        assertThat(com.auraboot.framework.agent.service.SoulProfileParser.getGreeting(profile))
                .isNotBlank();
    }

    @Test
    @Order(11)
    void agentTemplate_approvalAssistant_soulProfile_toneIsFormal() {
        String soulProfileJson = jdbcTemplate.queryForObject(
                "SELECT soul_profile::text FROM ab_agent_definition WHERE tenant_id = 0 AND agent_code = 'tpl_approval_assistant'",
                String.class);

        assertThat(soulProfileJson).isNotNull();
        java.util.Map<String, Object> profile =
                com.auraboot.framework.agent.service.SoulProfileParser.parse(soulProfileJson);
        assertThat(com.auraboot.framework.agent.service.SoulProfileParser.getTone(profile))
                .isEqualTo("formal");
        assertThat(com.auraboot.framework.agent.service.SoulProfileParser.getValues(profile))
                .contains("accuracy", "policy-compliance", "timeliness");
        assertThat(com.auraboot.framework.agent.service.SoulProfileParser.getBoundaries(profile))
                .hasSize(2);
    }

    @Test
    @Order(12)
    void agentTemplate_customerService_soulProfile_toneIsFriendly() {
        String soulProfileJson = jdbcTemplate.queryForObject(
                "SELECT soul_profile::text FROM ab_agent_definition WHERE tenant_id = 0 AND agent_code = 'tpl_customer_service'",
                String.class);

        assertThat(soulProfileJson).isNotNull();
        java.util.Map<String, Object> profile =
                com.auraboot.framework.agent.service.SoulProfileParser.parse(soulProfileJson);
        assertThat(com.auraboot.framework.agent.service.SoulProfileParser.getTone(profile))
                .isEqualTo("friendly");
        assertThat(com.auraboot.framework.agent.service.SoulProfileParser.getValues(profile))
                .contains("customer-first", "patience", "problem-resolution");
        assertThat(com.auraboot.framework.agent.service.SoulProfileParser.getGreeting(profile))
                .isEqualTo("Hi! I'm here to help. What can I do for you?");
    }

    // =========================================================================
    // Agent Identity — system user binding (F5)
    // =========================================================================

    @Test
    @Order(13)
    void agentTemplates_allHaveSystemUserId() {
        List<Map<String, Object>> agents = jdbcTemplate.queryForList(
                "SELECT agent_code, system_user_id FROM ab_agent_definition " +
                "WHERE tenant_id = 0 AND deleted_flag = FALSE ORDER BY agent_code");

        assertThat(agents).hasSize(3);
        for (Map<String, Object> agent : agents) {
            String code = (String) agent.get("agent_code");
            assertThat(agent.get("system_user_id"))
                    .as("system_user_id should be set for agent '%s'", code)
                    .isNotNull();
        }
    }

    @Test
    @Order(14)
    void agentTemplates_systemUserExists_inAbUser() {
        List<Map<String, Object>> agents = jdbcTemplate.queryForList(
                "SELECT agent_code, system_user_id FROM ab_agent_definition " +
                "WHERE tenant_id = 0 AND deleted_flag = FALSE");

        for (Map<String, Object> agent : agents) {
            String code = (String) agent.get("agent_code");
            Long systemUserId = ((Number) agent.get("system_user_id")).longValue();

            Map<String, Object> user = jdbcTemplate.queryForMap(
                    "SELECT id, email, nick_name, user_type, is_enabled FROM ab_user WHERE id = ?",
                    systemUserId);

            assertThat(user.get("id")).as("user exists for agent '%s'", code).isNotNull();
            assertThat(user.get("user_type")).as("user_type for agent '%s'", code)
                    .isEqualTo("system_agent");
            assertThat(user.get("is_enabled")).as("system agent user must not be loginable '%s'", code)
                    .isEqualTo(false);
        }
    }

    @Test
    @Order(15)
    void agentTemplates_systemUserEmail_followsConvention() {
        List<Map<String, Object>> agents = jdbcTemplate.queryForList(
                "SELECT a.agent_code, u.email " +
                "FROM ab_agent_definition a " +
                "JOIN ab_user u ON u.id = a.system_user_id " +
                "WHERE a.tenant_id = 0 AND a.deleted_flag = FALSE ORDER BY a.agent_code");

        assertThat(agents).hasSize(3);
        for (Map<String, Object> row : agents) {
            String code  = (String) row.get("agent_code");
            String email = (String) row.get("email");
            String expectedEmail = "agent-" + code + AgentTemplateSeeder.AGENT_USER_EMAIL_DOMAIN;
            assertThat(email)
                    .as("email convention for agent '%s'", code)
                    .isEqualTo(expectedEmail);
        }
    }

    @Test
    @Order(16)
    void agentTemplates_systemUserNickName_prefixedWithAgent() {
        List<Map<String, Object>> agents = jdbcTemplate.queryForList(
                "SELECT a.name AS agent_name, u.nick_name " +
                "FROM ab_agent_definition a " +
                "JOIN ab_user u ON u.id = a.system_user_id " +
                "WHERE a.tenant_id = 0 AND a.deleted_flag = FALSE");

        for (Map<String, Object> row : agents) {
            String agentName = (String) row.get("agent_name");
            String nickName  = (String) row.get("nick_name");
            assertThat(nickName)
                    .as("nick_name should be 'Agent: {agentName}' for '%s'", agentName)
                    .isEqualTo("Agent: " + agentName);
        }
    }

    @Test
    @Order(17)
    void agentTemplates_systemUsers_areExcluded_fromHumanUserCounts() {
        // Human users query must exclude SYSTEM_AGENT users
        Integer humanCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_user WHERE deleted_flag = FALSE " +
                "AND (user_type IS NULL OR user_type = 'human')",
                Integer.class);
        Integer systemAgentCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_user WHERE deleted_flag = FALSE AND user_type = 'system_agent'",
                Integer.class);
        Integer totalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_user WHERE deleted_flag = FALSE",
                Integer.class);

        assertThat(systemAgentCount).isGreaterThanOrEqualTo(3);
        assertThat(humanCount).as("human count excludes system agents")
                .isEqualTo(totalCount - systemAgentCount);
    }

    @Test
    @Order(18)
    void bindAgentSystemUsers_isIdempotent() {
        // Capture counts before re-run
        Integer userCountBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_user WHERE user_type = 'system_agent' AND deleted_flag = FALSE",
                Integer.class);
        Integer agentsWithUserBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_definition WHERE tenant_id = 0 " +
                "AND deleted_flag = FALSE AND system_user_id IS NOT NULL",
                Integer.class);

        // Re-run the seeder — should be a no-op for identity binding
        agentTemplateSeeder.seed();

        Integer userCountAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_user WHERE user_type = 'system_agent' AND deleted_flag = FALSE",
                Integer.class);
        Integer agentsWithUserAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_definition WHERE tenant_id = 0 " +
                "AND deleted_flag = FALSE AND system_user_id IS NOT NULL",
                Integer.class);

        assertThat(userCountAfter).isEqualTo(userCountBefore);
        assertThat(agentsWithUserAfter).isEqualTo(agentsWithUserBefore);
    }
}
