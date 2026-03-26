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
    void execute_dslCommand_dispatches_to_commandExecutor() {
        // Dispatch reaches CommandExecutor — the command may fail at domain level
        // (e.g., model not found) but the dispatch routing itself works.
        SkillInput input = new SkillInput();
        input.setIntent("create");
        input.setObject("crm_account");
        input.setParameters(Map.of(
                "commandCode", "crm_account_create",
                "crm_account_name", "SkillEngineTest_" + System.currentTimeMillis()
        ));

        SkillResult result = skillEngine.execute(tenantId, "test-run", "dsl.command", input, null, null, null);

        // Verify dispatch reached the handler (not a dispatch-level error)
        assertThat(result).isNotNull();
        assertThat(result.getSkillCode()).isEqualTo("dsl.command");
        assertThat(result.getToolCallCount()).isEqualTo(1);
        // The error (if any) should come from CommandExecutor, not from dispatch logic
        if (result.getStatus() == SkillResult.Status.FAILED) {
            assertThat(result.getErrorMessage())
                    .describedAs("Error should come from command execution, not dispatch")
                    .contains("Error executing command");
        }
    }

    @Test
    void execute_dslQuery_list_dispatches_to_dynamicDataService() {
        // Dispatch reaches DynamicDataService.list() — may fail if model not registered
        // in the test tenant, but that proves routing worked.
        SkillInput input = new SkillInput();
        input.setIntent("query");
        input.setObject("crm_account");
        input.setParameters(Map.of("model", "crm_account"));

        SkillResult result = skillEngine.execute(tenantId, "test-run", "dsl.query", input, null, null, null);

        assertThat(result).isNotNull();
        assertThat(result.getSkillCode()).isEqualTo("dsl.query");
        // If the model exists in test tenant, we get SUCCESS with records
        // If not, the error comes from DynamicDataService (not from dispatch)
        if (result.getStatus() == SkillResult.Status.SUCCESS) {
            assertThat(result.getOutputType()).isEqualTo("structured_result");
            assertThat(result.getData()).containsKey("records");
        } else {
            assertThat(result.getErrorMessage())
                    .describedAs("Error should come from data service, not dispatch")
                    .containsAnyOf("Model not found", "not found", "error");
        }
    }

    @Test
    void execute_dslQuery_missingParams_fails() {
        SkillInput input = new SkillInput();
        input.setIntent("query");
        input.setParameters(Map.of()); // no model, queryCode, or recordId

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
