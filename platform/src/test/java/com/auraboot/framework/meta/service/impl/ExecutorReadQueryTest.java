package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.entity.BindingRule;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExecutorReadQueryTest {

    private final ExtensionRegistry registry = mock(ExtensionRegistry.class);

    @Test
    void aQueryWithAModelAndNoHandlerIsReadByTheExecutor() {
        assertThat(ExecutorReadQuery.isReadByExecutor(context("query", "crm_account"), registry)).isTrue();
        assertThat(ExecutorReadQuery.isReadByExecutor(context("query", "crm_account"), null)).isTrue();
    }

    @Test
    void isQueryReadsTheDeclaredTypeOnly() {
        assertThat(ExecutorReadQuery.isQuery(Map.of("type", "query"))).isTrue();
        assertThat(ExecutorReadQuery.isQuery(Map.of("type", "QUERY"))).isTrue();
        assertThat(ExecutorReadQuery.isQuery(Map.of("type", "create"))).isFalse();
        assertThat(ExecutorReadQuery.isQuery(Map.of("type", "custom"))).isFalse();
        assertThat(ExecutorReadQuery.isQuery(Map.of("type", 1))).isFalse();
        assertThat(ExecutorReadQuery.isQuery(Map.of())).isFalse();
        assertThat(ExecutorReadQuery.isQuery(null)).isFalse();
    }

    @Test
    void anotherTypeOrAMissingModelIsNotReadByTheExecutor() {
        assertThat(ExecutorReadQuery.isReadByExecutor(context("create", "crm_account"), registry)).isFalse();
        assertThat(ExecutorReadQuery.isReadByExecutor(context("custom", "crm_account"), registry)).isFalse();
        assertThat(ExecutorReadQuery.isReadByExecutor(context("query", " "), registry)).isFalse();
        assertThat(ExecutorReadQuery.isReadByExecutor(context("query", null), registry)).isFalse();
        assertThat(ExecutorReadQuery.isReadByExecutor(null, registry)).isFalse();
    }

    @Test
    void aQueryWhoseBindingRuleNamesAHandlerKeepsThatHandlersContract() {
        CommandPipelineContext ctx = context("query", "crm_account");
        BindingRule rule = new BindingRule();
        rule.setHandlerClass("accountQueryHandler");
        ctx.setRulesByType(new HashMap<>(Map.of("handler", List.of(rule))));

        assertThat(ExecutorReadQuery.isReadByExecutor(ctx, registry)).isFalse();
        assertThat(ExecutorReadQuery.isReadByExecutor(ctx, null)).isFalse();
    }

    @Test
    void aBindingRuleWithoutAHandlerClassIsNoHandler() {
        CommandPipelineContext ctx = context("query", "crm_account");
        BindingRule rule = new BindingRule();
        ctx.setRulesByType(new HashMap<>(Map.of("handler", List.of(rule))));

        assertThat(ExecutorReadQuery.isReadByExecutor(ctx, registry)).isTrue();
    }

    @Test
    void aQueryWithAPluginHandlerPrimaryOrSecondaryKeepsThatHandlersContract() {
        when(registry.getSecondaryCommandHandlers("crm:list_accounts")).thenReturn(List.of());
        when(registry.getCommandHandler("crm:list_accounts"))
                .thenReturn(Optional.of(mock(CommandHandlerExtension.class)));
        assertThat(ExecutorReadQuery.isReadByExecutor(context("query", "crm_account"), registry)).isFalse();

        when(registry.getCommandHandler("crm:list_accounts")).thenReturn(Optional.empty());
        when(registry.getSecondaryCommandHandlers("crm:list_accounts"))
                .thenReturn(List.of(mock(CommandHandlerExtension.class)));
        assertThat(ExecutorReadQuery.isReadByExecutor(context("query", "crm_account"), registry)).isFalse();

        when(registry.getSecondaryCommandHandlers("crm:list_accounts")).thenReturn(List.of());
        assertThat(ExecutorReadQuery.isReadByExecutor(context("query", "crm_account"), registry)).isTrue();
    }

    @Test
    void aConfiguredHandlerCodeIsWhereThePluginHandlerIsLookedUp() {
        CommandPipelineContext ctx = context("query", "crm_account");
        ctx.setExecConfig(new HashMap<>(Map.of("type", "query", "handler", "accountQuery")));
        when(registry.getCommandHandler("accountQuery")).thenReturn(Optional.of(mock(CommandHandlerExtension.class)));
        when(registry.getSecondaryCommandHandlers("accountQuery")).thenReturn(List.of());

        assertThat(ExecutorReadQuery.isReadByExecutor(ctx, registry)).isFalse();
    }

    private CommandPipelineContext context(String type, String modelCode) {
        CommandDefinition command = new CommandDefinition();
        command.setCode("crm:list_accounts");
        command.setModelCode(modelCode);
        return CommandPipelineContext.builder()
                .commandCode(command.getCode())
                .command(command)
                .execConfig(new HashMap<>(Map.of("type", type)))
                .rulesByType(new HashMap<>())
                .build();
    }
}
