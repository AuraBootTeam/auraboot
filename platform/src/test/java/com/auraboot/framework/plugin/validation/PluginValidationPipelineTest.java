package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.PluginManifest;
import com.auraboot.framework.plugin.dto.imports.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the plugin validation pipeline and individual validators.
 */
class PluginValidationPipelineTest {

    // ==================== CircularDependencyValidator ====================

    @Test
    void circularDependencyValidator_noCycle() {
        // A → B → C (no cycle)
        Map<String, Set<String>> graph = Map.of(
                "A", Set.of("B"),
                "B", Set.of("C"),
                "C", Set.of()
        );
        assertNull(CircularDependencyValidator.detectCycle(graph, "A"));
    }

    @Test
    void circularDependencyValidator_directCycle() {
        // A → B → A (direct cycle)
        Map<String, Set<String>> graph = Map.of(
                "A", Set.of("B"),
                "B", Set.of("A")
        );
        List<String> cycle = CircularDependencyValidator.detectCycle(graph, "A");
        assertNotNull(cycle);
        assertTrue(cycle.size() >= 2);
        assertEquals(cycle.get(0), cycle.get(cycle.size() - 1)); // cycle is closed
    }

    @Test
    void circularDependencyValidator_indirectCycle() {
        // A → B → C → A
        Map<String, Set<String>> graph = Map.of(
                "A", Set.of("B"),
                "B", Set.of("C"),
                "C", Set.of("A")
        );
        List<String> cycle = CircularDependencyValidator.detectCycle(graph, "A");
        assertNotNull(cycle);
        assertTrue(cycle.size() >= 3);
    }

    @Test
    void circularDependencyValidator_emptyDeps() {
        Map<String, Set<String>> graph = Map.of("A", Set.of());
        assertNull(CircularDependencyValidator.detectCycle(graph, "A"));
    }

    @Test
    void circularDependencyValidator_selfCycle() {
        // A → A
        Map<String, Set<String>> graph = Map.of("A", Set.of("A"));
        List<String> cycle = CircularDependencyValidator.detectCycle(graph, "A");
        assertNotNull(cycle);
    }

    // ==================== NamespaceConsistencyValidator ====================

    @Test
    void namespaceValidator_correctPrefixes() {
        NamespaceConsistencyValidator validator = new NamespaceConsistencyValidator();

        PluginManifestExtended manifest = new PluginManifestExtended();
        ModelDefinitionDTO model = new ModelDefinitionDTO();
        model.setCode("pe_customer");
        manifest.setModels(List.of(model));

        CommandDefinitionDTO cmd = new CommandDefinitionDTO();
        cmd.setCode("pe:create-customer");
        manifest.setCommands(List.of(cmd));

        PluginValidationContext ctx = PluginValidationContext.builder()
                .pluginId("com.test.plugin")
                .namespace("pe")
                .manifest(manifest)
                .build();

        List<PluginValidationMessage> messages = validator.validate(ctx);
        assertTrue(messages.isEmpty(), "Should have no warnings for correct prefixes");
    }

    @Test
    void namespaceValidator_wrongPrefixes() {
        NamespaceConsistencyValidator validator = new NamespaceConsistencyValidator();

        PluginManifestExtended manifest = new PluginManifestExtended();
        ModelDefinitionDTO model = new ModelDefinitionDTO();
        model.setCode("customer"); // missing "pe_" prefix
        manifest.setModels(List.of(model));

        CommandDefinitionDTO cmd = new CommandDefinitionDTO();
        cmd.setCode("create-customer"); // missing "pe:" prefix
        manifest.setCommands(List.of(cmd));

        PluginValidationContext ctx = PluginValidationContext.builder()
                .pluginId("com.test.plugin")
                .namespace("pe")
                .manifest(manifest)
                .build();

        List<PluginValidationMessage> messages = validator.validate(ctx);
        assertEquals(2, messages.size());
        assertTrue(messages.stream().allMatch(m -> "warning".equals(m.getSeverity())));
    }

    @Test
    void namespaceValidator_tableBoundModelExempt() {
        NamespaceConsistencyValidator validator = new NamespaceConsistencyValidator();

        PluginManifestExtended manifest = new PluginManifestExtended();
        ModelDefinitionDTO model = new ModelDefinitionDTO();
        model.setCode("sla_config"); // not prefixed with "pe_"
        model.setTableName("ab_sla_config"); // bound to existing table (first-class field)
        manifest.setModels(List.of(model));

        PluginValidationContext ctx = PluginValidationContext.builder()
                .pluginId("com.test.plugin")
                .namespace("pe")
                .manifest(manifest)
                .build();

        List<PluginValidationMessage> messages = validator.validate(ctx);
        assertTrue(messages.isEmpty(), "Table-bound models should be exempt from namespace check");
    }

