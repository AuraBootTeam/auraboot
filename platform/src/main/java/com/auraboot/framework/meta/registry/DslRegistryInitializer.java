package com.auraboot.framework.meta.registry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Populates all 6 open DSL registries with platform built-in entries at startup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DslRegistryInitializer {

    private final CommandHandlerRegistry commandHandlerRegistry;
    private final SideEffectHandlerRegistry sideEffectHandlerRegistry;
    private final AutomationActionRegistry automationActionRegistry;
    private final ExpressionFunctionRegistry expressionFunctionRegistry;
    private final RenderComponentRegistry renderComponentRegistry;
    private final BlockRendererRegistry blockRendererRegistry;

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        registerBuiltinCommandHandlers();
        registerBuiltinSideEffectHandlers();
        registerBuiltinAutomationActions();
        registerBuiltinExpressionFunctions();
        registerBuiltinRenderComponents();
        registerBuiltinBlockRenderers();
        log.info("DSL Registry initialized: {} cmd handlers, {} side effects, {} automation actions, {} expressions, {} components, {} renderers",
                commandHandlerRegistry.getAll().size(),
                sideEffectHandlerRegistry.getAll().size(),
                automationActionRegistry.getAll().size(),
                expressionFunctionRegistry.getAll().size(),
                renderComponentRegistry.getAll().size(),
                blockRendererRegistry.getAll().size());
    }

    private void registerBuiltinCommandHandlers() {
        var reg = commandHandlerRegistry;
        reg.register(new CommandHandlerRegistry.HandlerMeta("create", "platform", "Default CREATE handler", null, "L1"));
        reg.register(new CommandHandlerRegistry.HandlerMeta("update", "platform", "Default UPDATE handler", null, "L1"));
        reg.register(new CommandHandlerRegistry.HandlerMeta("delete", "platform", "Default DELETE handler", null, "L4"));
        reg.register(new CommandHandlerRegistry.HandlerMeta("state_transition", "platform", "State transition handler", null, "L2"));
        reg.register(new CommandHandlerRegistry.HandlerMeta("batch", "platform", "Batch operation handler", null, "L2"));
        reg.register(new CommandHandlerRegistry.HandlerMeta("query", "platform", "Query handler", null, "L0"));
        reg.register(new CommandHandlerRegistry.HandlerMeta("custom", "platform", "Custom logic handler", null, "L2"));
        reg.register(new CommandHandlerRegistry.HandlerMeta("action", "platform", "Generic action handler", null, "L1"));
    }

    private void registerBuiltinSideEffectHandlers() {
        var reg = sideEffectHandlerRegistry;
        reg.register(new SideEffectHandlerRegistry.HandlerMeta("create_record", "platform", "Create a single record"));
        reg.register(new SideEffectHandlerRegistry.HandlerMeta("update_record", "platform", "Update a single record"));
        reg.register(new SideEffectHandlerRegistry.HandlerMeta("batch_create_record", "platform", "Batch create from single template"));
        reg.register(new SideEffectHandlerRegistry.HandlerMeta("batch_create_records", "platform", "Batch create from array payload"));
        reg.register(new SideEffectHandlerRegistry.HandlerMeta("batch_update_record", "platform", "Batch update from single template"));
        reg.register(new SideEffectHandlerRegistry.HandlerMeta("batch_update_records", "platform", "Batch update from array payload"));
        reg.register(new SideEffectHandlerRegistry.HandlerMeta("aggregate", "platform", "Aggregate calculation side effect"));
        reg.register(new SideEffectHandlerRegistry.HandlerMeta("document_flow", "platform", "Document flow tracking"));
    }

    private void registerBuiltinAutomationActions() {
        var reg = automationActionRegistry;
        reg.register(new AutomationActionRegistry.ActionMeta("create_record", "platform", "Create a record"));
        reg.register(new AutomationActionRegistry.ActionMeta("update_record", "platform", "Update a record"));
        reg.register(new AutomationActionRegistry.ActionMeta("execute_command", "platform", "Execute a DSL command"));
        reg.register(new AutomationActionRegistry.ActionMeta("send_notification", "platform", "Send a notification"));
        reg.register(new AutomationActionRegistry.ActionMeta("send_webhook", "platform", "Send a webhook request"));
        reg.register(new AutomationActionRegistry.ActionMeta("call_api", "platform", "Call an external API"));
        reg.register(new AutomationActionRegistry.ActionMeta("conditional", "platform", "Conditional branching"));
        reg.register(new AutomationActionRegistry.ActionMeta("loop", "platform", "Loop over a collection"));
        reg.register(new AutomationActionRegistry.ActionMeta("parallel", "platform", "Execute actions in parallel"));
    }

    private void registerBuiltinExpressionFunctions() {
        var reg = expressionFunctionRegistry;
        reg.register(new ExpressionFunctionRegistry.FunctionMeta("#record", "platform", "variable", "Current record being processed"));
        reg.register(new ExpressionFunctionRegistry.FunctionMeta("#oldRecord", "platform", "variable", "Previous state of the record before update"));
        reg.register(new ExpressionFunctionRegistry.FunctionMeta("#currentUser", "platform", "variable", "Current authenticated user"));
        reg.register(new ExpressionFunctionRegistry.FunctionMeta("#now", "platform", "variable", "Current timestamp"));
        reg.register(new ExpressionFunctionRegistry.FunctionMeta("#env", "platform", "variable", "Environment variables"));
        reg.register(new ExpressionFunctionRegistry.FunctionMeta("#sourceRecordId", "platform", "variable", "Source record ID for side effects"));
        reg.register(new ExpressionFunctionRegistry.FunctionMeta("#payload", "platform", "variable", "Command execution payload"));
    }

    private void registerBuiltinRenderComponents() {
        var reg = renderComponentRegistry;
        reg.register(new RenderComponentRegistry.ComponentMeta("input", "platform", List.of("string"), "basic"));
        reg.register(new RenderComponentRegistry.ComponentMeta("number", "platform", List.of("integer", "decimal"), "basic"));
        reg.register(new RenderComponentRegistry.ComponentMeta("select", "platform", List.of("enum"), "basic"));
        reg.register(new RenderComponentRegistry.ComponentMeta("date", "platform", List.of("date"), "basic"));
        reg.register(new RenderComponentRegistry.ComponentMeta("datetime", "platform", List.of("datetime"), "basic"));
        reg.register(new RenderComponentRegistry.ComponentMeta("switch", "platform", List.of("boolean"), "basic"));
        reg.register(new RenderComponentRegistry.ComponentMeta("upload", "platform", List.of("file"), "basic"));
        reg.register(new RenderComponentRegistry.ComponentMeta("richtext", "platform", List.of("text"), "editor"));
        reg.register(new RenderComponentRegistry.ComponentMeta("textarea", "platform", List.of("text"), "basic"));
        reg.register(new RenderComponentRegistry.ComponentMeta("radio-group", "platform", List.of("enum"), "basic"));
        reg.register(new RenderComponentRegistry.ComponentMeta("checkbox-group", "platform", List.of("enum"), "basic"));
        reg.register(new RenderComponentRegistry.ComponentMeta("color-picker", "platform", List.of("string"), "advanced"));
        reg.register(new RenderComponentRegistry.ComponentMeta("tag-input", "platform", List.of("json"), "advanced"));
        reg.register(new RenderComponentRegistry.ComponentMeta("image", "platform", List.of("string"), "display"));
        reg.register(new RenderComponentRegistry.ComponentMeta("avatar", "platform", List.of("string"), "display"));
        reg.register(new RenderComponentRegistry.ComponentMeta("progress", "platform", List.of("decimal"), "display"));
        reg.register(new RenderComponentRegistry.ComponentMeta("rating", "platform", List.of("integer"), "display"));

        // Phase W — 18 widgets surfaced via /api/dsl/registry so the designer
        // can list them alongside core widgets. dataTypes inferred from each widget's
        // intended field semantics; category aligns with client-side category buckets
        // (`input` / `selection` / `display` / `advanced`). Three of these
        // (`richtext` / `progress` / `rating`) were registered above with the
        // core entries — re-registration is idempotent (last write wins) and
        // keeps the catalog aligned with Phase W's declared dataTypes.
        reg.register(new RenderComponentRegistry.ComponentMeta("multiselect", "platform", List.of("enum", "json"), "selection"));
        reg.register(new RenderComponentRegistry.ComponentMeta("progress", "platform", List.of("decimal", "integer"), "display"));
        reg.register(new RenderComponentRegistry.ComponentMeta("rating", "platform", List.of("integer", "decimal"), "input"));
        reg.register(new RenderComponentRegistry.ComponentMeta("colorpicker", "platform", List.of("string"), "input"));
        reg.register(new RenderComponentRegistry.ComponentMeta("moneyinput", "platform", List.of("decimal"), "input"));
        reg.register(new RenderComponentRegistry.ComponentMeta("timepicker", "platform", List.of("time", "string"), "input"));
        reg.register(new RenderComponentRegistry.ComponentMeta("daterange", "platform", List.of("date", "json"), "input"));
        reg.register(new RenderComponentRegistry.ComponentMeta("timerangepicker", "platform", List.of("time", "json"), "input"));
        reg.register(new RenderComponentRegistry.ComponentMeta("cascadeselect", "platform", List.of("string", "json"), "selection"));
        reg.register(new RenderComponentRegistry.ComponentMeta("treeselect", "platform", List.of("string", "json"), "selection"));
        reg.register(new RenderComponentRegistry.ComponentMeta("userselect", "platform", List.of("string", "reference"), "selection"));
        reg.register(new RenderComponentRegistry.ComponentMeta("memberpicker", "platform", List.of("string", "reference"), "selection"));
        reg.register(new RenderComponentRegistry.ComponentMeta("organizationselect", "platform", List.of("string", "reference"), "selection"));
        reg.register(new RenderComponentRegistry.ComponentMeta("coordinatespicker", "platform", List.of("string", "json"), "advanced"));
        reg.register(new RenderComponentRegistry.ComponentMeta("aifield", "platform", List.of("string", "text"), "advanced"));
        reg.register(new RenderComponentRegistry.ComponentMeta("addressfield", "platform", List.of("string", "json"), "advanced"));
        reg.register(new RenderComponentRegistry.ComponentMeta("richtext", "platform", List.of("text", "string"), "input"));
        reg.register(new RenderComponentRegistry.ComponentMeta("fileattachment", "platform", List.of("file", "json"), "input"));
    }

    private void registerBuiltinBlockRenderers() {
        var reg = blockRendererRegistry;
        reg.register(new BlockRendererRegistry.RendererMeta("form", "platform", "Form layout renderer"));
        reg.register(new BlockRendererRegistry.RendererMeta("form-section", "platform", "Form section renderer"));
        reg.register(new BlockRendererRegistry.RendererMeta("form-buttons", "platform", "Form button bar renderer"));
        reg.register(new BlockRendererRegistry.RendererMeta("form-wizard", "platform", "Multi-step form wizard renderer"));
        reg.register(new BlockRendererRegistry.RendererMeta("table", "platform", "Table renderer"));
        reg.register(new BlockRendererRegistry.RendererMeta("table", "platform", "Data table renderer"));
        reg.register(new BlockRendererRegistry.RendererMeta("filters", "platform", "Filter panel renderer"));
        reg.register(new BlockRendererRegistry.RendererMeta("filters", "platform", "Filter form renderer"));
        reg.register(new BlockRendererRegistry.RendererMeta("toolbar", "platform", "Toolbar renderer"));
        reg.register(new BlockRendererRegistry.RendererMeta("action", "platform", "Action button renderer"));
        reg.register(new BlockRendererRegistry.RendererMeta("description", "platform", "Description/detail renderer"));
        reg.register(new BlockRendererRegistry.RendererMeta("chart", "platform", "Chart renderer"));
        reg.register(new BlockRendererRegistry.RendererMeta("tabs", "platform", "Tab container renderer"));
    }
}
