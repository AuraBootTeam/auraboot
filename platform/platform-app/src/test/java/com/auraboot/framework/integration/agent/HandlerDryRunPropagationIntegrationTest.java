package com.auraboot.framework.integration.agent;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.CommandHandler;
import com.auraboot.framework.meta.service.CommandHandlerContext;
import com.auraboot.framework.meta.service.DryRunSafe;
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
    static final String UNSAFE_HANDLER_BEAN_NAME = "pr56TestUnsafeHandler";

    @Autowired private HandlerPhase handlerPhase;
    @Autowired private RecordingCommandHandler recordingHandler;
    @Autowired private UnsafeCommandHandler unsafeHandler;

    @BeforeEach
    void resetRecorder() {
        recordingHandler.reset();
        unsafeHandler.reset();
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

    @Test
    @DisplayName("PR-56 C3: handler not annotated @DryRunSafe is skipped under dryRun=true")
    void non_dryrun_safe_handler_skipped_under_dry_run() {
        CommandPipelineContext ctx = buildCtxWithBothHandlers(true);

        handlerPhase.execute(ctx);

        assertThat(recordingHandler.lastDryRun.get())
                .as("safe handler must be invoked").isTrue();
        assertThat(unsafeHandler.invoked.get())
                .as("unsafe handler must be SKIPPED under dry-run").isFalse();
    }

    @Test
    @DisplayName("PR-56 C3: both handlers invoked when dryRun=false")
    void both_handlers_invoked_when_not_dry_run() {
        CommandPipelineContext ctx = buildCtxWithBothHandlers(false);

        handlerPhase.execute(ctx);

        assertThat(recordingHandler.lastDryRun.get())
                .as("safe handler must be invoked").isFalse();
        assertThat(unsafeHandler.invoked.get())
                .as("unsafe handler must be invoked under normal execution").isTrue();
    }

    private CommandPipelineContext buildCtxWithBothHandlers(boolean dryRun) {
        CommandDefinition command = new CommandDefinition();
        command.setCode("pr56_test_cmd");

        BindingRule safeRule = new BindingRule();
        safeRule.setRuleType("handler");
        safeRule.setHandlerClass(HANDLER_BEAN_NAME);

        BindingRule unsafeRule = new BindingRule();
        unsafeRule.setRuleType("handler");
        unsafeRule.setHandlerClass(UNSAFE_HANDLER_BEAN_NAME);

        Map<String, List<BindingRule>> rulesByType = new HashMap<>();
        rulesByType.put("handler", List.of(safeRule, unsafeRule));

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
     * {@code dryRun} flag from the context it receives. Marked
     * {@link DryRunSafe} so HandlerPhase invokes it under dry-run
     * (otherwise the PR-50 propagation assertions cannot observe the flag).
     */
    @DryRunSafe
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

    /**
     * Test-only {@link CommandHandler} that deliberately omits
     * {@link DryRunSafe}. HandlerPhase must skip it under dry-run.
     */
    static class UnsafeCommandHandler implements CommandHandler {
        final java.util.concurrent.atomic.AtomicBoolean invoked =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        @Override
        public String getHandlerName() {
            return UNSAFE_HANDLER_BEAN_NAME;
        }

        @Override
        public Map<String, Object> execute(CommandHandlerContext context) {
            invoked.set(true);
            return Collections.emptyMap();
        }

        void reset() {
            invoked.set(false);
        }
    }

    @TestConfiguration
    static class TestHandlers {
        @Bean(name = HANDLER_BEAN_NAME)
        RecordingCommandHandler pr50TestRecordingHandler() {
            return new RecordingCommandHandler();
        }

        @Bean(name = UNSAFE_HANDLER_BEAN_NAME)
        UnsafeCommandHandler pr56TestUnsafeHandler() {
            return new UnsafeCommandHandler();
        }
    }
}
