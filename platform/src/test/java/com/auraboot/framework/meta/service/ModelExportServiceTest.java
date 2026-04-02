package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.dto.CommandDefinitionDTO;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.dto.MetaModelFieldBindingDTO;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.service.impl.ModelExportServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit test for ModelExportService
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelExportService Test")
class ModelExportServiceTest {

    @Mock
    private MetaModelService metaModelService;

    @Mock
    private ModelFieldBindingService modelFieldBindingService;

    @Mock
    private CommandService commandService;

    @Mock
    private PageSchemaService pageSchemaService;

    private ModelExportService modelExportService;

    @BeforeEach
    void setUp() {
        modelExportService = new ModelExportServiceImpl(
                metaModelService, modelFieldBindingService, commandService,
                pageSchemaService, new ObjectMapper());
    }

    @Test
    @DisplayName("should export model with non-empty models, fields, commands arrays")
    void testExportExistingModel() {
        // Given
        String modelCode = "e2et_order";
        String modelPid = "pid-001";

        MetaModelDTO modelDTO = MetaModelDTO.builder()
                .pid(modelPid)
                .code(modelCode)
                .displayName("Test Order")
                .description("E2E test order model")
                .modelType("entity")
                .extension(Map.of("titleField", "e2et_order_title"))
                .build();

        MetaFieldDTO fieldDTO = MetaFieldDTO.builder()
                .code("e2et_order_title")
                .dataType("string")
                .extension(Map.of("displayName", "Order Title"))
                .build();

        MetaModelFieldBindingDTO bindingDTO = MetaModelFieldBindingDTO.builder()
                .modelCode(modelCode)
                .code("e2et_order_title")
                .fieldOrder(1)
                .required(true)
                .visible(true)
                .build();

        CommandDefinitionDTO commandDTO = new CommandDefinitionDTO();
        commandDTO.setCode("e2et:create_order");
        commandDTO.setDisplayName("Create Order");
        commandDTO.setModelCode(modelCode);
        commandDTO.setExecutionConfig("{\"type\":\"CREATE\",\"inputFields\":[\"e2et_order_title\"],\"autoSetFields\":{}}");

        PageSchemaDTO pageDTO = new PageSchemaDTO();
        pageDTO.setPageKey("e2et_order_list");
        pageDTO.setModelCode(modelCode);
        pageDTO.setKind("list");
        pageDTO.setBlocks(List.of(Map.of("blockType", "data-table", "columns", List.of())));

        when(metaModelService.findByCode(modelCode)).thenReturn(modelDTO);
        when(modelFieldBindingService.getModelFields(modelPid)).thenReturn(List.of(fieldDTO));
        when(modelFieldBindingService.getModelBindings(modelPid)).thenReturn(List.of(bindingDTO));
        when(commandService.listByModelCode(modelCode)).thenReturn(List.of(commandDTO));
        when(pageSchemaService.findByModelCode(modelCode)).thenReturn(List.of(pageDTO));

        // When
        Map<String, Object> result = modelExportService.exportByModelCodes(List.of(modelCode));

        // Then
        assertNotNull(result);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> models = (List<Map<String, Object>>) result.get("models");
        assertNotNull(models);
        assertFalse(models.isEmpty(), "models array should not be empty");

        Map<String, Object> exportedModel = models.get(0);
        assertEquals(modelCode, exportedModel.get("code"));
        assertEquals("Test Order", exportedModel.get("displayName"));
        assertEquals("E2E test order model", exportedModel.get("description"));
        assertEquals("entity", exportedModel.get("modelType"));
        assertNotNull(exportedModel.get("extension"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) result.get("fields");
        assertNotNull(fields);
        assertFalse(fields.isEmpty(), "fields array should not be empty");

        Map<String, Object> exportedField = fields.get(0);
        assertEquals("e2et_order_title", exportedField.get("code"));
        assertEquals("string", exportedField.get("dataType"));
        assertNotNull(exportedField.get("extension"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bindings = (List<Map<String, Object>>) result.get("bindings");
        assertNotNull(bindings);
        assertFalse(bindings.isEmpty(), "bindings array should not be empty");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> commands = (List<Map<String, Object>>) result.get("commands");
        assertNotNull(commands);
        assertFalse(commands.isEmpty(), "commands array should not be empty");

        Map<String, Object> exportedCommand = commands.get(0);
        assertEquals("e2et:create_order", exportedCommand.get("code"));
        assertEquals("Create Order", exportedCommand.get("displayName"));
        assertEquals("create", exportedCommand.get("type"));
        assertEquals(modelCode, exportedCommand.get("modelCode"));
        assertNotNull(exportedCommand.get("inputFields"));
        assertNotNull(exportedCommand.get("autoSetFields"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pages = (List<Map<String, Object>>) result.get("pages");
        assertNotNull(pages, "pages array should be present");
        assertFalse(pages.isEmpty(), "pages array should not be empty");

        Map<String, Object> exportedPage = pages.get(0);
        assertEquals("e2et_order_list", exportedPage.get("code"));
        assertEquals(modelCode, exportedPage.get("modelCode"));
        assertEquals("list", exportedPage.get("kind"));
        assertNotNull(exportedPage.get("blocks"));
    }

    @Test
    @DisplayName("should return empty arrays for nonexistent model code without error")
    void testExportNonexistentModel() {
        // Given
        String modelCode = "nonexistent_model";
        when(metaModelService.findByCode(modelCode)).thenReturn(null);

        // When
        Map<String, Object> result = modelExportService.exportByModelCodes(List.of(modelCode));

        // Then
        assertNotNull(result);

        @SuppressWarnings("unchecked")
        List<?> models = (List<?>) result.get("models");
        assertNotNull(models);
        assertTrue(models.isEmpty(), "models should be empty for nonexistent model");

        @SuppressWarnings("unchecked")
        List<?> fields = (List<?>) result.get("fields");
        assertNotNull(fields);
        assertTrue(fields.isEmpty(), "fields should be empty for nonexistent model");

        @SuppressWarnings("unchecked")
        List<?> bindings = (List<?>) result.get("bindings");
        assertNotNull(bindings);
        assertTrue(bindings.isEmpty(), "bindings should be empty for nonexistent model");

        @SuppressWarnings("unchecked")
        List<?> commands = (List<?>) result.get("commands");
        assertNotNull(commands);
        assertTrue(commands.isEmpty(), "commands should be empty for nonexistent model");

        @SuppressWarnings("unchecked")
        List<?> pages = (List<?>) result.get("pages");
        assertNotNull(pages, "pages array should be present");
        assertTrue(pages.isEmpty(), "pages should be empty for nonexistent model");

        // Should not attempt to query fields/bindings/commands/pages for nonexistent model
        verify(modelFieldBindingService, never()).getModelFields(any());
        verify(modelFieldBindingService, never()).getModelBindings(any());
        verify(commandService, never()).listByModelCode(any());
        verify(pageSchemaService, never()).findByModelCode(any());
    }
}
