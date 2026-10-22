package com.auraboot.framework.dsl.service;

import com.auraboot.framework.dsl.dto.DslIntrospectionResponse;
import com.auraboot.framework.dsl.dto.DslIntrospectionResponse.*;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for DslIntrospectionService.
 * Validates full schema introspection, single model lookup, and capabilities catalog
 * against the real database and registries.
 */
@Slf4j
@DisplayName("DslIntrospectionService Integration Tests")
class DslIntrospectionServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DslIntrospectionService introspectionService;

    // ==================== Full Schema ====================

    @Test
    @Order(1)
    @DisplayName("getFullSchema returns valid response with all sections")
    void getFullSchema_allScopes_returnsCompleteResponse() {
        DslIntrospectionResponse response = introspectionService.getFullSchema(Set.of());

        assertThat(response).isNotNull();
        assertThat(response.getVersion()).isEqualTo("1.0");
        assertThat(response.getExportedAt()).isNotBlank();
        assertThat(response.getTenantId()).isNotNull();

        // Stats
        assertThat(response.getStats()).isNotNull();
        assertThat(response.getStats().getModelCount()).isGreaterThanOrEqualTo(0);

        // Models list present
        assertThat(response.getModels()).isNotNull();

        // Capabilities present
        assertThat(response.getCapabilities()).isNotNull();
    }

    @Test
    @Order(2)
    @DisplayName("getFullSchema with scope=models excludes capabilities")
    void getFullSchema_modelsScope_excludesCapabilities() {
        DslIntrospectionResponse response = introspectionService.getFullSchema(Set.of("models"));

        assertThat(response).isNotNull();
        assertThat(response.getModels()).isNotNull();
        assertThat(response.getCapabilities()).isNull();
    }

    @Test
    @Order(3)
    @DisplayName("getFullSchema with scope=capabilities excludes models")
    void getFullSchema_capabilitiesScope_excludesModels() {
        DslIntrospectionResponse response = introspectionService.getFullSchema(Set.of("capabilities"));

        assertThat(response).isNotNull();
        assertThat(response.getModels()).isNull();
        assertThat(response.getCapabilities()).isNotNull();
    }

    @Test
    @Order(4)
    @DisplayName("getFullSchema models contain fields, commands, and pages when data exists")
    void getFullSchema_modelsHaveNestedResources() {
        DslIntrospectionResponse response = introspectionService.getFullSchema(Set.of());

        if (response.getModels() != null && !response.getModels().isEmpty()) {
            ModelIntrospection firstModel = response.getModels().get(0);
            assertThat(firstModel.getCode()).isNotBlank();
            assertThat(firstModel.getStatus()).isNotBlank();
            // Fields, commands, pages may be empty for some models, but the lists should exist
            assertThat(firstModel.getFields()).isNotNull();
            assertThat(firstModel.getCommands()).isNotNull();
            assertThat(firstModel.getPages()).isNotNull();
        }
    }

    // ==================== Single Model ====================

    @Test
    @Order(10)
    @DisplayName("getModelSchema returns null for non-existent model")
    void getModelSchema_nonExistent_returnsNull() {
        ModelIntrospection result = introspectionService.getModelSchema("non_existent_model_" + System.currentTimeMillis());
        assertThat(result).isNull();
    }

    @Test
    @Order(11)
    @DisplayName("getModelSchema returns valid introspection for existing model")
    void getModelSchema_existingModel_returnsIntrospection() {
        // First get the full schema to find a model code
        DslIntrospectionResponse full = introspectionService.getFullSchema(Set.of("models"));
        if (full.getModels() == null || full.getModels().isEmpty()) {
            log.info("No models found in tenant, skipping single model test");
            return;
        }

        String modelCode = full.getModels().get(0).getCode();
        ModelIntrospection result = introspectionService.getModelSchema(modelCode);

        assertThat(result).isNotNull();
        assertThat(result.getCode()).isEqualTo(modelCode);
        assertThat(result.getFields()).isNotNull();
        assertThat(result.getCommands()).isNotNull();
        assertThat(result.getPages()).isNotNull();
    }

    // ==================== Capabilities ====================

    @Test
    @Order(20)
    @DisplayName("getAvailableCapabilities returns non-empty data types")
    void getAvailableCapabilities_hasDataTypes() {
        CapabilityCatalog capabilities = introspectionService.getAvailableCapabilities();

        assertThat(capabilities).isNotNull();
        assertThat(capabilities.getDataTypes()).isNotNull().isNotEmpty();
        assertThat(capabilities.getDataTypes()).contains("string", "integer", "boolean", "reference");
    }

    @Test
    @Order(21)
    @DisplayName("getAvailableCapabilities returns non-empty block types")
    void getAvailableCapabilities_hasBlockTypes() {
        CapabilityCatalog capabilities = introspectionService.getAvailableCapabilities();

        assertThat(capabilities.getBlockTypes()).isNotNull().isNotEmpty();
        assertThat(capabilities.getBlockTypes()).contains("form", "table", "chart", "tabs");
    }

    @Test
    @Order(22)
    @DisplayName("getAvailableCapabilities returns non-empty command types")
    void getAvailableCapabilities_hasCommandTypes() {
        CapabilityCatalog capabilities = introspectionService.getAvailableCapabilities();

        assertThat(capabilities.getCommandTypes()).isNotNull().isNotEmpty();
        assertThat(capabilities.getCommandTypes()).contains("create", "update", "delete");
    }

    @Test
    @Order(23)
    @DisplayName("getAvailableCapabilities has render components list")
    void getAvailableCapabilities_hasRenderComponents() {
        CapabilityCatalog capabilities = introspectionService.getAvailableCapabilities();

        // Render components list should exist (may be empty if no runtime registrations)
        assertThat(capabilities.getRenderComponents()).isNotNull();
    }

    @Test
    @Order(24)
    @DisplayName("getAvailableCapabilities has expression functions list")
    void getAvailableCapabilities_hasExpressionFunctions() {
        CapabilityCatalog capabilities = introspectionService.getAvailableCapabilities();

        assertThat(capabilities.getExpressionFunctions()).isNotNull();
    }

    @Test
    @Order(25)
    @DisplayName("getAvailableCapabilities has side effect handlers list")
    void getAvailableCapabilities_hasSideEffectHandlers() {
        CapabilityCatalog capabilities = introspectionService.getAvailableCapabilities();

        assertThat(capabilities.getSideEffectHandlers()).isNotNull();
    }

    @Test
    @Order(26)
    @DisplayName("getAvailableCapabilities has automation actions list")
    void getAvailableCapabilities_hasAutomationActions() {
        CapabilityCatalog capabilities = introspectionService.getAvailableCapabilities();

        assertThat(capabilities.getAutomationActions()).isNotNull();
    }

    // ==================== Field introspection details ====================

    @Test
    @Order(30)
    @DisplayName("Field introspection includes dataType and sortOrder")
    void fieldIntrospection_hasExpectedAttributes() {
        DslIntrospectionResponse full = introspectionService.getFullSchema(Set.of());
        if (full.getModels() == null) return;

        // Find a model that has fields
        for (ModelIntrospection model : full.getModels()) {
            if (model.getFields() != null && !model.getFields().isEmpty()) {
                FieldIntrospection field = model.getFields().get(0);
                assertThat(field.getCode()).isNotBlank();
                assertThat(field.getDataType()).isNotBlank();
                // sortOrder can be 0 but should not be null
                assertThat(field.getSortOrder()).isNotNull();
                return;
            }
        }
        log.info("No models with fields found, skipping field detail test");
    }

    // ==================== Command introspection details ====================

    @Test
    @Order(40)
    @DisplayName("Command introspection includes code and modelCode")
    void commandIntrospection_hasExpectedAttributes() {
        DslIntrospectionResponse full = introspectionService.getFullSchema(Set.of());
        if (full.getModels() == null) return;

        // Find a model that has commands
        for (ModelIntrospection model : full.getModels()) {
            if (model.getCommands() != null && !model.getCommands().isEmpty()) {
                CommandIntrospection cmd = model.getCommands().get(0);
                assertThat(cmd.getCode()).isNotBlank();
                assertThat(cmd.getModelCode()).isNotBlank();
                assertThat(cmd.getStatus()).isNotBlank();
                return;
            }
        }
        log.info("No models with commands found, skipping command detail test");
    }
}
