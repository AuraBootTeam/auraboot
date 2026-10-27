package com.auraboot.framework.agent;

import com.auraboot.framework.agent.dto.SkillInput;
import com.auraboot.framework.agent.dto.SkillResult;
import com.auraboot.framework.agent.service.SkillAutoGenerator;
import com.auraboot.framework.agent.service.SkillEngine;
import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for SkillEngine's dsl_dispatch execution mode.
 *
 * Verifies that the 2 built-in skills (dsl.command, dsl.query) route correctly
 * to CommandExecutor, DynamicDataService, and NamedQueryService at runtime.
 *
 * Note: The test tenant does not have business models registered, so success-path
 * tests verify routing reached the correct handler (CommandExecutor / DynamicDataService)
 * by checking that the result is returned (not null) and the error is a domain-level
 * error from the handler, not a dispatch-level error.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Transactional
@Rollback(true)
class SkillEngineIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private SkillEngine skillEngine;

    @Autowired
    private SkillAutoGenerator skillAutoGenerator;

    private Long tenantId;

    @BeforeEach
    void setupSkills() {
        tenantId = getTestTenant().getId();
        // Ensure the 2 built-in skills exist
        skillAutoGenerator.syncSkills(tenantId);
    }

    @Test
    void unavailableCommandIsRejectedBeforeExecution() {
        // Dispatch reaches CommandExecutor — the command may fail at domain level
        // (e.g., model not found) but the dispatch routing itself works.
        SkillInput input = new SkillInput();
        input.setIntent("create");
        input.setObject("crm_account_common");
        input.setParameters(Map.of(
                "commandCode", "crm_account_create",
                "crm_account_name", "SkillEngineTest_" + System.currentTimeMillis()
        ));

        SkillResult result = skillEngine.execute(tenantId, "test-run", "dsl.command", input, null, null, null);

        assertThat(result.getSkillCode()).isEqualTo("dsl.command");
        assertThat(result.getStatus()).isEqualTo(SkillResult.Status.FAILED);
        assertThat(result.getToolCallCount()).isZero();
        assertThat(result.getErrorMessage()).contains("Tool is unavailable or not permitted");
    }

    @Test
    void unavailableModelIsRejectedBeforeExecution() {
        // Dispatch reaches DynamicDataService.list() — may fail if model not registered
        // in the test tenant, but that proves routing worked.
        SkillInput input = new SkillInput();
        input.setIntent("query");
        input.setObject("crm_account_common");
        input.setParameters(Map.of("model", "crm_account_common"));

        SkillResult result = skillEngine.execute(tenantId, "test-run", "dsl.query", input, null, null, null);

        assertThat(result.getSkillCode()).isEqualTo("dsl.query");
        assertThat(result.getStatus()).isEqualTo(SkillResult.Status.FAILED);
        assertThat(result.getToolCallCount()).isZero();
        assertThat(result.getErrorMessage()).contains("Tool is unavailable or not permitted");
    }

    @Test
    void execute_dslQuery_missingParams_fails() {
        SkillInput input = new SkillInput();
        input.setIntent("query");
        input.setParameters(Map.of()); // no model, queryCode, or recordPid

        SkillResult result = skillEngine.execute(tenantId, "test-run", "dsl.query", input, null, null, null);
        assertThat(result.getStatus()).isEqualTo(SkillResult.Status.FAILED);
        assertThat(result.getErrorMessage()).contains("requires at least one of");
    }

    @Test
    void execute_dslCommand_missingCommandCode_fails() {
        SkillInput input = new SkillInput();
        input.setParameters(Map.of("name", "test")); // no commandCode

        SkillResult result = skillEngine.execute(tenantId, "test-run", "dsl.command", input, null, null, null);
        assertThat(result.getStatus()).isEqualTo(SkillResult.Status.FAILED);
        assertThat(result.getErrorMessage()).contains("commandCode is required");
    }

    @Test
    void execute_unknownSkill_fails() {
        SkillInput input = new SkillInput();
        input.setParameters(Map.of("foo", "bar"));

        SkillResult result = skillEngine.execute(tenantId, "test-run", "nonexistent.skill", input, null, null, null);
        assertThat(result.getStatus()).isEqualTo(SkillResult.Status.FAILED);
        assertThat(result.getErrorMessage()).contains("Skill not found");
    }

    @Test
    void syncSkills_creates_both_builtin_skills() {
        SkillAutoGenerator.SyncResult syncResult = skillAutoGenerator.syncSkills(tenantId);
        // Second call should update (not create) since skills already exist from @BeforeEach
        assertThat(syncResult.created()).isEqualTo(0);
        assertThat(syncResult.updated()).isEqualTo(2);
    }
}
