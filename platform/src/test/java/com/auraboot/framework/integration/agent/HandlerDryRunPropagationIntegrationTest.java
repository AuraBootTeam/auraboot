package com.auraboot.framework.integration.agent;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.CommandHandler;
import com.auraboot.framework.meta.service.CommandHandlerContext;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.meta.service.impl.pipeline.phases.HandlerPhase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-50: verifies that {@link HandlerPhase} propagates
 * {@link CommandExecuteRequest#isDryRun()} to the Spring-bean
 * {@link CommandHandler} via {@link CommandHandlerContext#isDryRun()}.
 *
 * <p>Handlers that produce side effects outside the JDBC connection
 * (HTTP, email, MQ, external DB) MUST early-return when the flag is set,
 * because the CommandPipeline transaction rollback only covers writes
 * issued through the pooled DataSource.
 */
@DisplayName("HandlerPhase — dryRun propagation to command handlers (PR-50)")
@Import(HandlerDryRunPropagationIntegrationTest.TestHandlers.class)
class HandlerDryRunPropagationIntegrationTest extends BaseIntegrationTest {

    static final String HANDLER_BEAN_NAME = "pr50TestRecordingHandler";

    @Autowired private HandlerPhase handlerPhase;
    @Autowired private RecordingCommandHandler recordingHandler;

    @BeforeEach
    void resetRecorder() {
        recordingHandler.reset();
    }

    @Test
    @DisplayName("dryRun=true on request → ctx.isDryRun() is true inside handler")
    void dryRun_flag_reaches_handler() {
        CommandPipelineContext ctx = buildCtx(true);

        handlerPhase.execute(ctx);

        Boolean captured = recordingHandler.lastDryRun.get();
        assertThat(captured).as("handler must receive dryRun=true").isTrue();
    }

    @Test
    @DisplayName("dryRun=false (default) → ctx.isDryRun() is false inside handler")
    void regular_execution_sees_false_flag() {
        CommandPipelineContext ctx = buildCtx(false);

        handlerPhase.execute(ctx);

        Boolean captured = recordingHandler.lastDryRun.get();
        assertThat(captured).as("handler must receive dryRun=false by default").isFalse();
    }

    private CommandPipelineContext buildCtx(boolean dryRun) {
        CommandDefinition command = new CommandDefinition();
        command.setCode("pr50_test_cmd");
        // modelCode left null so persistHandlerResults short-circuits
        // (handler returns empty map anyway).

        BindingRule rule = new BindingRule();
        rule.setRuleType("handler");
        rule.setHandlerClass(HANDLER_BEAN_NAME);
        rule.setConfig(null);

        Map<String, List<BindingRule>> rulesByType = new HashMap<>();
        rulesByType.put("handler", List.of(rule));

        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setPayload(Collections.emptyMap());
        request.setDryRun(dryRun);

        return CommandPipelineContext.builder()
                .commandCode(command.getCode())
                .request(request)
                .tenantId(1L)
                .userId(1L)
                .startTime(System.currentTimeMillis())
                .command(command)
                .payload(new HashMap<>())
                .execConfig(new HashMap<>())
                .rulesByType(rulesByType)
                .fieldMapResults(new HashMap<>())
                .handlerResults(new HashMap<>())
                .build();
    }

    /**
     * Test-only {@link CommandHandler} stub that records the observed
     * {@code dryRun} flag from the context it receives.
     */
    static class RecordingCommandHandler implements CommandHandler {
        final AtomicReference<Boolean> lastDryRun = new AtomicReference<>();

        @Override
        public String getHandlerName() {
            return HANDLER_BEAN_NAME;
        }

        @Override
        public Map<String, Object> execute(CommandHandlerContext context) {
            lastDryRun.set(context.isDryRun());
            return Collections.emptyMap();
        }

        void reset() {
            lastDryRun.set(null);
        }
    }

    @TestConfiguration
    static class TestHandlers {
        @Bean(name = HANDLER_BEAN_NAME)
        RecordingCommandHandler pr50TestRecordingHandler() {
            return new RecordingCommandHandler();
        }
    }
}
