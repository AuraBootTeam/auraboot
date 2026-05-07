package com.auraboot.framework.integration.agent;

import com.auraboot.framework.application.bootstrap.seeder.AgentTemplateSeeder;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.saas.executor.SystemTenantContextExecutor;
import org.junit.jupiter.api.BeforeEach;
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

    private static final Long SYSTEM_TENANT_ID = SystemTenantContextExecutor.SYSTEM_TENANT_ID;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AgentTemplateSeeder agentTemplateSeeder;

    @BeforeEach
    void ensureSeeded() {
        agentTemplateSeeder.seed();
    }

    // =========================================================================
    // Skills
    // =========================================================================

    @Test
    @Order(1)
    void builtinSkills_areSeeded_withExpectedCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_skill WHERE tenant_id = ? AND is_builtin = TRUE",
                Integer.class,
                SYSTEM_TENANT_ID);
        assertThat(count).isEqualTo(7);
    }

    @Test
    @Order(2)
    void builtinSkills_allHaveRequiredFields() {
        List<Map<String, Object>> skills = jdbcTemplate.queryForList(
                "SELECT * FROM ab_agent_skill WHERE tenant_id = ? AND is_builtin = TRUE ORDER BY skill_code",
                SYSTEM_TENANT_ID);

        assertThat(skills).hasSize(7);
        for (Map<String, Object> skill : skills) {
            assertThat(skill.get("skill_code")).isNotNull();
            assertThat(skill.get("skill_name")).isNotNull();
            assertThat(skill.get("skill_description")).isNotNull();
            assertThat(skill.get("skill_category")).isNotNull();
            assertThat(skill.get("skill_status")).isEqualTo("active");
            String skillCode = (String) skill.get("skill_code");
            if ("dsl.command".equals(skillCode) || "dsl.query".equals(skillCode)) {
                assertThat(skill.get("skill_level")).isEqualTo("atomic");
            } else {
                assertThat(skill.get("skill_level")).isEqualTo("workflow");
                assertThat(skill.get("skill_tools")).isNotNull();
                assertThat(skill.get("prompt_template")).isNotNull();
            }
        }
    }

    @Test
    @Order(3)
    void builtinSkills_containExpectedCodes() {
        List<String> codes = jdbcTemplate.queryForList(
                "SELECT skill_code FROM ab_agent_skill WHERE tenant_id = ? AND is_builtin = TRUE ORDER BY skill_code",
                String.class,
                SYSTEM_TENANT_ID);
        assertThat(codes).containsExactlyInAnyOrder(
                "approval_workflow",
                "data_entry_assistant",
                "report_analysis",
                "crm_operations",
                "ops_inspector",
                "dsl.command",
                "dsl.query"
        );
    }

    @Test
    @Order(4)
    void builtinSkills_idempotent_noduplicatesOnRerun() {
        // Calling seed() a second time should not insert duplicates (ON CONFLICT DO UPDATE
        // only refreshes execution_config + updated_at, never inserts new rows).
        Integer beforeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_skill WHERE tenant_id = ? AND is_builtin = TRUE",
                Integer.class,
                SYSTEM_TENANT_ID);
        // Re-run the seeder explicitly to confirm upsert is idempotent on row count
        agentTemplateSeeder.seed();
        Integer afterCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_skill WHERE tenant_id = ? AND is_builtin = TRUE",
                Integer.class,
                SYSTEM_TENANT_ID);
        assertThat(afterCount).isEqualTo(beforeCount);
    }

    @Test
    @Order(4)
    void builtinSkills_promptTemplate_mentionsDelegateTask() {
        // C.4: each of the 5 workflow-level builtin skills must teach the LLM
        // about platform.delegate_task so it can spawn child runs.
        // Re-seed in this @Transactional method to pick up the latest
        // prompt_template constants from source (seeder is INSERT ... ON
        // CONFLICT DO NOTHING and is normally short-circuited when rows
        // already exist; the surrounding rollback isolates this delete).
        jdbcTemplate.update(
                "DELETE FROM ab_agent_skill WHERE tenant_id = ? AND is_builtin = TRUE",
                SYSTEM_TENANT_ID);
        agentTemplateSeeder.seed();

        List<String> workflowSkillCodes = List.of(
                "approval_workflow",
                "data_entry_assistant",
                "report_analysis",
                "crm_operations",
                "ops_inspector");

        for (String code : workflowSkillCodes) {
            String promptTemplate = jdbcTemplate.queryForObject(
                    "SELECT prompt_template FROM ab_agent_skill " +
                    "WHERE tenant_id = ? AND skill_code = ? AND is_builtin = TRUE",
                    String.class,
                    SYSTEM_TENANT_ID, code);

            assertThat(promptTemplate)
                    .as("prompt_template for skill '%s'", code)
                    .isNotNull()
                    .contains("platform.delegate_task")
                    .contains("subtaskMessage");
        }
    }

    @Test
    @Order(4)
    void builtinSkills_atomicSkills_doNotMentionDelegateTask() {
        // Atomic skills (dsl.command, dsl.query) are leaf primitives; they
        // are not allowed to delegate further. Their prompt_template (if any)
        // must NOT advertise platform.delegate_task.
        // dsl.command / dsl.query are seeded by a separate atomic-skill
        // seeder; we do not reset them here. We only assert that any
        // existing prompt_template for these atomic codes does NOT
        // advertise platform.delegate_task.
        for (String code : List.of("dsl.command", "dsl.query")) {
            List<String> rows = jdbcTemplate.queryForList(
                    "SELECT prompt_template FROM ab_agent_skill " +
                    "WHERE tenant_id = ? AND skill_code = ? AND is_builtin = TRUE",
                    String.class,
                    SYSTEM_TENANT_ID, code);

            assertThat(rows)
                    .as("atomic skill '%s' must be seeded as builtin", code)
                    .isNotEmpty();

            String promptTemplate = rows.get(0);
            // prompt_template may legitimately be null for atomic skills;
            // if present, it must not reference delegate_task.
            if (promptTemplate != null) {
                assertThat(promptTemplate)
                        .as("atomic skill '%s' prompt_template must not delegate", code)
                        .doesNotContain("platform.delegate_task");
            }
        }
    }

    // =========================================================================
    // execution_config (F.2) — Extended Thinking opt-in for report_analysis
    // =========================================================================

    /**
     * F.2: report_analysis is a multi-hop reasoning skill — its execution_config
     * must opt in to Anthropic Extended Thinking with a 8000-token budget so
     * StepLoopService.resolveThinkingConfig surfaces a non-null ThinkingConfig.
     */
    @Test
    @Order(20)
    void seed_reportAnalysisSkill_hasThinkingEnabledExecutionConfig() {
        Boolean thinkingEnabled = jdbcTemplate.queryForObject(
                "SELECT (execution_config->>'thinking_enabled')::boolean " +
                "FROM ab_agent_skill " +
                "WHERE tenant_id = ? AND skill_code = 'report_analysis'",
                Boolean.class,
                SYSTEM_TENANT_ID);
        Integer budget = jdbcTemplate.queryForObject(
                "SELECT (execution_config->>'thinking_budget_tokens')::int " +
                "FROM ab_agent_skill " +
                "WHERE tenant_id = ? AND skill_code = 'report_analysis'",
                Integer.class,
                SYSTEM_TENANT_ID);

        assertThat(thinkingEnabled)
                .as("report_analysis skill must opt in to Extended Thinking")
                .isTrue();
        assertThat(budget)
                .as("report_analysis thinking budget must be 8000 tokens")
                .isEqualTo(8000);
    }

    /**
     * F.2 negative case: the four non-analytical skills must keep execution_config = {}.
     * Guarantees we did not accidentally enable thinking on workflow skills where
     * Anthropic billing or non-thinking-capable models would produce HTTP 400.
     */
    @Test
    @Order(21)
    void seed_otherSkills_keepEmptyExecutionConfig() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT skill_code, execution_config::text AS cfg " +
                "FROM ab_agent_skill " +
                "WHERE tenant_id = ? AND is_builtin = TRUE " +
                "AND skill_code IN ('approval_workflow','data_entry_assistant','crm_operations','ops_inspector') " +
                "ORDER BY skill_code",
                SYSTEM_TENANT_ID);

        assertThat(rows).hasSize(4);
        for (Map<String, Object> row : rows) {
            String code = (String) row.get("skill_code");
            String cfg = (String) row.get("cfg");
            assertThat(cfg)
                    .as("execution_config for non-analytical skill '%s' must be empty JSON {}", code)
                    .isEqualTo("{}");
        }
    }

    /**
     * F.2 idempotency: re-running the seeder must NOT corrupt the report_analysis
     * execution_config. The ON CONFLICT DO UPDATE clause refreshes execution_config
     * to the seeder's canonical value, so the second run still sees thinking_enabled=true.
     */
    @Test
    @Order(22)
    void seed_executionConfig_isIdempotentAcrossReseeds() {
        // First re-run
        agentTemplateSeeder.seed();
        Boolean firstRun = jdbcTemplate.queryForObject(
                "SELECT (execution_config->>'thinking_enabled')::boolean " +
                "FROM ab_agent_skill " +
                "WHERE tenant_id = ? AND skill_code = 'report_analysis'",
                Boolean.class,
                SYSTEM_TENANT_ID);

        // Second re-run
        agentTemplateSeeder.seed();
        Boolean secondRun = jdbcTemplate.queryForObject(
                "SELECT (execution_config->>'thinking_enabled')::boolean " +
                "FROM ab_agent_skill " +
                "WHERE tenant_id = ? AND skill_code = 'report_analysis'",
                Boolean.class,
                SYSTEM_TENANT_ID);

        assertThat(firstRun).isTrue();
        assertThat(secondRun).isTrue();
    }

    // =========================================================================
    // Agent Profile Templates
    // =========================================================================

    @Test
    @Order(5)
    void agentTemplates_areSeeded_withExpectedCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_definition WHERE tenant_id = ? " +
                "AND deleted_flag = FALSE AND agent_code LIKE 'tpl_%'",
                Integer.class,
                SYSTEM_TENANT_ID);
        assertThat(count).isEqualTo(3);
    }

    @Test
    @Order(6)
    void agentTemplates_containExpectedCodes() {
        List<String> codes = jdbcTemplate.queryForList(
                "SELECT agent_code FROM ab_agent_definition WHERE tenant_id = ? " +
                "AND deleted_flag = FALSE AND agent_code LIKE 'tpl_%' ORDER BY agent_code",
                String.class,
                SYSTEM_TENANT_ID);
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
                "WHERE tenant_id = ? AND deleted_flag = FALSE",
                SYSTEM_TENANT_ID);

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
                "SELECT skills FROM ab_agent_definition WHERE tenant_id = ? AND agent_code = 'tpl_aurabot_internal'",
                String.class,
                SYSTEM_TENANT_ID);
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
                "SELECT skills FROM ab_agent_definition WHERE tenant_id = ? AND agent_code = 'tpl_approval_assistant'",
                String.class,
                SYSTEM_TENANT_ID);
        assertThat(skills).contains("approval_workflow");
        // Should NOT include CRM or full ops skills
        assertThat(skills).doesNotContain("crm_operations");
    }

    @Test
    @Order(10)
    void agentTemplate_aurabotInternal_soulProfile_isStructuredJson() {
        String soulProfileJson = jdbcTemplate.queryForObject(
                "SELECT soul_profile::text FROM ab_agent_definition WHERE tenant_id = ? AND agent_code = 'tpl_aurabot_internal'",
                String.class,
                SYSTEM_TENANT_ID);

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
                "SELECT soul_profile::text FROM ab_agent_definition WHERE tenant_id = ? AND agent_code = 'tpl_approval_assistant'",
                String.class,
                SYSTEM_TENANT_ID);

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
                "SELECT soul_profile::text FROM ab_agent_definition WHERE tenant_id = ? AND agent_code = 'tpl_customer_service'",
                String.class,
                SYSTEM_TENANT_ID);

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
                "WHERE tenant_id = ? AND deleted_flag = FALSE AND agent_code LIKE 'tpl_%' " +
                "ORDER BY agent_code",
                SYSTEM_TENANT_ID);

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
                "WHERE tenant_id = ? AND deleted_flag = FALSE AND agent_code LIKE 'tpl_%'",
                SYSTEM_TENANT_ID);

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
                "WHERE a.tenant_id = ? AND a.deleted_flag = FALSE AND a.agent_code LIKE 'tpl_%' " +
                "ORDER BY a.agent_code",
                SYSTEM_TENANT_ID);

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
                "WHERE a.tenant_id = ? AND a.deleted_flag = FALSE AND a.agent_code LIKE 'tpl_%'",
                SYSTEM_TENANT_ID);

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
                "SELECT COUNT(*) FROM ab_agent_definition WHERE tenant_id = ? " +
                "AND deleted_flag = FALSE AND system_user_id IS NOT NULL",
                Integer.class,
                SYSTEM_TENANT_ID);

        // Re-run the seeder — should be a no-op for identity binding
        agentTemplateSeeder.seed();

        Integer userCountAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_user WHERE user_type = 'system_agent' AND deleted_flag = FALSE",
                Integer.class);
        Integer agentsWithUserAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_definition WHERE tenant_id = ? " +
                "AND deleted_flag = FALSE AND system_user_id IS NOT NULL",
                Integer.class,
                SYSTEM_TENANT_ID);

        assertThat(userCountAfter).isEqualTo(userCountBefore);
        assertThat(agentsWithUserAfter).isEqualTo(agentsWithUserBefore);
    }
}
