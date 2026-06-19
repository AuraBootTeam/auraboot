package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Real-stack coverage IT for {@link PostExecutionPhase} — the phase name and the dry-run
 * short-circuit (a dry-run request skips side-effects / roll-up / governance / post-actions).
 * The live side-effect + roll-up recalculation paths need a command with rollup config and
 * stay out of scope here.
 */
@Slf4j
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("PostExecutionPhase Coverage IT — name + dry-run short-circuit")
class PostExecutionPhaseCoverageIT {

    private static final long TENANT_ID = 991_500_001L;

    @Autowired
    private PostExecutionPhase phase;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, 991_500_002L, "post-test-pid", "post-test-user");
    }

    @AfterAll
    void cleanup() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("name() is post_execution and a dry-run request is a no-op")
    void dryRunShortCircuit() {
        assertEquals("post_execution", phase.name());

        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setDryRun(true);
        CommandDefinition command = new CommandDefinition();
        command.setModelCode("post_cov_model");

        CommandPipelineContext ctx = CommandPipelineContext.builder()
                .commandCode("post:cov")
                .command(command)
                .request(request)
                .tenantId(TENANT_ID)
                .userId(991_500_002L)
                .payload(new HashMap<>())
                .execConfig(new HashMap<>())
                .build();

        assertDoesNotThrow(() -> phase.execute(ctx));
    }
}
