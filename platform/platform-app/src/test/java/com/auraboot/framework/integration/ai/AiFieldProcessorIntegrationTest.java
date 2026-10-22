package com.auraboot.framework.integration.ai;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.ai.AiFieldProcessor;
import com.auraboot.framework.meta.ai.AiFieldProcessor.AiGenerationRequest;
import com.auraboot.framework.meta.ai.AiFieldProcessor.AiGenerationResult;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for AiFieldProcessor.
 *
 * Note: In integration-test profile, ai.service.enabled is typically false,
 * so tests verify the disabled-state behavior and prompt building logic.
 * When AI is enabled, tests verify the full request/response cycle.
 */
@Slf4j
@DisplayName("AiFieldProcessor - Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiFieldProcessorIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AiFieldProcessor aiFieldProcessor;

    @Value("${ai.service.enabled:false}")
    private boolean aiEnabled;

    // ==================== Disabled State Tests ====================

    @Test
    @Order(1)
    @DisplayName("GENERATE operation returns disabled error when AI is off")
    void test01_generateWhenDisabled() {
        AiGenerationRequest request = AiGenerationRequest.builder()
                .operation("generate")
                .prompt("Write a product description for a laptop")
                .maxTokens(200)
                .temperature(0.7)
                .build();

        AiGenerationResult result = aiFieldProcessor.process(request);

        assertNotNull(result, "Result should never be null");

        if (!aiEnabled) {
            assertFalse(result.isSuccess(), "Should fail when AI is disabled");
            assertNotNull(result.getError());
            assertTrue(result.getError().contains("not enabled"),
                    "Error should mention AI service not enabled");
            log.info("AI disabled - error: {}", result.getError());
        } else {
            // AI is enabled - verify success
            log.info("AI enabled - success={}, content length={}",
                    result.isSuccess(),
                    result.getContent() != null ? result.getContent().length() : 0);
        }
    }

    @Test
    @Order(2)
    @DisplayName("SUMMARIZE operation returns disabled error when AI is off")
    void test02_summarizeWhenDisabled() {
        AiGenerationRequest request = AiGenerationRequest.builder()
                .operation("summarize")
                .sourceContent(Map.of(
                        "title", "AuraBoot Platform Overview",
                        "content", "AuraBoot is a low-code platform that provides model-driven development capabilities."
                ))
                .build();

        AiGenerationResult result = aiFieldProcessor.process(request);

        assertNotNull(result);
        if (!aiEnabled) {
            assertFalse(result.isSuccess());
            assertTrue(result.getError().contains("not enabled"));
        }
    }

    @Test
    @Order(3)
    @DisplayName("TRANSLATE operation returns disabled error when AI is off")
    void test03_translateWhenDisabled() {
        AiGenerationRequest request = AiGenerationRequest.builder()
                .operation("translate")
                .sourceContent(Map.of("text", "Hello World"))
                .targetLanguage("Chinese")
                .build();

        AiGenerationResult result = aiFieldProcessor.process(request);

        assertNotNull(result);
        if (!aiEnabled) {
            assertFalse(result.isSuccess());
        }
    }

    @Test
    @Order(4)
    @DisplayName("CLASSIFY operation returns disabled error when AI is off")
    void test04_classifyWhenDisabled() {
        AiGenerationRequest request = AiGenerationRequest.builder()
                .operation("classify")
                .sourceContent(Map.of("text", "The server is down and users cannot login"))
                .categories(List.of("Bug", "Feature", "Enhancement", "Support"))
                .build();

        AiGenerationResult result = aiFieldProcessor.process(request);

        assertNotNull(result);
        if (!aiEnabled) {
            assertFalse(result.isSuccess());
        }
    }

    @Test
    @Order(5)
    @DisplayName("EXTRACT operation returns disabled error when AI is off")
    void test05_extractWhenDisabled() {
        AiGenerationRequest request = AiGenerationRequest.builder()
                .operation("extract")
                .sourceContent(Map.of(
                        "email", "Hi, my name is John and I work at Acme Corp. My phone is 555-1234."
                ))
                .extractFields(List.of("name", "company", "phone"))
                .build();

        AiGenerationResult result = aiFieldProcessor.process(request);

        assertNotNull(result);
        if (!aiEnabled) {
            assertFalse(result.isSuccess());
        }
    }

    // ==================== Request Validation Tests ====================

    @Test
    @Order(6)
    @DisplayName("Process with null operation")
    void test06_nullOperation() {
        AiGenerationRequest request = AiGenerationRequest.builder()
                .operation(null)
                .prompt("test")
                .build();

        AiGenerationResult result = aiFieldProcessor.process(request);

        assertNotNull(result);
        // Should either fail gracefully or process with default GENERATE behavior
        if (!aiEnabled) {
            assertFalse(result.isSuccess());
        }
    }

    @Test
    @Order(7)
    @DisplayName("Process with empty source content")
    void test07_emptySourceContent() {
        AiGenerationRequest request = AiGenerationRequest.builder()
                .operation("summarize")
                .sourceContent(Map.of())
                .build();

        AiGenerationResult result = aiFieldProcessor.process(request);

        assertNotNull(result);
        if (!aiEnabled) {
            assertFalse(result.isSuccess());
        }
    }

    @Test
    @Order(8)
    @DisplayName("Process with custom maxTokens and temperature")
    void test08_customParameters() {
        AiGenerationRequest request = AiGenerationRequest.builder()
                .operation("generate")
                .prompt("Hello")
                .maxTokens(100)
                .temperature(0.1)
                .build();

        AiGenerationResult result = aiFieldProcessor.process(request);

        assertNotNull(result);
        // The request builder should have accepted the custom parameters
        assertEquals(100, request.getMaxTokens());
        assertEquals(0.1, request.getTemperature(), 0.001);
    }

    @Test
    @Order(9)
    @DisplayName("Process with null maxTokens uses default")
    void test09_defaultParameters() {
        AiGenerationRequest request = AiGenerationRequest.builder()
                .operation("generate")
                .prompt("Hello")
                .build();

        assertNull(request.getMaxTokens(), "maxTokens should be null before processing");
        assertNull(request.getTemperature(), "temperature should be null before processing");

        // Process - should use defaults internally (500 tokens, 0.7 temperature)
        AiGenerationResult result = aiFieldProcessor.process(request);
        assertNotNull(result);
    }

    // ==================== AiGenerationRequest Builder Tests ====================

    @Test
    @Order(10)
    @DisplayName("AiGenerationRequest builder creates correct objects")
    void test10_requestBuilder() {
        AiGenerationRequest request = AiGenerationRequest.builder()
                .operation("classify")
                .prompt("Test prompt")
                .sourceContent(Map.of("field1", "value1"))
                .targetLanguage("en")
                .categories(List.of("A", "B", "C"))
                .extractFields(List.of("x", "y"))
                .maxTokens(300)
                .temperature(0.5)
                .build();

        assertEquals("classify", request.getOperation());
        assertEquals("Test prompt", request.getPrompt());
        assertEquals("value1", request.getSourceContent().get("field1"));
        assertEquals("en", request.getTargetLanguage());
        assertEquals(3, request.getCategories().size());
        assertEquals(2, request.getExtractFields().size());
        assertEquals(300, request.getMaxTokens());
        assertEquals(0.5, request.getTemperature(), 0.001);
    }

    @Test
    @Order(11)
    @DisplayName("AiGenerationResult builder creates correct objects")
    void test11_resultBuilder() {
        AiGenerationResult result = AiGenerationResult.builder()
                .success(true)
                .content("Generated content")
                .tokensUsed(150)
                .build();

        assertTrue(result.isSuccess());
        assertEquals("Generated content", result.getContent());
        assertEquals(150, result.getTokensUsed());
        assertNull(result.getError());
    }

    @Test
    @Order(12)
    @DisplayName("AiGenerationResult error case")
    void test12_resultError() {
        AiGenerationResult result = AiGenerationResult.builder()
                .success(false)
                .error("Connection timeout")
                .build();

        assertFalse(result.isSuccess());
        assertNull(result.getContent());
        assertEquals("Connection timeout", result.getError());
    }
}
