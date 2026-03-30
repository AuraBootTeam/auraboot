package com.auraboot.framework.agent.nlmodeling;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.nlmodeling.dto.*;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.dto.imports.ImportExecuteResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NL Modeling Service — converts natural language descriptions into AuraBoot DSL
 * configurations (models, fields, commands, pages, menus, i18n, bindings).
 *
 * <p>Uses LLM with structured JSON output mode and DSL schema-aware system prompts.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NlModelingService {

    private final LlmProviderFactory providerFactory;
    private final PluginImportService pluginImportService;
    private final ObjectMapper objectMapper;

    /** In-memory session store for conversational refinement (session-scoped, not persistent) */
    private final Map<String, List<LlmChatRequest.Message>> sessionHistory = new ConcurrentHashMap<>();

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Generate DSL from a natural language description.
     */
    public NlModelingResponse generate(NlModelingRequest request) {
        if (request.getDescription() == null || request.getDescription().isBlank()) {
            return NlModelingResponse.builder()
                    .validationErrors(List.of("description is required"))
                    .build();
        }

        NlModelingRequest.Options opts = request.getOptions();
        if (opts == null) {
            opts = NlModelingRequest.Options.builder().build();
        }

        String systemPrompt = buildSystemPrompt(opts);
        String userMessage = buildGenerateUserMessage(request.getDescription(), opts);

        // Create a new session
        String sessionId = UUID.randomUUID().toString();
        List<LlmChatRequest.Message> messages = new ArrayList<>();
        messages.add(LlmChatRequest.Message.builder()
                .role("user").content(userMessage).build());

        LlmChatResponse llmResponse = callLlm(systemPrompt, messages);
        if (llmResponse == null) {
            return NlModelingResponse.builder()
                    .validationErrors(List.of("LLM call failed — check provider configuration"))
                    .build();
        }

        // Extract text from response
        String responseText = extractText(llmResponse);
        if (responseText == null || responseText.isBlank()) {
            return NlModelingResponse.builder()
                    .validationErrors(List.of("LLM returned empty response"))
                    .build();
        }

        // Parse JSON from response
        NlModelingResponse result = parseGeneratedDsl(responseText);
        if (result.getValidationErrors() != null && !result.getValidationErrors().isEmpty()) {
            return result;
        }

        // Store session for refinement
        messages.add(LlmChatRequest.Message.builder()
                .role("assistant").content(responseText).build());
        sessionHistory.put(sessionId, messages);

        result.setSessionId(sessionId);
        result.setTokenUsage(NlModelingResponse.TokenUsage.builder()
                .inputTokens(llmResponse.getInputTokens())
                .outputTokens(llmResponse.getOutputTokens())
                .build());

        return result;
    }

    /**
     * Refine existing DSL via conversational instruction.
     */
    public NlModelingResponse refine(NlRefineRequest request) {
        if (request.getInstruction() == null || request.getInstruction().isBlank()) {
            return NlModelingResponse.builder()
                    .validationErrors(List.of("instruction is required"))
                    .build();
        }

        String sessionId = request.getSessionId();
        List<LlmChatRequest.Message> history;
        if (sessionId != null && sessionHistory.containsKey(sessionId)) {
            history = new ArrayList<>(sessionHistory.get(sessionId));
        } else {
            // Start new session with current resources as context
            sessionId = UUID.randomUUID().toString();
            history = new ArrayList<>();
            if (request.getCurrentResources() != null) {
                try {
                    String resourcesJson = objectMapper.writeValueAsString(request.getCurrentResources());
                    history.add(LlmChatRequest.Message.builder()
                            .role("user")
                            .content("Here is the current DSL configuration I want to modify:\n```json\n" + resourcesJson + "\n```")
                            .build());
                    history.add(LlmChatRequest.Message.builder()
                            .role("assistant")
                            .content("I understand. I have the current DSL configuration. What changes would you like to make?")
                            .build());
                } catch (Exception e) {
                    log.warn("Failed to serialize current resources: {}", e.getMessage());
                }
            }
        }

        // Add the refinement instruction
        history.add(LlmChatRequest.Message.builder()
                .role("user")
                .content(request.getInstruction() + "\n\nPlease output the complete updated DSL JSON (same format as before).")
                .build());

        String systemPrompt = buildSystemPrompt(NlModelingRequest.Options.builder().build());
        LlmChatResponse llmResponse = callLlm(systemPrompt, history);
        if (llmResponse == null) {
            return NlModelingResponse.builder()
                    .validationErrors(List.of("LLM call failed — check provider configuration"))
                    .build();
        }

        String responseText = extractText(llmResponse);
        NlModelingResponse result = parseGeneratedDsl(responseText);

        // Update session
        history.add(LlmChatRequest.Message.builder()
                .role("assistant").content(responseText).build());
        sessionHistory.put(sessionId, history);

        result.setSessionId(sessionId);
        result.setTokenUsage(NlModelingResponse.TokenUsage.builder()
                .inputTokens(llmResponse.getInputTokens())
                .outputTokens(llmResponse.getOutputTokens())
                .build());

        return result;
    }

    /**
     * Apply generated DSL as a plugin via the plugin import pipeline.
     */
    @SuppressWarnings("unchecked")
    public ImportExecuteResult apply(NlApplyRequest request) {
        if (request.getPluginCode() == null || request.getPluginCode().isBlank()) {
            return ImportExecuteResult.builder()
                    .success(false)
                    .errorMessage("pluginCode is required")
                    .build();
        }
        if (request.getResources() == null) {
            return ImportExecuteResult.builder()
                    .success(false)
                    .errorMessage("resources are required")
                    .build();
        }

        try {
            // Convert NlModelingResponse.Resources to PluginManifestExtended
            NlModelingResponse.Resources res = request.getResources();
            String manifestJson = buildPluginManifestJson(request.getPluginCode(), res);

            PluginManifestExtended manifest = objectMapper.readValue(manifestJson, PluginManifestExtended.class);

            // Validate before importing
            List<String> errors = pluginImportService.validateManifest(manifest);
            if (!errors.isEmpty()) {
                return ImportExecuteResult.builder()
                        .success(false)
                        .errorMessage("Validation failed: " + String.join("; ", errors))
                        .build();
            }

            // Execute import with OVERWRITE strategy (dev phase)
            ImportRequest importRequest = ImportRequest.builder()
                    .conflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE)
                    .autoPublishModels(true)
                    .autoPublishFields(true)
                    .autoPublishCommands(true)
                    .autoPublishPages(true)
                    .build();

            return pluginImportService.executeFromManifest(manifest, importRequest);
        } catch (Exception e) {
            log.error("Failed to apply NL modeling result: {}", e.getMessage(), e);
            return ImportExecuteResult.builder()
                    .success(false)
                    .errorMessage("Apply failed: " + e.getMessage())
                    .build();
        }
    }

    // =========================================================================
    // LLM Interaction
    // =========================================================================

    private LlmChatResponse callLlm(String systemPrompt, List<LlmChatRequest.Message> messages) {
        Long tenantId = MetaContext.getCurrentTenantId();

        // Resolve provider and config (prefer anthropic, fallback to any configured)
        LlmProviderFactory.ProviderConfig config = providerFactory.resolveConfig(tenantId, "anthropic");
        String providerCode = "anthropic";
        if (config == null) {
            // Try to find any configured provider
            var providers = providerFactory.listConfiguredProviders(tenantId);
            if (providers.isEmpty()) {
                log.error("No LLM provider configured for NL modeling");
                return null;
            }
            providerCode = providers.get(0).getProviderCode();
            config = providerFactory.resolveConfig(tenantId, providerCode);
        }
        if (config == null) {
            log.error("Cannot resolve LLM config for provider: {}", providerCode);
            return null;
        }

        LlmProvider provider = providerFactory.getProvider(providerCode);

        LlmChatRequest request = LlmChatRequest.builder()
                .model(config.getDefaultModel())
                .systemPrompt(systemPrompt)
                .messages(messages)
                .maxTokens(8192)
                .build();

        try {
            return provider.chat(request, config.getApiKey(), config.getBaseUrl());
        } catch (Exception e) {
            log.error("LLM call failed: {}", e.getMessage(), e);
            return null;
        }
    }

    // =========================================================================
    // Prompt Engineering
    // =========================================================================

    public static String buildSystemPrompt(NlModelingRequest.Options opts) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are an expert AuraBoot DSL architect. Your job is to convert natural language \
                business module descriptions into complete AuraBoot plugin DSL configurations.

                ## Output Format
                You MUST output ONLY a single JSON object (no markdown fences, no explanation outside the JSON). \
                The JSON must follow this exact structure:

                ```
                {
                  "pluginCode": "<snake_case_module_code>",
                  "summary": "<one-line description of what was generated>",
                  "resources": {
                    "models": [...],
                    "fields": [...],
                    "bindings": [...],
                    "commands": [...],
                    "pages": [...],
                    "menus": [...],
                    "i18n": [...],
                    "permissions": [...]
                  }
                }
                ```

                ## DSL Schema Reference

                ### Model Definition
                ```json
                {
                  "code": "<snake_case>",
                  "displayName:zh-CN": "<Chinese name>",
                  "displayName:en": "<English name>",
                  "description": "<brief description>",
                  "modelType": "entity",
                  "modelCategory": "MASTER|DOCUMENT|REFERENCE",
                  "extension": {
                    "titleField": "<field_code>",
                    "subtitleField": "<field_code>",
                    "tableName": null,
                    "softDelete": false
                  }
                }
                ```
                - modelCategory: MASTER for standalone entities, DOCUMENT for stateful workflow entities, REFERENCE for lookup tables
                - tableName: set to null — the platform auto-creates dynamic tables (mt_<code>)
                - softDelete: false for dynamic tables (mt_* has no deleted_flag)

                ### Field Definition
                ```json
                {
                  "code": "<snake_case>",
                  "displayName:zh-CN": "<Chinese name>",
                  "displayName:en": "<English name>",
                  "dataType": "STRING|INTEGER|DECIMAL|BOOLEAN|DATE|DATETIME|ENUM|REFERENCE|FILE|TEXT",
                  "dictCode": "<dict_code, only for ENUM type>",
                  "constraints": { "required": true, "maxLength": 128 },
                  "feature": { "searchable": true },
                  "defaultValue": null
                }
                ```
                - Field code must be snake_case
                - For ENUM fields, dictCode is required (but dict creation is out of scope here — just reference the code)
                - REFERENCE fields need an extension with refModelCode

                ### Model-Field Binding (bindings)
                ```json
                {
                  "modelCode": "<model_code>",
                  "fieldCode": "<field_code>",
                  "sequence": 1,
                  "required": true,
                  "visible": true,
                  "editable": true,
                  "displayConfig": { "searchable": true, "sortable": true }
                }
                ```
                - Every field must be bound to its model
                - sequence: display order (1-based)
                - required/visible/editable control form behavior

                ### Command Definition
                ```json
                {
                  "code": "<namespace>:create_<model>",
                  "displayName:zh-CN": "<Chinese name>",
                  "displayName:en": "<English name>",
                  "type": "CREATE|UPDATE|DELETE|STATE_TRANSITION|BULK_UPDATE",
                  "modelCode": "<model_code>",
                  "inputFields": ["field1", "field2"]
                }
                ```
                - Standard CRUD: create, update, delete commands per model
                - code format: <pluginCode>:create_<model>, <pluginCode>:update_<model>, <pluginCode>:delete_<model>
                - inputFields: list of field codes accepted by this command
                - DELETE commands typically have no inputFields and use extension.confirmMessage

                ### Page Schema
                ```json
                {
                  "pageKey": "<model_code>_list",
                  "name:zh-CN": "<Chinese name>",
                  "name:en": "<English name>",
                  "kind": "LIST|FORM|DETAIL",
                  "modelCode": "<model_code>",
                  "blocks": {
                    "kind": "List",
                    "version": "1.0.0",
                    "id": "list.<model_code>",
                    "modelCode": "<model_code>",
                    "layout": {
                      "areas": ["toolbar", "content"],
                      "areasConfig": {
                        "toolbar": { "type": "flex", "direction": "row" },
                        "content": { "type": "flex", "direction": "column" }
                      }
                    },
                    "areas": {
                      "toolbar": {
                        "blocks": [{
                          "id": "toolbar",
                          "blockType": "toolbar",
                          "buttons": [{
                            "code": "create",
                            "variant": "primary",
                            "label": "create",
                            "action": {
                              "type": "navigate",
                              "to": "<model_code>_form",
                              "command": "<namespace>:create_<model_code>"
                            }
                          }]
                        }]
                      },
                      "content": {
                        "blocks": [{
                          "id": "table",
                          "blockType": "table",
                          "columns": [
                            { "field": "<field_code>", "width": 200, "sortable": true }
                          ],
                          "rowActions": [
                            { "code": "edit", "label": "edit", "action": { "type": "navigate", "to": "<model_code>_form", "command": "<namespace>:update_<model_code>" } },
                            { "code": "delete", "label": "delete", "variant": "danger", "action": { "type": "command", "command": "<namespace>:delete_<model_code>" } }
                          ]
                        }]
                      }
                    }
                  }
                }
                ```
                - List page: toolbar (create button) + table with columns and row actions
                - Form page: kind=form, layout with form-section blocks

                ### Form Page Schema
                ```json
                {
                  "pageKey": "<model_code>_form",
                  "name:zh-CN": "<Chinese form name>",
                  "name:en": "<English form name>",
                  "kind": "form",
                  "modelCode": "<model_code>",
                  "blocks": {
                    "kind": "Form",
                    "version": "1.0.0",
                    "id": "form.<model_code>",
                    "modelCode": "<model_code>",
                    "layout": {
                      "areas": ["content"],
                      "areasConfig": {
                        "content": { "type": "flex", "direction": "column" }
                      }
                    },
                    "areas": {
                      "content": {
                        "blocks": [{
                          "id": "basic_info",
                          "blockType": "form-section",
                          "title": "basic_info",
                          "columns": 2,
                          "fields": [
                            { "field": "<field_code>" }
                          ]
                        }]
                      }
                    }
                  }
                }
                ```

                ### Menu Definition
                ```json
                {
                  "code": "<UPPER_SNAKE_MENU_CODE>",
                  "parentCode": null,
                  "name:zh-CN": "<Chinese name>",
                  "name:en": "<English name>",
                  "path": "/dynamic/<kebab-case-model-code>",
                  "component": null,
                  "icon": "IconDatabase",
                  "type": 1,
                  "permissionCode": "<UPPER_SNAKE_PERMISSION>",
                  "orderNo": 10,
                  "visible": true,
                  "extension": { "platforms": ["web"] }
                }
                ```
                - type=0 for directory (parent menu), type=1 for page link
                - path for DSL pages: /dynamic/<model-code-kebab-case>
                - First create a parent menu (type=0, path=null), then child menus with parentCode

                ### i18n Definition
                ```json
                {
                  "key": "model.<model_code>._meta.label",
                  "zh-CN": "<Chinese model name>",
                  "en-US": "<English model name>",
                  "source": "import",
                  "refType": "model"
                }
                ```
                - Model label: key = "model.<model_code>._meta.label", refType = "model"
                - Field label: key = "model.<model_code>.<field_code>.label", refType = "field"

                ### Permission Definition
                ```json
                {
                  "code": "dynamic.<model_code>.read",
                  "name:zh-CN": "<Chinese name>",
                  "name:en": "<English name>",
                  "type": "dynamic",
                  "description": "Read access to <model>"
                }
                ```
                - Permissions are auto-created when models are published, so you typically don't need to generate them
                - Only generate explicit permissions if custom permission codes are needed for menus

                ## Naming Conventions
                - Model code: snake_case (e.g., customer, follow_up_record)
                - Field code: snake_case (e.g., company_name, contact_phone)
                - Command code: <pluginCode>:create_<model>, <pluginCode>:update_<model>, <pluginCode>:delete_<model>
                - Menu code: UPPER_SNAKE_CASE (e.g., NL_CUSTOMER_LIST)
                - i18n key: model.<model_code>.<field_code>.label
                - Page key: <model_code>_list, <model_code>_form

                ## Important Rules
                1. Field codes should NOT include the model code as prefix (e.g., use "name" not "customer_name")
                2. Every ENTITY model MUST have bindings for all its fields
                3. Generate bilingual displayNames (zh-CN and en) for ALL resources
                4. For ENUM fields, use a dictCode like "<model_code>_<field_code>" (the dict will be created separately)
                5. Generate a parent menu (type=0) to group all child page menus
                6. The permissions array can be empty — dynamic permissions are auto-created on model publish

                ## Few-Shot Example
                For a simple "Book Management" module with title, author, ISBN, price, and published date:

                """);

        sb.append(FEW_SHOT_EXAMPLE);

        return sb.toString();
    }

    private String buildGenerateUserMessage(String description, NlModelingRequest.Options opts) {
        StringBuilder sb = new StringBuilder();
        sb.append("Please generate a complete AuraBoot DSL plugin configuration for the following requirement:\n\n");
        sb.append(description).append("\n\n");
        sb.append("Generation options:\n");
        sb.append("- Pages: ").append(opts.isGeneratePages() ? "yes" : "no").append("\n");
        sb.append("- Commands: ").append(opts.isGenerateCommands() ? "yes" : "no").append("\n");
        sb.append("- Menus: ").append(opts.isGenerateMenus() ? "yes" : "no").append("\n");
        sb.append("- i18n: ").append(opts.isGenerateI18n() ? "yes" : "no").append("\n");
        sb.append("- Bindings: ").append(opts.isGenerateBindings() ? "yes" : "no").append("\n");
        sb.append("\nOutput the complete JSON object. No markdown, no explanation outside the JSON.");
        return sb.toString();
    }

    // =========================================================================
    // Response Parsing
    // =========================================================================

    private String extractText(LlmChatResponse response) {
        if (response.getContent() == null) return null;
        return response.getContent().stream()
                .filter(b -> "text".equals(b.getType()))
                .map(LlmChatResponse.ContentBlock::getText)
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    public NlModelingResponse parseGeneratedDsl(String text) {
        if (text == null || text.isBlank()) {
            return NlModelingResponse.builder()
                    .validationErrors(List.of("Empty LLM response"))
                    .build();
        }

        // Strip markdown code fences if present
        String json = text.trim();
        if (json.startsWith("```")) {
            int firstNewline = json.indexOf('\n');
            if (firstNewline > 0) {
                json = json.substring(firstNewline + 1);
            }
            if (json.endsWith("```")) {
                json = json.substring(0, json.length() - 3).trim();
            }
        }

        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});

            String pluginCode = (String) parsed.get("pluginCode");
            String summary = (String) parsed.get("summary");
            Map<String, Object> resourcesMap = (Map<String, Object>) parsed.get("resources");

            if (resourcesMap == null) {
                return NlModelingResponse.builder()
                        .pluginCode(pluginCode)
                        .summary(summary)
                        .validationErrors(List.of("LLM output missing 'resources' key"))
                        .build();
            }

            NlModelingResponse.Resources resources = NlModelingResponse.Resources.builder()
                    .models(safeList(resourcesMap, "models"))
                    .fields(safeList(resourcesMap, "fields"))
                    .bindings(safeList(resourcesMap, "bindings"))
                    .commands(safeList(resourcesMap, "commands"))
                    .pages(safeList(resourcesMap, "pages"))
                    .menus(safeList(resourcesMap, "menus"))
                    .i18n(safeList(resourcesMap, "i18n"))
                    .permissions(safeList(resourcesMap, "permissions"))
                    .build();

            // Basic validation
            List<String> errors = validateResources(resources);

            return NlModelingResponse.builder()
                    .pluginCode(pluginCode)
                    .summary(summary)
                    .resources(resources)
                    .validationErrors(errors.isEmpty() ? null : errors)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse LLM output as JSON: {}", e.getMessage());
            return NlModelingResponse.builder()
                    .validationErrors(List.of("Failed to parse LLM output as JSON: " + e.getMessage()))
                    .build();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> safeList(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof List<?> list) {
            return (List<Map<String, Object>>) (List<?>) list;
        }
        return List.of();
    }

    private List<String> validateResources(NlModelingResponse.Resources resources) {
        List<String> errors = new ArrayList<>();

        if (resources.getModels() == null || resources.getModels().isEmpty()) {
            errors.add("No models generated");
        } else {
            for (int i = 0; i < resources.getModels().size(); i++) {
                Map<String, Object> m = resources.getModels().get(i);
                if (m.get("code") == null) {
                    errors.add("models[" + i + "]: missing 'code'");
                }
            }
        }

        if (resources.getFields() != null) {
            for (int i = 0; i < resources.getFields().size(); i++) {
                Map<String, Object> f = resources.getFields().get(i);
                if (f.get("code") == null) {
                    errors.add("fields[" + i + "]: missing 'code'");
                }
                if (f.get("dataType") == null) {
                    errors.add("fields[" + i + "]: missing 'dataType'");
                }
            }
        }

        // Validate bindings reference existing models and fields
        if (resources.getBindings() != null) {
            Set<String> modelCodes = new HashSet<>();
            Set<String> fieldCodes = new HashSet<>();
            if (resources.getModels() != null) {
                resources.getModels().forEach(m -> {
                    Object code = m.get("code");
                    if (code != null) modelCodes.add(code.toString());
                });
            }
            if (resources.getFields() != null) {
                resources.getFields().forEach(f -> {
                    Object code = f.get("code");
                    if (code != null) fieldCodes.add(code.toString());
                });
            }

            for (int i = 0; i < resources.getBindings().size(); i++) {
                Map<String, Object> b = resources.getBindings().get(i);
                String mc = (String) b.get("modelCode");
                String fc = (String) b.get("fieldCode");
                if (mc != null && !modelCodes.contains(mc)) {
                    errors.add("bindings[" + i + "]: references unknown model '" + mc + "'");
                }
                if (fc != null && !fieldCodes.contains(fc)) {
                    errors.add("bindings[" + i + "]: references unknown field '" + fc + "'");
                }
            }
        }

        return errors;
    }

    // =========================================================================
    // Plugin Manifest Builder
    // =========================================================================

    @SuppressWarnings("unchecked")
    private String buildPluginManifestJson(String pluginCode, NlModelingResponse.Resources res) throws Exception {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("pluginId", "nl-" + pluginCode);
        manifest.put("namespace", pluginCode);
        manifest.put("version", "1.0.0");
        manifest.put("displayName", pluginCode);
        manifest.put("description", "Auto-generated by NL Modeling");

        // Map resources to plugin manifest format
        manifest.put("models", res.getModels() != null ? res.getModels() : List.of());
        manifest.put("fields", res.getFields() != null ? res.getFields() : List.of());
        manifest.put("modelFieldBindings", res.getBindings() != null ? res.getBindings() : List.of());
        manifest.put("commands", res.getCommands() != null ? res.getCommands() : List.of());
        manifest.put("pages", res.getPages() != null ? res.getPages() : List.of());
        manifest.put("menus", res.getMenus() != null ? res.getMenus() : List.of());
        manifest.put("i18nResources", res.getI18n() != null ? res.getI18n() : List.of());
        manifest.put("permissions", res.getPermissions() != null ? res.getPermissions() : List.of());

        return objectMapper.writeValueAsString(manifest);
    }

    // =========================================================================
    // Few-Shot Example
    // =========================================================================

    public static final String FEW_SHOT_EXAMPLE = """
            {
              "pluginCode": "book_mgmt",
              "summary": "Generated book management module with 1 model, 5 fields, CRUD commands, list/form pages, and menus",
              "resources": {
                "models": [
                  {
                    "code": "book",
                    "displayName:zh-CN": "图书",
                    "displayName:en": "Book",
                    "description": "Book catalog management",
                    "modelType": "entity",
                    "modelCategory": "master",
                    "extension": {
                      "titleField": "title",
                      "subtitleField": "author",
                      "tableName": null,
                      "softDelete": false
                    }
                  }
                ],
                "fields": [
                  { "code": "title", "displayName:zh-CN": "书名", "displayName:en": "Title", "dataType": "string", "constraints": { "required": true, "maxLength": 200 }, "feature": { "searchable": true } },
                  { "code": "author", "displayName:zh-CN": "作者", "displayName:en": "Author", "dataType": "string", "constraints": { "maxLength": 100 }, "feature": { "searchable": true } },
                  { "code": "isbn", "displayName:zh-CN": "isbn", "displayName:en": "isbn", "dataType": "string", "constraints": { "maxLength": 20 } },
                  { "code": "price", "displayName:zh-CN": "价格", "displayName:en": "Price", "dataType": "decimal" },
                  { "code": "published_date", "displayName:zh-CN": "出版日期", "displayName:en": "Published Date", "dataType": "date" }
                ],
                "bindings": [
                  { "modelCode": "book", "fieldCode": "title", "sequence": 1, "required": true, "visible": true, "editable": true, "displayConfig": { "searchable": true, "sortable": true } },
                  { "modelCode": "book", "fieldCode": "author", "sequence": 2, "required": false, "visible": true, "editable": true, "displayConfig": { "searchable": true } },
                  { "modelCode": "book", "fieldCode": "isbn", "sequence": 3, "required": false, "visible": true, "editable": true },
                  { "modelCode": "book", "fieldCode": "price", "sequence": 4, "required": false, "visible": true, "editable": true },
                  { "modelCode": "book", "fieldCode": "published_date", "sequence": 5, "required": false, "visible": true, "editable": true }
                ],
                "commands": [
                  { "code": "book_mgmt:create_book", "displayName:zh-CN": "创建图书", "displayName:en": "Create Book", "type": "create", "modelCode": "book", "inputFields": ["title", "author", "isbn", "price", "published_date"] },
                  { "code": "book_mgmt:update_book", "displayName:zh-CN": "更新图书", "displayName:en": "Update Book", "type": "update", "modelCode": "book", "inputFields": ["title", "author", "isbn", "price", "published_date"] },
                  { "code": "book_mgmt:delete_book", "displayName:zh-CN": "删除图书", "displayName:en": "Delete Book", "type": "delete", "modelCode": "book", "extension": { "confirmMessage:zh-CN": "确认删除此图书？", "confirmMessage:en": "Confirm delete this book?" } }
                ],
                "pages": [
                  {
                    "pageKey": "book_list",
                    "name:zh-CN": "图书列表",
                    "name:en": "Book List",
                    "kind": "list",
                    "modelCode": "book",
                    "blocks": {
                      "kind": "List",
                      "version": "1.0.0",
                      "id": "list.book",
                      "modelCode": "book",
                      "layout": { "areas": ["toolbar", "content"], "areasConfig": { "toolbar": { "type": "flex", "direction": "row" }, "content": { "type": "flex", "direction": "column" } } },
                      "areas": {
                        "toolbar": { "blocks": [{ "id": "toolbar", "blockType": "toolbar", "buttons": [{ "code": "create", "variant": "primary", "label": "create", "action": { "type": "navigate", "to": "book_form", "command": "book_mgmt:create_book" } }] }] },
                        "content": { "blocks": [{ "id": "table", "blockType": "table", "columns": [ { "field": "title", "width": 200, "sortable": true }, { "field": "author", "width": 150 }, { "field": "isbn", "width": 150 }, { "field": "price", "width": 100 }, { "field": "published_date", "width": 120 } ], "rowActions": [ { "code": "edit", "label": "edit", "action": { "type": "navigate", "to": "book_form", "command": "book_mgmt:update_book" } }, { "code": "delete", "label": "delete", "variant": "danger", "action": { "type": "command", "command": "book_mgmt:delete_book" } } ] }] }
                      }
                    }
                  },
                  {
                    "pageKey": "book_form",
                    "name:zh-CN": "图书表单",
                    "name:en": "Book Form",
                    "kind": "form",
                    "modelCode": "book",
                    "blocks": {
                      "kind": "Form",
                      "version": "1.0.0",
                      "id": "form.book",
                      "modelCode": "book",
                      "layout": { "areas": ["content"], "areasConfig": { "content": { "type": "flex", "direction": "column" } } },
                      "areas": { "content": { "blocks": [{ "id": "basic_info", "blockType": "form-section", "title": "basic_info", "columns": 2, "fields": [ { "field": "title" }, { "field": "author" }, { "field": "isbn" }, { "field": "price" }, { "field": "published_date" } ] }] } }
                    }
                  }
                ],
                "menus": [
                  { "code": "nl_book_mgmt", "parentCode": null, "name:zh-CN": "图书管理", "name:en": "Book Management", "path": null, "component": null, "icon": "IconBook", "type": 0, "permissionCode": null, "orderNo": 100, "visible": true, "extension": { "platforms": ["web"] } },
                  { "code": "nl_book_list", "parentCode": "nl_book_mgmt", "name:zh-CN": "图书列表", "name:en": "Book List", "path": "/dynamic/book", "component": null, "icon": "IconList", "type": 1, "permissionCode": "dynamic.book.read", "orderNo": 10, "visible": true, "extension": { "platforms": ["web"] } }
                ],
                "i18n": [
                  { "key": "model.book._meta.label", "zh-CN": "图书", "en-US": "Book", "source": "import", "refType": "model" },
                  { "key": "model.book.title.label", "zh-CN": "书名", "en-US": "Title", "source": "import", "refType": "field" },
                  { "key": "model.book.author.label", "zh-CN": "作者", "en-US": "Author", "source": "import", "refType": "field" },
                  { "key": "model.book.isbn.label", "zh-CN": "isbn", "en-US": "isbn", "source": "import", "refType": "field" },
                  { "key": "model.book.price.label", "zh-CN": "价格", "en-US": "Price", "source": "import", "refType": "field" },
                  { "key": "model.book.published_date.label", "zh-CN": "出版日期", "en-US": "Published Date", "source": "import", "refType": "field" }
                ],
                "permissions": []
              }
            }
            """;
}
