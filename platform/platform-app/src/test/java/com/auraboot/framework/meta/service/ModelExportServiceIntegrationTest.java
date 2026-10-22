package com.auraboot.framework.meta.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for ModelExportService using real database.
 * Creates test model/fields/commands then verifies export output.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ModelExportServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ModelExportService modelExportService;

    @Autowired
    private MetaModelService metaModelService;

    @Autowired
    private MetaFieldService metaFieldService;

    @Autowired
    private ModelFieldBindingService modelFieldBindingService;

    @Autowired
    private CommandService commandService;

    private String testModelCode;

    @BeforeEach
    void ensureTestModel() {
        testModelCode = "export_test_" + System.nanoTime();

        // Create model
        MetaModelCreateRequest modelReq = new MetaModelCreateRequest();
        modelReq.setCode(testModelCode);
        modelReq.setDisplayName("Export Test Model");
        modelReq.setDescription("Model for export service testing");
        modelReq.setModelType("entity");
        MetaModelDTO model = metaModelService.create(modelReq);
        assertNotNull(model, "Model should be created");

        // Create and bind 5 fields
        String[] fieldNames = {"title", "status", "amount", "description", "category"};
        String[] dataTypes = {"string", "string", "decimal", "text", "string"};
        for (int i = 0; i < fieldNames.length; i++) {
            String fieldCode = testModelCode + "_" + fieldNames[i];
            MetaFieldCreateRequest fieldReq = new MetaFieldCreateRequest();
            fieldReq.setCode(fieldCode);
            fieldReq.setDataType(dataTypes[i]);
            MetaFieldDTO field = metaFieldService.create(fieldReq);
            assertNotNull(field, "Field should be created: " + fieldCode);

            modelFieldBindingService.bindFieldToModel(
                    model.getPid(), field.getPid(), i + 1, false, false, true);
        }

        // Create commands
        CommandDefinitionCreateRequest createCmd = new CommandDefinitionCreateRequest();
        createCmd.setCode(testModelCode + ":create");
        createCmd.setDisplayName("Create " + testModelCode);
        createCmd.setModelCode(testModelCode);
        createCmd.setExecutionConfig("{\"type\":\"CREATE\"}");
        commandService.create(createCmd);

        CommandDefinitionCreateRequest updateCmd = new CommandDefinitionCreateRequest();
        updateCmd.setCode(testModelCode + ":update");
        updateCmd.setDisplayName("Update " + testModelCode);
        updateCmd.setModelCode(testModelCode);
        updateCmd.setExecutionConfig("{\"type\":\"UPDATE\"}");
        commandService.create(updateCmd);
    }

    @Test
    @Order(1)
    @DisplayName("should export e2et_order model with correct structure")
    void testExportE2etOrderModel() {
        Map<String, Object> result = modelExportService.exportByModelCodes(List.of(testModelCode));

        assertNotNull(result);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> models = (List<Map<String, Object>>) result.get("models");
        assertNotNull(models);
        assertFalse(models.isEmpty(), "models array should not be empty");
        assertEquals(testModelCode, models.get(0).get("code"));
    }

    @Test
    @Order(2)
    @DisplayName("should export at least 5 fields for e2et_order")
    void testExportE2etOrderFields() {
        Map<String, Object> result = modelExportService.exportByModelCodes(List.of(testModelCode));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) result.get("fields");
        assertNotNull(fields);
        assertTrue(fields.size() >= 5,
                "Model should have at least 5 fields, got " + fields.size());
    }

    @Test
    @Order(3)
    @DisplayName("should export create and update commands for e2et_order")
    void testExportE2etOrderCommands() {
        Map<String, Object> result = modelExportService.exportByModelCodes(List.of(testModelCode));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> commands = (List<Map<String, Object>>) result.get("commands");
        assertNotNull(commands);
        assertFalse(commands.isEmpty(), "commands should not be empty");

        List<String> commandCodes = commands.stream()
                .map(c -> (String) c.get("code"))
                .toList();
        assertTrue(commandCodes.contains(testModelCode + ":create"),
                "should contain create command, got: " + commandCodes);
        assertTrue(commandCodes.contains(testModelCode + ":update"),
                "should contain update command, got: " + commandCodes);
    }

    @Test
    @Order(4)
    @DisplayName("should include pages array in export (may be empty)")
    void testExportE2etOrderPages() {
        Map<String, Object> result = modelExportService.exportByModelCodes(List.of(testModelCode));

        assertTrue(result.containsKey("pages"), "export result should contain 'pages' key");

        @SuppressWarnings("unchecked")
        List<?> pages = (List<?>) result.get("pages");
        assertNotNull(pages, "pages array should not be null");
    }
}
