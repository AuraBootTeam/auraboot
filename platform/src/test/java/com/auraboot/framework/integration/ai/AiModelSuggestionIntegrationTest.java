package com.auraboot.framework.integration.ai;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.ai.AiModelSuggestionController;
import com.auraboot.framework.meta.ai.AiModelSuggestionController.SuggestModelRequest;
import com.auraboot.framework.meta.ai.AiModelSuggestionService;
import com.auraboot.framework.meta.ai.AiModelSuggestionService.ModelSuggestion;
import com.auraboot.framework.meta.ai.AiModelSuggestionService.FieldSuggestion;
import com.auraboot.framework.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for AiModelSuggestionController and AiModelSuggestionService.
 *
 * When AI is disabled (default in tests), the service returns null and
 * the controller returns an error response. No fallback data is generated.
 */
@Slf4j
@DisplayName("AiModelSuggestion - Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiModelSuggestionIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AiModelSuggestionController controller;

    @Autowired
    private AiModelSuggestionService service;

    @Value("${ai.service.enabled:false}")
    private boolean aiEnabled;

    // ==================== Service Layer Tests ====================

    @Test
    @Order(1)
    @DisplayName("Service returns null when AI is disabled")
    void test01_serviceReturnsNullWhenAiDisabled() {
        if (aiEnabled) {
            log.info("Skipping test — AI is enabled, service may return real data");
            return;
        }

        ModelSuggestion result = service.suggestModel("Customer management system", "en");
        assertNull(result, "Service should return null when AI is disabled");
    }

    @Test
    @Order(2)
    @DisplayName("Service returns null for various descriptions when AI disabled")
    void test02_serviceReturnsNullForAllDescriptions() {
        if (aiEnabled) {
            log.info("Skipping test — AI is enabled");
            return;
        }

        assertNull(service.suggestModel("Project task tracking", "en"));
        assertNull(service.suggestModel("Employee Leave Management", "en"));
        assertNull(service.suggestModel("客户关系管理系统", "zh"));
        assertNull(service.suggestModel("Simple data", null));
    }

    // ==================== Controller Layer Tests ====================

    @Test
    @Order(3)
    @DisplayName("Controller returns error response when AI is disabled")
    void test03_controllerReturnsErrorWhenAiDisabled() {
        if (aiEnabled) {
            log.info("Skipping test — AI is enabled");
            return;
        }

        SuggestModelRequest request = new SuggestModelRequest();
        request.setDescription("Customer management system");
        request.setLanguage("en");

        ApiResponse<ModelSuggestion> response = controller.suggestModel(request);

        assertNotNull(response);
        assertNull(response.getData(), "Data should be null when AI is unavailable");
        assertNotEquals("0", response.getCode(), "Response code should indicate error");

        log.info("Controller error response: code={}, message={}", response.getCode(), response.getMessage());
    }

    @Test
    @Order(4)
    @DisplayName("Controller error message mentions LLM configuration")
    void test04_controllerErrorMessageIsHelpful() {
        if (aiEnabled) {
            log.info("Skipping test — AI is enabled");
            return;
        }

        SuggestModelRequest request = new SuggestModelRequest();
        request.setDescription("Inventory tracking");

        ApiResponse<ModelSuggestion> response = controller.suggestModel(request);

        assertNotNull(response.getMessage());
        assertTrue(response.getMessage().contains("LLM"),
                "Error message should mention LLM provider: " + response.getMessage());
    }

    @Test
    @Order(5)
    @DisplayName("Controller handles Chinese description gracefully")
    void test05_controllerHandlesChineseDescription() {
        if (aiEnabled) {
            log.info("Skipping test — AI is enabled");
            return;
        }

        SuggestModelRequest request = new SuggestModelRequest();
        request.setDescription("客户关系管理系统");
        request.setLanguage("zh");

        ApiResponse<ModelSuggestion> response = controller.suggestModel(request);

        assertNotNull(response);
        assertNull(response.getData(), "Data should be null when AI is unavailable");
    }

    @Test
    @Order(6)
    @DisplayName("Controller handles null language parameter")
    void test06_controllerHandlesNullLanguage() {
        if (aiEnabled) {
            log.info("Skipping test — AI is enabled");
            return;
        }

        SuggestModelRequest request = new SuggestModelRequest();
        request.setDescription("Inventory tracking");
        request.setLanguage(null);

        ApiResponse<ModelSuggestion> response = controller.suggestModel(request);

        assertNotNull(response, "Response should not be null even when AI is unavailable");
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
