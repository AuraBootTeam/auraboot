package com.auraboot.framework.meta.service.impl.pipeline;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Command pipeline publishes only an authoritative permit plan")
class CommandPipelinePermitContextTest {

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("PERMIT publishes scope and version to every downstream phase")
    void permitPublishesScopeAndVersionDownstream() {
        AtomicReference<String> scopeSeen = new AtomicReference<>();
        AtomicReference<String> commandSeen = new AtomicReference<>();
        AtomicReference<Long> versionSeen = new AtomicReference<>();
        CommandPipelineContext ctx = context();

        CommandPipeline pipeline = new CommandPipeline(List.of(), List.of(
                phase("assembly", c -> c.setPermitPlan(new CommandPermitPlan(
                        CommandPermitPlan.Decision.PERMIT, null, null, "r-1",
                        CommandPermitPlan.ScopeGrade.SELF, 5L))),
                phase("write", c -> {
                    scopeSeen.set(MetaContext.getCommandPermitScope());
                    commandSeen.set(MetaContext.getAuthorizedCommandCode());
                    versionSeen.set(MetaContext.getCommandExpectedVersion("invoice", "r-1"));
                })));

        pipeline.executeGuardedPhases(ctx);

        assertThat(scopeSeen).hasValue("SELF");
        assertThat(commandSeen).hasValue("invoice:update");
        assertThat(versionSeen).hasValue(5L);
        assertThat(MetaContext.getCommandPermitScope()).isNull();
        assertThat(MetaContext.getAuthorizedCommandCode()).isNull();
    }

    @Test
    @DisplayName("ABSTAIN and DENY never publish a data-layer scope")
    void nonPermitNeverPublishesScope() {
        for (CommandPermitPlan.Decision decision : List.of(
                CommandPermitPlan.Decision.ABSTAIN, CommandPermitPlan.Decision.DENY)) {
            AtomicReference<String> scopeSeen = new AtomicReference<>("not-run");
            AtomicReference<String> commandSeen = new AtomicReference<>("not-run");
            CommandPipeline pipeline = new CommandPipeline(List.of(), List.of(
                    phase("assembly", c -> c.setPermitPlan(new CommandPermitPlan(
                            decision, "reason", "gate", "r-1",
                            CommandPermitPlan.ScopeGrade.ALL, 2L))),
                    phase("write", c -> {
                        scopeSeen.set(MetaContext.getCommandPermitScope());
                        commandSeen.set(MetaContext.getAuthorizedCommandCode());
                    })));

            pipeline.executeGuardedPhases(context());
            assertThat(scopeSeen.get())
                    .as("%s must not publish ALL", decision)
                    .isNull();
            assertThat(commandSeen.get())
                    .as("%s must not publish a trusted command writer identity", decision)
                    .isNull();
        }
    }

    private CommandPipelineContext context() {
        CommandExecuteRequest request = new CommandExecuteRequest();
        request.setTargetRecordId("r-1");
        CommandDefinition command = new CommandDefinition();
        command.setModelCode("invoice");
        CommandPipelineContext ctx = CommandPipelineContext.builder()
                .commandCode("invoice:update")
                .request(request)
                .tenantId(1L)
                .userId(9L)
                .startTime(System.currentTimeMillis())
                .build();
        ctx.setCommand(command);
        return ctx;
    }

    private CommandPhase phase(String name, java.util.function.Consumer<CommandPipelineContext> action) {
        return new CommandPhase() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void execute(CommandPipelineContext ctx) {
                action.accept(ctx);
            }
        };
    }
}
