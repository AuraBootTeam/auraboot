package com.auraboot.framework.integration.agent.nlmodeling;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.nlmodeling.NlModelingService;
import com.auraboot.framework.agent.nlmodeling.dto.*;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration test for NlModelingService.
 * LLM providers are mocked to avoid real API calls.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NlModelingServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private NlModelingService nlModelingService;

    @MockitoBean
    private LlmProviderFactory providerFactory;

    // ======================================================================
    // Helpers
    // ======================================================================

    private void mockLlmResponse(String responseJson) throws Exception {
        LlmProvider mockProvider = mock(LlmProvider.class);
        LlmChatResponse mockResponse = LlmChatResponse.builder()
                .stopReason("end_turn")
                .content(List.of(LlmChatResponse.ContentBlock.builder()
                        .type("text")
                        .text(responseJson)
                        .build()))
                .inputTokens(500)
                .outputTokens(1000)
                .build();

        when(mockProvider.chat(any(LlmChatRequest.class), anyString(), anyString()))
                .thenReturn(mockResponse);

        LlmProviderFactory.ProviderConfig config = LlmProviderFactory.ProviderConfig.builder()
                .providerCode("openai")
                .apiKey("test-key")
                .baseUrl("https://api.openai.com")
                .defaultModel("gpt-4o")
                .maxTokens(8192)
                .build();

        when(providerFactory.resolveConfig(any(), anyString())).thenReturn(config);
        when(providerFactory.getProvider(anyString())).thenReturn(mockProvider);
        when(providerFactory.listConfiguredProviders(any())).thenReturn(List.of(
                LlmProviderFactory.ProviderInfo.builder()
                        .providerCode("openai").displayName("OpenAI")
                        .apiFormat("chat_completions").configured(true)
                        .build()
        ));
    }

    // ======================================================================
    // Generate tests
    // ======================================================================

    @Test
    @Order(1)
    void generate_withValidDescription_returnsResources() throws Exception {
        // Use the few-shot example as mock LLM response
        mockLlmResponse(NlModelingService.FEW_SHOT_EXAMPLE);

        NlModelingRequest request = NlModelingRequest.builder()
                .description("I need a book management module with title, author, ISBN, price, and published date")
                .options(NlModelingRequest.Options.builder().build())
                .build();

        NlModelingResponse response = nlModelingService.generate(request);

        assertThat(response).isNotNull();
        assertThat(response.getPluginCode()).isEqualTo("book_mgmt");
        assertThat(response.getSummary()).isNotBlank();
        assertThat(response.getResources()).isNotNull();
        assertThat(response.getResources().getModels()).hasSize(1);
        assertThat(response.getResources().getFields()).hasSize(5);
        assertThat(response.getResources().getBindings()).hasSize(5);
        assertThat(response.getResources().getCommands()).hasSize(3);
        assertThat(response.getResources().getPages()).hasSize(2);
        assertThat(response.getResources().getMenus()).hasSize(2);
        assertThat(response.getResources().getI18n()).hasSize(6);
        assertThat(response.getValidationErrors()).isNull();
        assertThat(response.getTokenUsage()).isNotNull();
        assertThat(response.getTokenUsage().getInputTokens()).isEqualTo(500);
    }

    @Test
    @Order(2)
    void generate_withEmptyDescription_returnsValidationError() {
        NlModelingRequest request = NlModelingRequest.builder()
                .description("")
                .build();

        NlModelingResponse response = nlModelingService.generate(request);

        assertThat(response).isNotNull();
        assertThat(response.getValidationErrors()).isNotNull();
        assertThat(response.getValidationErrors()).contains("description is required");
    }

    @Test
    @Order(3)
    void generate_withNullDescription_returnsValidationError() {
        NlModelingRequest request = NlModelingRequest.builder().build();

        NlModelingResponse response = nlModelingService.generate(request);

        assertThat(response.getValidationErrors()).contains("description is required");
    }

    @Test
    @Order(4)
    void generate_withNoProviderConfigured_returnsError() {
        when(providerFactory.resolveConfig(any(), anyString())).thenReturn(null);
        when(providerFactory.listConfiguredProviders(any())).thenReturn(List.of());

        NlModelingRequest request = NlModelingRequest.builder()
                .description("I need a simple task module")
                .options(NlModelingRequest.Options.builder().build())
                .build();

        NlModelingResponse response = nlModelingService.generate(request);

        assertThat(response.getValidationErrors()).isNotNull();
        assertThat(response.getValidationErrors().get(0)).contains("LLM call failed");
    }

    // ======================================================================
    // Parsing tests
    // ======================================================================

    @Test
    @Order(10)
    void parseGeneratedDsl_validJson_parsesCorrectly() {
        String json = """
                {
                  "pluginCode": "test_mod",
                  "summary": "Test module",
                  "resources": {
                    "models": [{"code": "item", "modelType": "entity"}],
                    "fields": [{"code": "name", "dataType": "string"}],
                    "bindings": [{"modelCode": "item", "fieldCode": "name", "sequence": 1}],
                    "commands": [],
                    "pages": [],
                    "menus": [],
                    "i18n": [],
                    "permissions": []
                  }
                }
                """;

        NlModelingResponse response = nlModelingService.parseGeneratedDsl(json);

        assertThat(response.getPluginCode()).isEqualTo("test_mod");
        assertThat(response.getResources().getModels()).hasSize(1);
        assertThat(response.getResources().getFields()).hasSize(1);
        assertThat(response.getResources().getBindings()).hasSize(1);
        assertThat(response.getValidationErrors()).isNull();
    }

    @Test
    @Order(11)
    void parseGeneratedDsl_withMarkdownFences_stripsAndParses() {
        String markdown = """
                ```json
                {
                  "pluginCode": "fenced",
                  "summary": "Fenced test",
                  "resources": {
                    "models": [{"code": "task", "modelType": "entity"}],
                    "fields": [{"code": "title", "dataType": "string"}],
                    "bindings": [{"modelCode": "task", "fieldCode": "title", "sequence": 1}],
                    "commands": [], "pages": [], "menus": [], "i18n": [], "permissions": []
                  }
                }
                ```
                """;

        NlModelingResponse response = nlModelingService.parseGeneratedDsl(markdown);

        assertThat(response.getPluginCode()).isEqualTo("fenced");
        assertThat(response.getResources().getModels()).hasSize(1);
    }

    @Test
    @Order(12)
    void parseGeneratedDsl_invalidJson_returnsError() {
        NlModelingResponse response = nlModelingService.parseGeneratedDsl("not json at all");

        assertThat(response.getValidationErrors()).isNotNull();
        assertThat(response.getValidationErrors().get(0)).contains("Failed to parse");
    }

    @Test
    @Order(13)
    void parseGeneratedDsl_missingModels_returnsValidationError() {
        String json = """
                {
                  "pluginCode": "empty",
                  "summary": "Empty module",
                  "resources": {
                    "models": [],
                    "fields": [],
                    "bindings": [],
                    "commands": [], "pages": [], "menus": [], "i18n": [], "permissions": []
                  }
                }
                """;

        NlModelingResponse response = nlModelingService.parseGeneratedDsl(json);

        assertThat(response.getValidationErrors()).isNotNull();
        assertThat(response.getValidationErrors()).anyMatch(e -> e.contains("No models"));
    }

    @Test
    @Order(14)
    void parseGeneratedDsl_bindingReferencesUnknownModel_returnsError() {
        String json = """
                {
                  "pluginCode": "bad_ref",
                  "summary": "Bad reference",
                  "resources": {
                    "models": [{"code": "product", "modelType": "entity"}],
                    "fields": [{"code": "name", "dataType": "string"}],
                    "bindings": [{"modelCode": "nonexistent", "fieldCode": "name", "sequence": 1}],
                    "commands": [], "pages": [], "menus": [], "i18n": [], "permissions": []
                  }
                }
                """;

        NlModelingResponse response = nlModelingService.parseGeneratedDsl(json);

        assertThat(response.getValidationErrors()).isNotNull();
        assertThat(response.getValidationErrors()).anyMatch(e -> e.contains("unknown model 'nonexistent'"));
    }

    @Test
    @Order(15)
    void parseGeneratedDsl_fieldMissingCode_returnsError() {
        String json = """
                {
                  "pluginCode": "no_code",
                  "summary": "Missing code",
                  "resources": {
                    "models": [{"code": "item", "modelType": "entity"}],
                    "fields": [{"dataType": "string"}],
                    "bindings": [],
                    "commands": [], "pages": [], "menus": [], "i18n": [], "permissions": []
                  }
                }
                """;

        NlModelingResponse response = nlModelingService.parseGeneratedDsl(json);

        assertThat(response.getValidationErrors()).isNotNull();
        assertThat(response.getValidationErrors()).anyMatch(e -> e.contains("missing 'code'"));
    }

    // ======================================================================
    // System prompt tests
    // ======================================================================

    @Test
    @Order(20)
    void buildSystemPrompt_containsDslSchemaReference() {
        String prompt = NlModelingService.buildSystemPrompt(
                NlModelingRequest.Options.builder().build());

        assertThat(prompt).contains("AuraBoot DSL architect");
        assertThat(prompt).contains("Model Definition");
        assertThat(prompt).contains("Field Definition");
        assertThat(prompt).contains("Command Definition");
        assertThat(prompt).contains("Page Schema");
        assertThat(prompt).contains("Menu Definition");
        assertThat(prompt).contains("i18n Definition");
        assertThat(prompt).contains("snake_case");
        assertThat(prompt).contains("book_mgmt"); // few-shot example
    }

    // ======================================================================
    // Refine tests
    // ======================================================================

    @Test
    @Order(30)
    void refine_withEmptyInstruction_returnsValidationError() {
        NlRefineRequest request = NlRefineRequest.builder()
                .instruction("")
                .build();

        NlModelingResponse response = nlModelingService.refine(request);

        assertThat(response.getValidationErrors()).contains("instruction is required");
    }

    @Test
    @Order(31)
    void refine_withValidInstruction_callsLlm() throws Exception {
        String refinedJson = """
                {
                  "pluginCode": "book_mgmt",
                  "summary": "Added category field",
                  "resources": {
                    "models": [{"code": "book", "modelType": "entity"}],
                    "fields": [
                      {"code": "title", "dataType": "string"},
                      {"code": "category", "dataType": "enum"}
                    ],
                    "bindings": [
                      {"modelCode": "book", "fieldCode": "title", "sequence": 1},
                      {"modelCode": "book", "fieldCode": "category", "sequence": 2}
                    ],
                    "commands": [], "pages": [], "menus": [], "i18n": [], "permissions": []
                  }
                }
                """;
        mockLlmResponse(refinedJson);

        NlRefineRequest request = NlRefineRequest.builder()
                .instruction("Add a category field to the book model")
                .currentResources(NlModelingResponse.Resources.builder()
                        .models(List.of(Map.of("code", "book", "modelType", "entity")))
                        .fields(List.of(Map.of("code", "title", "dataType", "string")))
                        .bindings(List.of(Map.of("modelCode", "book", "fieldCode", "title", "sequence", 1)))
                        .commands(List.of())
                        .pages(List.of())
                        .menus(List.of())
                        .i18n(List.of())
                        .permissions(List.of())
                        .build())
                .build();

        NlModelingResponse response = nlModelingService.refine(request);

        assertThat(response).isNotNull();
        assertThat(response.getResources().getFields()).hasSize(2);
        assertThat(response.getResources().getFields().get(1).get("code")).isEqualTo("category");
    }

    // ======================================================================
    // Apply tests
    // ======================================================================

    @Test
    @Order(40)
    void apply_withNullPluginCode_returnsError() {
        NlApplyRequest request = NlApplyRequest.builder()
                .resources(NlModelingResponse.Resources.builder().build())
                .build();

        var result = nlModelingService.apply(request);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("pluginCode is required");
    }

    @Test
    @Order(41)
    void apply_withNullResources_returnsError() {
        NlApplyRequest request = NlApplyRequest.builder()
                .pluginCode("test")
                .build();

        var result = nlModelingService.apply(request);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("resources are required");
    }

    @Test
    @Order(42)
    void apply_withBlankPluginCode_returnsError() {
        NlApplyRequest request = NlApplyRequest.builder()
                .pluginCode("   ")
                .resources(NlModelingResponse.Resources.builder().build())
                .build();

        var result = nlModelingService.apply(request);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("pluginCode is required");
    }

    // ======================================================================
    // Session ID tracking tests
    // ======================================================================

    @Test
    @Order(50)
    void generate_returnsSessionId() throws Exception {
        mockLlmResponse(NlModelingService.FEW_SHOT_EXAMPLE);

        NlModelingRequest request = NlModelingRequest.builder()
                .description("A simple inventory module")
                .options(NlModelingRequest.Options.builder().build())
                .build();

        NlModelingResponse response = nlModelingService.generate(request);

        assertThat(response.getSessionId()).isNotNull();
        assertThat(response.getSessionId()).isNotBlank();
    }

    @Test
    @Order(51)
    void refine_returnsSessionId() throws Exception {
        String refinedJson = """
                {
                  "pluginCode": "inv",
                  "summary": "Refined inventory",
                  "resources": {
                    "models": [{"code": "inventory", "modelType": "entity"}],
                    "fields": [{"code": "sku", "dataType": "string"}],
                    "bindings": [{"modelCode": "inventory", "fieldCode": "sku", "sequence": 1}],
                    "commands": [], "pages": [], "menus": [], "i18n": [], "permissions": []
                  }
                }
                """;
        mockLlmResponse(refinedJson);

        NlRefineRequest request = NlRefineRequest.builder()
                .instruction("Add SKU field")
                .currentResources(NlModelingResponse.Resources.builder()
                        .models(List.of(Map.of("code", "inventory")))
                        .fields(List.of())
                        .bindings(List.of())
                        .commands(List.of())
                        .pages(List.of())
                        .menus(List.of())
                        .i18n(List.of())
                        .permissions(List.of())
                        .build())
                .build();

        NlModelingResponse response = nlModelingService.refine(request);

        assertThat(response.getSessionId()).isNotNull();
        assertThat(response.getSessionId()).isNotBlank();
    }

    // ======================================================================
    // Generate-then-refine flow test
    // ======================================================================

    @Test
    @Order(60)
    void generateThenRefine_sessionContinuity() throws Exception {
        // Step 1: Generate
        mockLlmResponse(NlModelingService.FEW_SHOT_EXAMPLE);

        NlModelingRequest genRequest = NlModelingRequest.builder()
                .description("Book management module")
                .options(NlModelingRequest.Options.builder().build())
                .build();

        NlModelingResponse genResponse = nlModelingService.generate(genRequest);
        assertThat(genResponse.getSessionId()).isNotNull();
        String sessionId = genResponse.getSessionId();

        // Step 2: Refine using the session ID from generate
        String refinedJson = """
                {
                  "pluginCode": "book_mgmt",
                  "summary": "Added genre field",
                  "resources": {
                    "models": [{"code": "book", "modelType": "entity"}],
                    "fields": [
                      {"code": "title", "dataType": "string"},
                      {"code": "genre", "dataType": "enum"}
                    ],
                    "bindings": [
                      {"modelCode": "book", "fieldCode": "title", "sequence": 1},
                      {"modelCode": "book", "fieldCode": "genre", "sequence": 2}
                    ],
                    "commands": [], "pages": [], "menus": [], "i18n": [], "permissions": []
                  }
                }
                """;
        mockLlmResponse(refinedJson);

        NlRefineRequest refineRequest = NlRefineRequest.builder()
                .sessionId(sessionId)
                .instruction("Add a genre field for book categorization")
                .build();

        NlModelingResponse refineResponse = nlModelingService.refine(refineRequest);

        assertThat(refineResponse).isNotNull();
        assertThat(refineResponse.getResources().getFields()).hasSize(2);
        assertThat(refineResponse.getResources().getFields())
                .extracting(f -> f.get("code"))
                .contains("genre");
        // Session ID is preserved (or a new one is assigned)
        assertThat(refineResponse.getSessionId()).isNotNull();
    }

    // ======================================================================
    // DSL schema structure validation
    // ======================================================================

    @Test
    @Order(70)
    void generate_dslSchemaStructure_modelsHaveRequiredFields() throws Exception {
        mockLlmResponse(NlModelingService.FEW_SHOT_EXAMPLE);

        NlModelingRequest request = NlModelingRequest.builder()
                .description("Book management")
                .options(NlModelingRequest.Options.builder().build())
                .build();

        NlModelingResponse response = nlModelingService.generate(request);

        // Verify model structure
        Map<String, Object> model = response.getResources().getModels().get(0);
        assertThat(model.get("code")).isEqualTo("book");
        assertThat(model.get("modelType")).isEqualTo("entity");
        assertThat(model.get("modelCategory")).isEqualTo("master");
        assertThat(model).containsKey("extension");

        // Verify fields have required attributes
        for (Map<String, Object> field : response.getResources().getFields()) {
            assertThat(field).containsKey("code");
            assertThat(field).containsKey("dataType");
        }

        // Verify commands follow naming convention
        for (Map<String, Object> cmd : response.getResources().getCommands()) {
            assertThat(cmd).containsKey("code");
            assertThat(cmd).containsKey("type");
            assertThat(cmd).containsKey("modelCode");
            String code = (String) cmd.get("code");
            assertThat(code).contains(":"); // namespace:action format
        }

        // Verify pages have dslSchema
        for (Map<String, Object> page : response.getResources().getPages()) {
            assertThat(page).containsKey("pageKey");
            assertThat(page).containsKey("kind");
            assertThat(page).containsKey("blocks");
        }

        // Verify bindings match models and fields
        for (Map<String, Object> binding : response.getResources().getBindings()) {
            assertThat(binding).containsKey("modelCode");
            assertThat(binding).containsKey("fieldCode");
            assertThat(binding).containsKey("sequence");
        }
    }

    @Test
    @Order(71)
    void generate_dslSchemaStructure_menusHaveCorrectTypes() throws Exception {
        mockLlmResponse(NlModelingService.FEW_SHOT_EXAMPLE);

        NlModelingRequest request = NlModelingRequest.builder()
                .description("Book management")
                .options(NlModelingRequest.Options.builder().build())
                .build();

        NlModelingResponse response = nlModelingService.generate(request);

        List<Map<String, Object>> menus = response.getResources().getMenus();
        assertThat(menus).hasSizeGreaterThanOrEqualTo(2);

        // First menu should be parent (type=0)
        Map<String, Object> parentMenu = menus.get(0);
        assertThat(parentMenu.get("type")).isEqualTo(0);
        assertThat(parentMenu.get("parentCode")).isNull();

        // Second menu should be child (type=1) with parentCode
        Map<String, Object> childMenu = menus.get(1);
        assertThat(childMenu.get("type")).isEqualTo(1);
        assertThat(childMenu.get("parentCode")).isEqualTo(parentMenu.get("code"));
        assertThat(childMenu.get("path")).isNotNull();
    }

    // ======================================================================
    // Edge cases
    // ======================================================================

    @Test
    @Order(80)
    void parseGeneratedDsl_nullText_returnsError() {
        NlModelingResponse response = nlModelingService.parseGeneratedDsl(null);

        assertThat(response.getValidationErrors()).isNotNull();
        assertThat(response.getValidationErrors().get(0)).contains("Empty LLM response");
    }

    @Test
    @Order(81)
    void parseGeneratedDsl_blankText_returnsError() {
        NlModelingResponse response = nlModelingService.parseGeneratedDsl("   ");

        assertThat(response.getValidationErrors()).isNotNull();
        assertThat(response.getValidationErrors().get(0)).contains("Empty LLM response");
    }

    @Test
    @Order(82)
    void parseGeneratedDsl_missingResourcesKey_returnsError() {
        String json = """
                {
                  "pluginCode": "no_resources",
                  "summary": "Missing resources key"
                }
                """;

        NlModelingResponse response = nlModelingService.parseGeneratedDsl(json);

        assertThat(response.getValidationErrors()).isNotNull();
        assertThat(response.getValidationErrors()).anyMatch(e -> e.contains("missing 'resources' key"));
    }

    @Test
    @Order(83)
    void parseGeneratedDsl_fieldMissingDataType_returnsError() {
        String json = """
                {
                  "pluginCode": "no_dt",
                  "summary": "Missing dataType",
                  "resources": {
                    "models": [{"code": "product", "modelType": "entity"}],
                    "fields": [{"code": "name"}],
                    "bindings": [],
                    "commands": [], "pages": [], "menus": [], "i18n": [], "permissions": []
                  }
                }
                """;

        NlModelingResponse response = nlModelingService.parseGeneratedDsl(json);

        assertThat(response.getValidationErrors()).isNotNull();
        assertThat(response.getValidationErrors()).anyMatch(e -> e.contains("missing 'dataType'"));
    }

    @Test
    @Order(84)
    void parseGeneratedDsl_bindingReferencesUnknownField_returnsError() {
        String json = """
                {
                  "pluginCode": "bad_field_ref",
                  "summary": "Bad field reference",
                  "resources": {
                    "models": [{"code": "item", "modelType": "entity"}],
                    "fields": [{"code": "name", "dataType": "string"}],
                    "bindings": [{"modelCode": "item", "fieldCode": "nonexistent_field", "sequence": 1}],
                    "commands": [], "pages": [], "menus": [], "i18n": [], "permissions": []
                  }
                }
                """;

        NlModelingResponse response = nlModelingService.parseGeneratedDsl(json);

        assertThat(response.getValidationErrors()).isNotNull();
        assertThat(response.getValidationErrors()).anyMatch(e -> e.contains("unknown field 'nonexistent_field'"));
    }

    @Test
    @Order(85)
    void refine_withNullInstruction_returnsValidationError() {
        NlRefineRequest request = NlRefineRequest.builder().build();

        NlModelingResponse response = nlModelingService.refine(request);

        assertThat(response.getValidationErrors()).contains("instruction is required");
    }

    @Test
    @Order(86)
    void generate_withNullOptions_usesDefaults() throws Exception {
        mockLlmResponse(NlModelingService.FEW_SHOT_EXAMPLE);

        NlModelingRequest request = NlModelingRequest.builder()
                .description("A simple task tracker")
                .build();

        NlModelingResponse response = nlModelingService.generate(request);

        assertThat(response).isNotNull();
        assertThat(response.getPluginCode()).isNotNull();
        assertThat(response.getResources()).isNotNull();
    }

    @Test
    @Order(87)
    void generate_withLlmReturningEmptyResponse_returnsError() throws Exception {
        LlmProvider mockProvider = mock(LlmProvider.class);
        LlmChatResponse emptyResponse = LlmChatResponse.builder()
                .stopReason("end_turn")
                .content(List.of(LlmChatResponse.ContentBlock.builder()
                        .type("text")
                        .text("")
                        .build()))
                .inputTokens(100)
                .outputTokens(0)
                .build();

        when(mockProvider.chat(any(LlmChatRequest.class), anyString(), anyString()))
                .thenReturn(emptyResponse);

        LlmProviderFactory.ProviderConfig config = LlmProviderFactory.ProviderConfig.builder()
                .providerCode("openai")
                .apiKey("test-key")
                .baseUrl("https://api.openai.com")
                .defaultModel("gpt-4o")
                .maxTokens(8192)
                .build();

        when(providerFactory.resolveConfig(any(), anyString())).thenReturn(config);
        when(providerFactory.getProvider(anyString())).thenReturn(mockProvider);

        NlModelingRequest request = NlModelingRequest.builder()
                .description("An order management system")
                .options(NlModelingRequest.Options.builder().build())
                .build();

        NlModelingResponse response = nlModelingService.generate(request);

        assertThat(response.getValidationErrors()).isNotNull();
        assertThat(response.getValidationErrors().get(0)).contains("LLM returned empty response");
    }
}