    // ==================== ExecutionConfigValidator ====================

    @Test
    void executionConfigValidator_validConfig() {
        ExecutionConfigValidator validator = new ExecutionConfigValidator();

        CommandDefinitionDTO cmd = new CommandDefinitionDTO();
        cmd.setCode("pe:create-order");
        cmd.setModelCode("pe_order");
        cmd.setType("create");

        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setCommands(List.of(cmd));

        PluginValidationContext ctx = PluginValidationContext.builder()
                .pluginId("com.test.plugin")
                .namespace("pe")
                .manifest(manifest)
                .build();

        List<PluginValidationMessage> messages = validator.validate(ctx);
        assertTrue(messages.isEmpty());
    }

    @Test
    void executionConfigValidator_invalidType() {
        ExecutionConfigValidator validator = new ExecutionConfigValidator();

        CommandDefinitionDTO cmd = new CommandDefinitionDTO();
        cmd.setCode("pe:bad-cmd");
        cmd.setModelCode("pe_order");
        cmd.setType("invalid_type");

        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setCommands(List.of(cmd));

        PluginValidationContext ctx = PluginValidationContext.builder()
                .pluginId("com.test.plugin")
                .namespace("pe")
                .manifest(manifest)
                .build();

        List<PluginValidationMessage> messages = validator.validate(ctx);
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).isError());
        assertTrue(messages.get(0).getMessage().contains("invalid_type"));
    }

    @Test
    void executionConfigValidator_stateTransitionMissingFields() {
        ExecutionConfigValidator validator = new ExecutionConfigValidator();

        CommandDefinitionDTO cmd = new CommandDefinitionDTO();
        cmd.setCode("pe:submit-order");
        cmd.setModelCode("pe_order");
        cmd.setType("state_transition");
        // Missing stateField, toState, and stateTransitionRules

        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setCommands(List.of(cmd));

        PluginValidationContext ctx = PluginValidationContext.builder()
                .pluginId("com.test.plugin")
                .namespace("pe")
                .manifest(manifest)
                .build();

        List<PluginValidationMessage> messages = validator.validate(ctx);
        assertTrue(messages.size() >= 2, "Should report missing stateField and toState/stateTransitionRules");
        assertTrue(messages.stream().allMatch(PluginValidationMessage::isWarning));
    }

    @Test
    void executionConfigValidator_stateTransitionWithRules() {
        ExecutionConfigValidator validator = new ExecutionConfigValidator();

        CommandDefinitionDTO cmd = new CommandDefinitionDTO();
        cmd.setCode("pe:submit-order");
        cmd.setModelCode("pe_order");
        cmd.setType("state_transition");
        cmd.setStateField("status");
        cmd.setStateTransitionRules(List.of(
                CommandDefinitionDTO.StateTransitionRuleConfig.builder()
                        .guard("payload.approved == true")
                        .toState("approved")
                        .build()
        ));

        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setCommands(List.of(cmd));

        PluginValidationContext ctx = PluginValidationContext.builder()
                .pluginId("com.test.plugin")
                .namespace("pe")
                .manifest(manifest)
                .build();

        List<PluginValidationMessage> messages = validator.validate(ctx);
        assertTrue(messages.isEmpty(), "STATE_TRANSITION with stateField + stateTransitionRules should be valid");
    }

    // ==================== I18nCoverageValidator ====================

    @Test
    void i18nCoverageValidator_fullCoverage() {
        I18nCoverageValidator validator = new I18nCoverageValidator();

        PluginManifestExtended manifest = new PluginManifestExtended();

        ModelDefinitionDTO model = new ModelDefinitionDTO();
        model.setCode("pe_order");
        manifest.setModels(List.of(model));

        ModelFieldBindingDTO binding = new ModelFieldBindingDTO();
        binding.setModelCode("pe_order");
        binding.setFieldCode("title");
        manifest.setModelFieldBindings(List.of(binding));

        I18nDefinitionDTO i18n1 = new I18nDefinitionDTO();
        i18n1.setKey("model.pe_order._meta.label");
        i18n1.setZhCN("订单");
        i18n1.setEnUS("Order");

        I18nDefinitionDTO i18n2 = new I18nDefinitionDTO();
        i18n2.setKey("model.pe_order.title.label");
        i18n2.setZhCN("标题");
        i18n2.setEnUS("Title");

        manifest.setI18nResources(List.of(i18n1, i18n2));

        PluginValidationContext ctx = PluginValidationContext.builder()
                .pluginId("com.test.plugin")
                .namespace("pe")
                .manifest(manifest)
                .build();

        List<PluginValidationMessage> messages = validator.validate(ctx);
        assertTrue(messages.isEmpty(), "Full i18n coverage should produce no messages");
    }

    @Test
    void i18nCoverageValidator_missingKeys() {
        I18nCoverageValidator validator = new I18nCoverageValidator();

        PluginManifestExtended manifest = new PluginManifestExtended();

        ModelDefinitionDTO model = new ModelDefinitionDTO();
        model.setCode("pe_order");
        manifest.setModels(List.of(model));

        ModelFieldBindingDTO binding = new ModelFieldBindingDTO();
        binding.setModelCode("pe_order");
        binding.setFieldCode("title");
        manifest.setModelFieldBindings(List.of(binding));

        manifest.setI18nResources(List.of()); // no i18n

        PluginValidationContext ctx = PluginValidationContext.builder()
                .pluginId("com.test.plugin")
                .namespace("pe")
                .manifest(manifest)
                .build();

        List<PluginValidationMessage> messages = validator.validate(ctx);
        assertEquals(2, messages.size());
        // All should be info-level (not blocking)
        assertTrue(messages.stream().noneMatch(PluginValidationMessage::isError));
    }

    // ==================== PageSchemaValidator ====================

    @Test
    void pageSchemaValidator_validPage() {
        PageSchemaValidator validator = new PageSchemaValidator();

        PluginManifestExtended manifest = new PluginManifestExtended();
        PageSchemaDTO page = new PageSchemaDTO();
        page.setPageKey("pe_order_list");
        page.setDslSchema(Map.of(
                "kind", "List",
                "layout", Map.of("areas", List.of("main")),
                "areas", Map.of("main", Map.of("blocks", List.of(
                        Map.of("blockType", "table")
                )))
        ));
        manifest.setPages(List.of(page));

        PluginValidationContext ctx = PluginValidationContext.builder()
                .pluginId("com.test.plugin")
                .namespace("pe")
                .manifest(manifest)
                .build();

        List<PluginValidationMessage> messages = validator.validate(ctx);
        assertTrue(messages.isEmpty());
    }

    @Test
    void pageSchemaValidator_missingKind() {
        PageSchemaValidator validator = new PageSchemaValidator();

        PluginManifestExtended manifest = new PluginManifestExtended();
        PageSchemaDTO page = new PageSchemaDTO();
        page.setPageKey("pe_order_form");
        page.setDslSchema(Map.of(
                "layout", Map.of("areas", List.of("main")),
                "areas", Map.of("main", Map.of("blocks", List.of()))
        ));
        manifest.setPages(List.of(page));

        PluginValidationContext ctx = PluginValidationContext.builder()
                .pluginId("com.test.plugin")
                .namespace("pe")
                .manifest(manifest)
                .build();

        List<PluginValidationMessage> messages = validator.validate(ctx);
        assertTrue(messages.stream().anyMatch(m -> m.getCode().equals("S-PAGE-KIND")));
    }

    @Test
    void pageSchemaValidator_unknownBlockType() {
        PageSchemaValidator validator = new PageSchemaValidator();

        PluginManifestExtended manifest = new PluginManifestExtended();
        PageSchemaDTO page = new PageSchemaDTO();
        page.setPageKey("pe_order_list");
        page.setDslSchema(Map.of(
                "kind", "List",
                "layout", Map.of("areas", List.of("main")),
                "areas", Map.of("main", Map.of("blocks", List.of(
                        Map.of("blockType", "unknown-block")
                )))
        ));
        manifest.setPages(List.of(page));

        PluginValidationContext ctx = PluginValidationContext.builder()
                .pluginId("com.test.plugin")
                .namespace("pe")
                .manifest(manifest)
                .build();

        List<PluginValidationMessage> messages = validator.validate(ctx);
        assertTrue(messages.stream().anyMatch(m -> m.getCode().equals("S-PAGE-BLOCK-TYPE")));
    }

    // ==================== PluginValidationResult ====================

    @Test
    void validationResult_empty_isValid() {
        PluginValidationResult result = PluginValidationResult.empty();
        assertTrue(result.isValid());
        assertEquals(0, result.getErrorCount());
    }

    @Test
    void validationResult_addError_becomesInvalid() {
        PluginValidationResult result = PluginValidationResult.empty();
        result.addMessage(PluginValidationMessage.error("test", "test", "test error"));
        assertFalse(result.isValid());
        assertEquals(1, result.getErrorCount());
    }

    @Test
    void validationResult_addWarning_staysValid() {
        PluginValidationResult result = PluginValidationResult.empty();
        result.addMessage(PluginValidationMessage.warning("test", "test", "test warning"));
        assertTrue(result.isValid());
        assertEquals(0, result.getErrorCount());
        assertEquals(1, result.getWarningCount());
    }

    // ==================== Pipeline Integration ====================

    @Test
    void pipeline_shortCircuit_semanticErrorsSkipGovernance() {
        // Create a pipeline with one semantic validator that produces error
        // and one governance validator that should be skipped
        PluginValidator errorSemantic = new PluginValidator() {
            @Override
            public String category() { return "semantic"; }
            @Override
            public List<PluginValidationMessage> validate(PluginValidationContext ctx) {
                return List.of(PluginValidationMessage.error("S-TEST", "semantic", "test error"));
            }
        };

        PluginValidator governance = new PluginValidator() {
            @Override
            public String category() { return "governance"; }
            @Override
            public List<PluginValidationMessage> validate(PluginValidationContext ctx) {
                return List.of(PluginValidationMessage.info("G-TEST", "governance", "should be skipped"));
            }
        };

        PluginValidationPipeline pipeline = new PluginValidationPipeline(List.of(errorSemantic, governance));

        PluginManifestExtended manifest = new PluginManifestExtended();
        PluginValidationContext ctx = PluginValidationContext.builder()
                .pluginId("test")
                .manifest(manifest)
                .build();

        PluginValidationResult result = pipeline.validate(ctx);
        assertFalse(result.isValid());
        assertEquals(1, result.getErrorCount());
        assertEquals(0, result.getInfoCount()); // governance was skipped
    }

    @Test
    void pipeline_noErrors_runsGovernance() {
        PluginValidator warningSemantic = new PluginValidator() {
            @Override
            public String category() { return "semantic"; }
            @Override
            public List<PluginValidationMessage> validate(PluginValidationContext ctx) {
                return List.of(PluginValidationMessage.warning("S-TEST", "semantic", "test warning"));
            }
        };

        PluginValidator governance = new PluginValidator() {
            @Override
            public String category() { return "governance"; }
            @Override
            public List<PluginValidationMessage> validate(PluginValidationContext ctx) {
                return List.of(PluginValidationMessage.info("G-TEST", "governance", "governance info"));
            }
        };

        PluginValidationPipeline pipeline = new PluginValidationPipeline(List.of(warningSemantic, governance));

        PluginManifestExtended manifest = new PluginManifestExtended();
        PluginValidationContext ctx = PluginValidationContext.builder()
                .pluginId("test")
                .manifest(manifest)
                .build();

        PluginValidationResult result = pipeline.validate(ctx);
        assertTrue(result.isValid()); // warnings + info don't invalidate
        assertEquals(1, result.getWarningCount());
        assertEquals(1, result.getInfoCount());
    }

    // ==================== ExtensionValidator — Type Compatibility (GAP-092) ====================

    /** Build a minimal ExtensionValidator with no-op registries (nothing registered → all unknown). */
    private ExtensionValidator buildExtensionValidator() {
        com.auraboot.framework.meta.registry.CommandHandlerRegistry cmdReg =
                new com.auraboot.framework.meta.registry.CommandHandlerRegistry();
        com.auraboot.framework.meta.registry.SideEffectHandlerRegistry seReg =
                new com.auraboot.framework.meta.registry.SideEffectHandlerRegistry();
        com.auraboot.framework.meta.registry.RenderComponentRegistry rcReg =
                new com.auraboot.framework.meta.registry.RenderComponentRegistry();
        return new ExtensionValidator(cmdReg, seReg, rcReg);
    }

    private PluginManifestExtended manifestWithField(String dataType, String renderComponent) {
        FieldDefinitionDTO field = FieldDefinitionDTO.builder()
                .code("test_field")
                .dataType(dataType)
                .extension(Map.of("renderComponent", renderComponent))
                .build();
        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setFields(List.of(field));
        return manifest;
    }

    @Test
    void extensionValidator_compatibleType_noWarning() {
        // STRING + SmartInput is a valid combination — no warning expected
        ExtensionValidator validator = buildExtensionValidator();
        PluginValidationContext ctx = PluginValidationContext.builder()
                .pluginId("test")
                .manifest(manifestWithField("string", "SmartInput"))
                .build();

        List<PluginValidationMessage> msgs = validator.validate(ctx);
        boolean hasTypeCompatWarning = msgs.stream()
                .anyMatch(m -> "S-EXT-TYPE-COMPAT".equals(m.getCode()));
        assertFalse(hasTypeCompatWarning, "SmartInput is compatible with STRING — should not warn");
    }

    @Test
    void extensionValidator_incompatibleType_emitsWarning() {
        // STRING + SmartSwitch is incompatible — should emit S-EXT-TYPE-COMPAT warning
        ExtensionValidator validator = buildExtensionValidator();
        PluginValidationContext ctx = PluginValidationContext.builder()
                .pluginId("test")
                .manifest(manifestWithField("string", "SmartSwitch"))
                .build();

        List<PluginValidationMessage> msgs = validator.validate(ctx);
        boolean hasTypeCompatWarning = msgs.stream()
                .anyMatch(m -> "S-EXT-TYPE-COMPAT".equals(m.getCode()));
        assertTrue(hasTypeCompatWarning, "SmartSwitch is not compatible with STRING — should warn");
        // Warning, not error — plugin should still be considered valid
        assertTrue(msgs.stream().filter(m -> "S-EXT-TYPE-COMPAT".equals(m.getCode()))
                .allMatch(PluginValidationMessage::isWarning));
    }

    @Test
    void extensionValidator_enumWithSmartSelect_noWarning() {
        ExtensionValidator validator = buildExtensionValidator();
        PluginValidationContext ctx = PluginValidationContext.builder()
                .pluginId("test")
                .manifest(manifestWithField("enum", "SmartSelect"))
                .build();
        boolean hasTypeCompatWarning = validator.validate(ctx).stream()
                .anyMatch(m -> "S-EXT-TYPE-COMPAT".equals(m.getCode()));
        assertFalse(hasTypeCompatWarning);
    }

    @Test
    void extensionValidator_enumWithSmartInput_emitsWarning() {
        ExtensionValidator validator = buildExtensionValidator();
        PluginValidationContext ctx = PluginValidationContext.builder()
                .pluginId("test")
                .manifest(manifestWithField("enum", "SmartInput"))
                .build();
        boolean hasTypeCompatWarning = validator.validate(ctx).stream()
                .anyMatch(m -> "S-EXT-TYPE-COMPAT".equals(m.getCode()));
        assertTrue(hasTypeCompatWarning);
    }

    @Test
    void extensionValidator_unknownDataType_noWarning() {
        // Unknown dataType → skip check (no entry in map)
        ExtensionValidator validator = buildExtensionValidator();
        PluginValidationContext ctx = PluginValidationContext.builder()
                .pluginId("test")
                .manifest(manifestWithField("custom_type", "AnythingGoes"))
                .build();
        boolean hasTypeCompatWarning = validator.validate(ctx).stream()
                .anyMatch(m -> "S-EXT-TYPE-COMPAT".equals(m.getCode()));
        assertFalse(hasTypeCompatWarning, "Unknown dataType should skip compatibility check");
    }

    @Test
    void extensionValidator_noRenderComponent_noWarning() {
        // No renderComponent in extension → nothing to check
        FieldDefinitionDTO field = FieldDefinitionDTO.builder()
                .code("no_component")
                .dataType("string")
                .extension(Map.of("jsonbColumn", "data"))
                .build();
        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setFields(List.of(field));
        ExtensionValidator validator = buildExtensionValidator();
        PluginValidationContext ctx = PluginValidationContext.builder()
                .pluginId("test").manifest(manifest).build();
        boolean hasTypeCompatWarning = validator.validate(ctx).stream()
                .anyMatch(m -> "S-EXT-TYPE-COMPAT".equals(m.getCode()));
        assertFalse(hasTypeCompatWarning);
    }
}
