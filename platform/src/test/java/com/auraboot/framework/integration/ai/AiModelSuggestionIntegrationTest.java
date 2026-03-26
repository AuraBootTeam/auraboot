package com.auraboot.framework.integration.ai;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.ai.AiModelSuggestionController;
import com.auraboot.framework.meta.ai.AiModelSuggestionController.SuggestModelRequest;
import com.auraboot.framework.meta.ai.AiModelSuggestionService.ModelSuggestion;
import com.auraboot.framework.meta.ai.AiModelSuggestionService.FieldSuggestion;
import com.auraboot.framework.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for AiModelSuggestionController.
 * Tests the suggest-model endpoint and fallback behavior.
 *
 * When AI is disabled, the controller returns a fallback suggestion.
 * When AI is enabled, it parses the AI response into ModelSuggestion.
 */
@Slf4j
@DisplayName("AiModelSuggestion - Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiModelSuggestionIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AiModelSuggestionController controller;

    @Value("${ai.service.enabled:false}")
    private boolean aiEnabled;

    // ==================== Fallback Suggestion Tests ====================

    @Test
    @Order(1)
    @DisplayName("Suggest model returns fallback when AI is disabled")
    void test01_suggestModelFallback() {
        SuggestModelRequest request = new SuggestModelRequest();
        request.setDescription("Customer management system");
        request.setLanguage("en");

        ApiResponse<ModelSuggestion> response = controller.suggestModel(request);

        assertNotNull(response);
        assertNotNull(response.getData(), "Should return a suggestion even when AI is disabled");

        ModelSuggestion suggestion = response.getData();
        assertNotNull(suggestion.getModelCode(), "Model code should not be null");
        assertNotNull(suggestion.getModelName(), "Model name should not be null");
        assertNotNull(suggestion.getFields(), "Fields should not be null");
        assertFalse(suggestion.getFields().isEmpty(), "Should have at least one field");
        assertNotNull(suggestion.getSuggestedViews(), "Views should not be null");

        log.info("Fallback suggestion: code={}, name={}, fields={}, views={}",
                suggestion.getModelCode(),
                suggestion.getModelName(),
                suggestion.getFields().size(),
                suggestion.getSuggestedViews());
    }

    @Test
    @Order(2)
    @DisplayName("Fallback suggestion contains standard fields")
    void test02_fallbackStandardFields() {
        SuggestModelRequest request = new SuggestModelRequest();
        request.setDescription("Project task tracking");

        ApiResponse<ModelSuggestion> response = controller.suggestModel(request);

        ModelSuggestion suggestion = response.getData();
        assertNotNull(suggestion);

        // Fallback always generates 4 standard fields
        if (!aiEnabled) {
            assertEquals(4, suggestion.getFields().size(),
                    "Fallback should have exactly 4 standard fields");

            // Verify standard field codes
            assertTrue(suggestion.getFields().stream()
                            .anyMatch(f -> "name".equals(f.getFieldCode())),
                    "Should have 'name' field");
            assertTrue(suggestion.getFields().stream()
                            .anyMatch(f -> "status".equals(f.getFieldCode())),
                    "Should have 'status' field");
            assertTrue(suggestion.getFields().stream()
                            .anyMatch(f -> "description".equals(f.getFieldCode())),
                    "Should have 'description' field");
            assertTrue(suggestion.getFields().stream()
                            .anyMatch(f -> "created_date".equals(f.getFieldCode())),
                    "Should have 'created_date' field");
        }
    }

    @Test
    @Order(3)
    @DisplayName("Fallback model code is derived from description")
    void test03_fallbackModelCodeDerivation() {
        SuggestModelRequest request = new SuggestModelRequest();
        request.setDescription("Employee Leave Management");

        ApiResponse<ModelSuggestion> response = controller.suggestModel(request);

        ModelSuggestion suggestion = response.getData();
        assertNotNull(suggestion);

        if (!aiEnabled) {
            // Model code should be lowercase, derived from description
            String code = suggestion.getModelCode();
            assertNotNull(code);
            assertEquals(code, code.toLowerCase(),
                    "Model code should be lowercase");
            assertFalse(code.contains(" "),
                    "Model code should not contain spaces");
            log.info("Derived model code: {}", code);
        }
    }

    @Test
    @Order(4)
    @DisplayName("Fallback handles long description truncation")
    void test04_fallbackLongDescription() {
        String longDesc = "A very detailed and comprehensive enterprise resource planning system that handles " +
                "inventory management, order processing, supplier management, warehouse logistics, " +
                "financial reporting, and human resources administration for multinational corporations";

        SuggestModelRequest request = new SuggestModelRequest();
        request.setDescription(longDesc);

        ApiResponse<ModelSuggestion> response = controller.suggestModel(request);

        ModelSuggestion suggestion = response.getData();
        assertNotNull(suggestion);

        if (!aiEnabled) {
            // Model code should be truncated to max 30 chars
            assertTrue(suggestion.getModelCode().length() <= 30,
                    "Model code should be max 30 chars");
            // Model name should be truncated to max 50 chars
            assertTrue(suggestion.getModelName().length() <= 50,
                    "Model name should be max 50 chars");
        }
    }

    @Test
    @Order(5)
    @DisplayName("Suggest model with Chinese description")
    void test05_chineseDescription() {
        SuggestModelRequest request = new SuggestModelRequest();
        request.setDescription("客户关系管理系统");
        request.setLanguage("zh");

        ApiResponse<ModelSuggestion> response = controller.suggestModel(request);

        ModelSuggestion suggestion = response.getData();
        assertNotNull(suggestion);
        assertNotNull(suggestion.getModelCode());
        assertNotNull(suggestion.getFields());
        assertFalse(suggestion.getFields().isEmpty());

        log.info("Chinese description suggestion: code={}, fields={}",
                suggestion.getModelCode(), suggestion.getFields().size());
    }

    @Test
    @Order(6)
    @DisplayName("Suggest model with null language defaults to zh")
    void test06_nullLanguageDefault() {
        SuggestModelRequest request = new SuggestModelRequest();
        request.setDescription("Inventory tracking");
        request.setLanguage(null);

        ApiResponse<ModelSuggestion> response = controller.suggestModel(request);

        assertNotNull(response);
        assertNotNull(response.getData());
        log.info("Null language suggestion: {}", response.getData().getModelCode());
    }

    // ==================== FieldSuggestion Tests ====================

    @Test
    @Order(7)
    @DisplayName("FieldSuggestion has correct data types")
    void test07_fieldSuggestionDataTypes() {
        SuggestModelRequest request = new SuggestModelRequest();
        request.setDescription("Test model");

        ApiResponse<ModelSuggestion> response = controller.suggestModel(request);

        ModelSuggestion suggestion = response.getData();
        assertNotNull(suggestion);

        if (!aiEnabled) {
            for (FieldSuggestion field : suggestion.getFields()) {
                assertNotNull(field.getFieldCode(), "Field code should not be null");
                assertNotNull(field.getFieldName(), "Field name should not be null");
                assertNotNull(field.getDataType(), "Data type should not be null");
                assertNotNull(field.getDescription(), "Description should not be null");

                // Validate data types are recognized types
                String dataType = field.getDataType();
                assertTrue(
                        dataType.equals("string") || dataType.equals("integer") ||
                                dataType.equals("decimal") || dataType.equals("date") ||
                                dataType.equals("datetime") || dataType.equals("boolean") ||
                                dataType.equals("text") || dataType.equals("enum"),
                        "Data type should be a valid type: " + dataType);
            }
        }
    }

    @Test
    @Order(8)
    @DisplayName("Fallback suggested views contain TABLE")
    void test08_fallbackSuggestedViews() {
        SuggestModelRequest request = new SuggestModelRequest();
        request.setDescription("Simple data");

        ApiResponse<ModelSuggestion> response = controller.suggestModel(request);

        ModelSuggestion suggestion = response.getData();
        assertNotNull(suggestion);

        if (!aiEnabled) {
            assertTrue(suggestion.getSuggestedViews().contains("table"),
                    "Fallback should always suggest TABLE view");
        }
    }

    // ==================== DTO Tests ====================

    @Test
    @Order(9)
    @DisplayName("SuggestModelRequest getter/setter")
    void test09_requestDto() {
        SuggestModelRequest request = new SuggestModelRequest();
        request.setDescription("Test description");
        request.setLanguage("en");

        assertEquals("Test description", request.getDescription());
        assertEquals("en", request.getLanguage());
    }

    @Test
    @Order(10)
    @DisplayName("ModelSuggestion getter/setter")
    void test10_suggestionDto() {
        ModelSuggestion suggestion = new ModelSuggestion();
        suggestion.setModelCode("test_model");
        suggestion.setModelName("Test Model");
        suggestion.setDescription("A test model");
        suggestion.setFields(java.util.List.of(
                new FieldSuggestion("name", "Name", "string", true, "Record name")
        ));
        suggestion.setSuggestedViews(java.util.List.of("table", "kanban"));

        assertEquals("test_model", suggestion.getModelCode());
        assertEquals("Test Model", suggestion.getModelName());
        assertEquals("A test model", suggestion.getDescription());
        assertEquals(1, suggestion.getFields().size());
        assertEquals(2, suggestion.getSuggestedViews().size());
    }

    @Test
    @Order(11)
    @DisplayName("FieldSuggestion all-args constructor")
    void test11_fieldSuggestionConstructor() {
        FieldSuggestion field = new FieldSuggestion(
                "email", "Email Address", "string", true, "Contact email");

        assertEquals("email", field.getFieldCode());
        assertEquals("Email Address", field.getFieldName());
        assertEquals("string", field.getDataType());
        assertTrue(field.isRequired());
        assertEquals("Contact email", field.getDescription());
    }
}
