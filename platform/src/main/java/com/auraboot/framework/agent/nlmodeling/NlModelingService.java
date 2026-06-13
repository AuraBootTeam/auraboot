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
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

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

    /**
     * In-memory session store for conversational refinement (session-scoped,
     * not persistent).
     *
     * <p>LRU-bounded via Caffeine to prevent unbounded growth — every
     * {@link #generate} call inserts a {@code UUID.randomUUID()} key and the
     * previous {@link ConcurrentHashMap} had no eviction. See deep-review
     * P1-2. Defaults: max 1000 sessions, 2-hour TTL after last access.
     * Refinement of a session beyond that window starts a fresh history,
     * which is the same behavior as if the JVM had restarted — acceptable
     * given the comment above says "session-scoped, not persistent".
     */
    private final Cache<String, List<LlmChatRequest.Message>> sessionHistory = Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterAccess(Duration.ofHours(2))
            .build();

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
        List<LlmChatRequest.Message> existing = sessionId == null ? null : sessionHistory.getIfPresent(sessionId);
        if (existing != null) {
            history = new ArrayList<>(existing);
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

        String effectiveProviderCode = LlmProviderFactory.effectiveProviderCode(providerCode, config);
        LlmProvider provider = providerFactory.getProvider(effectiveProviderCode);

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
                  "blocks": [{
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
                  }]
                }
                ```
                - IMPORTANT: `blocks` is always a JSON array (e.g., `"blocks": [{ ... }]`), never a bare object.
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
                  "blocks": [{
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
                  }]
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
    /**
     * Build the plugin import manifest JSON from generated resources, applying
     * deterministic conformance post-processing so that LLM-generated DSL passes
     * the strict import pipeline without an external fix-up layer:
     * <ul>
     *   <li>{@code command.type} / {@code field.dataType} are lower-cased
     *       (the import executor expects lower-case; LLMs emit upper-case enums).</li>
     *   <li>Dynamic-path menus ({@code /dynamic/<model>}) missing a {@code pageKey}
     *       are wired to {@code <model>_list} so navigation resolves to a page.</li>
     *   <li>{@code dicts} (enum value sets) are carried through so ENUM fields need
     *       not be downgraded to STRING.</li>
     * </ul>
     *
     * <p>Package-private for unit testing ({@code NlModelingManifestPostProcessingTest}).
     */
    String buildPluginManifestJson(String pluginCode, NlModelingResponse.Resources res) throws Exception {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("pluginId", "nl-" + pluginCode);
        manifest.put("namespace", pluginCode);
        manifest.put("version", "1.0.0");
        manifest.put("displayName", pluginCode);
        manifest.put("description", "Auto-generated by NL Modeling");

        // Map resources to plugin manifest format, with conformance post-processing.
        List<Map<String, Object>> fields = lowercaseStringKey(res.getFields(), "dataType");
        downgradeOrphanEnumFields(fields, res.getDicts());
        conformFieldLabels(fields);
        manifest.put("models", conformModels(res.getModels()));
        manifest.put("fields", fields);
        manifest.put("modelFieldBindings", synthesizeBindings(res.getModels(), fields, res.getBindings()));
        List<Map<String, Object>> commands =
                synthesizeCrudCommands(pluginCode, res.getModels(), fields, res.getCommands());
        manifest.put("commands", lowercaseStringKey(commands, "type"));
        List<Map<String, Object>> pages =
                synthesizePages(pluginCode, res.getModels(), fields, res.getPages());
        manifest.put("pages", pages);
        manifest.put("menus", deriveDynamicMenuPageKeys(
                synthesizeMenus(res.getModels(), res.getMenus())));
        manifest.put("i18nResources", res.getI18n() != null ? res.getI18n() : List.of());
        manifest.put("permissions", res.getPermissions() != null ? res.getPermissions() : List.of());
        manifest.put("dicts", res.getDicts() != null ? res.getDicts() : List.of());

        return objectMapper.writeValueAsString(manifest);
    }

    /**
     * Lower-cases a string-valued {@code key} in every map of a resource list.
     * Returns an empty list for null input; missing / non-string values are left
     * untouched. Mutates the (single-use, per-request) maps in place.
     */
    static List<Map<String, Object>> lowercaseStringKey(List<Map<String, Object>> items, String key) {
        if (items == null) {
            return List.of();
        }
        for (Map<String, Object> item : items) {
            if (item != null && item.get(key) instanceof String value) {
                item.put(key, value.toLowerCase(Locale.ROOT));
            }
        }
        return items;
    }

    /**
     * Wires dynamic-path menus that omit {@code pageKey} to their list page: a
     * menu whose {@code path} is {@code /dynamic/<kebab-model>} with no pageKey
     * gets {@code pageKey = <snake_model>_list}. Returns an empty list for null
     * input; non-dynamic or already-keyed menus are left untouched.
     */
    static List<Map<String, Object>> deriveDynamicMenuPageKeys(List<Map<String, Object>> menus) {
        if (menus == null) {
            return List.of();
        }
        for (Map<String, Object> menu : menus) {
            if (menu == null) {
                continue;
            }
            Object pageKey = menu.get("pageKey");
            boolean missingPageKey = pageKey == null
                    || (pageKey instanceof String pk && pk.isBlank());
            if (missingPageKey && menu.get("path") instanceof String path && path.startsWith("/dynamic/")) {
                String last = path.substring(path.lastIndexOf('/') + 1);
                if (!last.isBlank()) {
                    menu.put("pageKey", last.replace('-', '_') + "_list");
                }
            }
        }
        return menus;
    }

    /**
     * Downgrades {@code enum} fields whose {@code dictCode} has no matching dict in
     * the generated {@code dicts} to a plain {@code string}: the LLM frequently emits
     * an enum field referencing a dictionary it never defines, which the strict import
     * pipeline rejects ("references missing dictionary"). Without an external fix-up,
     * the safe deterministic conformance is to drop the dangling dict reference and
     * keep the field as free text. Mutates the (single-use, per-request) field maps.
     */
    static void downgradeOrphanEnumFields(List<Map<String, Object>> fields,
                                          List<Map<String, Object>> dicts) {
        if (fields == null) {
            return;
        }
        Set<String> dictCodes = new HashSet<>();
        if (dicts != null) {
            for (Map<String, Object> d : dicts) {
                if (d == null) {
                    continue;
                }
                Object code = d.get("code") != null ? d.get("code") : d.get("dictCode");
                if (code instanceof String s && !s.isBlank()) {
                    dictCodes.add(s);
                }
            }
        }
        for (Map<String, Object> f : fields) {
            if (f == null) {
                continue;
            }
            if ("enum".equals(f.get("dataType")) && f.get("dictCode") instanceof String dc
                    && !dictCodes.contains(dc)) {
                f.put("dataType", "string");
                f.remove("dictCode");
            }
        }
    }

    /**
     * Synthesizes {@code modelFieldBindings} from the field list when the LLM emitted
     * none — every entity model must bind at least one field or the import pipeline
     * rejects it. For the common single-model case, all fields bind to that model (in
     * order). When the generation already carries bindings, or there is not exactly one
     * model (the field→model assignment is then ambiguous without per-field hints), the
     * existing bindings are returned untouched and a warning is logged.
     */
    static List<Map<String, Object>> synthesizeBindings(List<Map<String, Object>> models,
                                                        List<Map<String, Object>> fields,
                                                        List<Map<String, Object>> bindings) {
        if (bindings != null && !bindings.isEmpty()) {
            return bindings;
        }
        if (models == null || models.size() != 1 || fields == null || fields.isEmpty()
                || !(models.get(0).get("code") instanceof String modelCode)) {
            if (models != null && models.size() > 1) {
                log.warn("NL modeling generated {} models but no field bindings; cannot "
                        + "safely infer field→model assignment", models.size());
            }
            return bindings != null ? bindings : new ArrayList<>();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        int sequence = 1;
        for (Map<String, Object> f : fields) {
            if (f == null || !(f.get("code") instanceof String fieldCode)) {
                continue;
            }
            boolean required = f.get("constraints") instanceof Map<?, ?> c
                    && Boolean.TRUE.equals(c.get("required"));
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("modelCode", modelCode);
            b.put("fieldCode", fieldCode);
            b.put("sequence", sequence++);
            b.put("required", required);
            b.put("visible", true);
            b.put("editable", true);
            out.add(b);
        }
        return out;
    }

    /** Action verb → zh-CN label for synthesized CRUD command display names. */
    private static final Map<String, String> CRUD_LABEL_ZH =
            Map.of("create", "新建", "update", "编辑", "delete", "删除");

    /**
     * Synthesizes default CRUD commands (create / update / delete) for a single-model
     * generation that carries none. Permission actions are DERIVED from commands
     * ({@code CommandActionDeriver}: a command with {@code type} create/update/delete
     * yields {@code model.<code>.<action>}), so a model with no commands gets no
     * fine-grained permissions and the dynamic CRUD API — gated by
     * {@code @RequirePermission("model.{pageKey}.create")} — returns 403: the generated
     * app imports but cannot be operated. Multi-model is left untouched (the
     * command→model assignment is ambiguous without per-field hints) and logged.
     */
    static List<Map<String, Object>> synthesizeCrudCommands(String pluginCode,
                                                           List<Map<String, Object>> models,
                                                           List<Map<String, Object>> fields,
                                                           List<Map<String, Object>> commands) {
        if (commands != null && !commands.isEmpty()) {
            return commands;
        }
        if (models == null || models.size() != 1
                || !(models.get(0).get("code") instanceof String modelCode)) {
            if (models != null && models.size() > 1) {
                log.warn("NL modeling generated {} models but no commands; cannot safely "
                        + "synthesize CRUD commands for ambiguous command→model assignment", models.size());
            }
            return commands != null ? commands : new ArrayList<>();
        }
        List<String> fieldCodes = new ArrayList<>();
        if (fields != null) {
            for (Map<String, Object> f : fields) {
                if (f != null && f.get("code") instanceof String fc) {
                    fieldCodes.add(fc);
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(crudCommand(pluginCode, modelCode, "create", fieldCodes));
        out.add(crudCommand(pluginCode, modelCode, "update", fieldCodes));
        out.add(crudCommand(pluginCode, modelCode, "delete", List.of()));
        return out;
    }

    private static Map<String, Object> crudCommand(String pluginCode, String modelCode,
                                                  String type, List<String> inputFields) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("code", pluginCode + ":" + type + "_" + modelCode);
        c.put("displayName:zh-CN", CRUD_LABEL_ZH.getOrDefault(type, type) + modelCode);
        c.put("displayName:en",
                Character.toUpperCase(type.charAt(0)) + type.substring(1) + " " + modelCode);
        c.put("type", type);
        c.put("modelCode", modelCode);
        c.put("inputFields", new ArrayList<>(inputFields));
        return c;
    }

    /** Compact ordered-map literal for building synthesized DSL fragments. */
    private static Map<String, Object> om(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    /**
     * Synthesizes default list + form pages for a single-model generation that carries
     * none, so a generated app is navigable/operable in the browser (not just via the
     * dynamic API). Mirrors the few-shot areas-based PageSchema (toolbar+table list,
     * form-section form) wired to the model's CRUD commands. Multi-model is left
     * untouched (page→model assignment is ambiguous) and logged.
     */
    /**
     * Humanizes a snake/kebab code into a business label: {@code "unit_price" -> "Unit Price"}.
     * Used as a fallback display label so synthesized pages/fields carry business wording
     * (the import's S-PAGE-LABEL rejects labels that are raw codes — contain {@code _}/{@code .}
     * or equal the field code).
     */
    static String humanize(String code) {
        if (code == null || code.isEmpty()) {
            return code;
        }
        StringBuilder sb = new StringBuilder();
        for (String part : code.split("[_\\-\\s]+")) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.length() == 0 ? code : sb.toString();
    }

    /**
     * Synthesizes a business {@code displayName} ("Unit Price" from "unit_price") on any field
     * whose labels are all blank or raw codes, so list columns and form fields resolve a label
     * and pass the import's S-PAGE-LABEL rule. Fields that already carry a business label
     * (in any locale) are left untouched.
     */
    static List<Map<String, Object>> conformFieldLabels(List<Map<String, Object>> fields) {
        if (fields == null) {
            return new ArrayList<>();
        }
        for (Map<String, Object> f : fields) {
            if (f == null || !(f.get("code") instanceof String code) || code.isBlank()
                    || hasBusinessLabel(f, code)) {
                continue;
            }
            String label = humanize(code);
            f.put("displayName:en", label);
            f.put("displayName:zh-CN", label);
        }
        return fields;
    }

    private static boolean hasBusinessLabel(Map<String, Object> field, String code) {
        for (String key : List.of("displayName", "displayName:en", "displayName:zh-CN")) {
            if (field.get(key) instanceof String s && !s.isBlank()
                    && !s.equals(code) && !s.contains("_") && !s.contains(".")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Defaults {@code modelType} to {@code "entity"} on any model that omits it.
     * The strict import rejects a model with a missing modelType
     * ("Model '&lt;code&gt;' has missing modelType"); weak LLMs sometimes drop it.
     */
    static List<Map<String, Object>> conformModels(List<Map<String, Object>> models) {
        if (models == null) {
            return new ArrayList<>();
        }
        for (Map<String, Object> m : models) {
            if (m != null && !(m.get("modelType") instanceof String mt && !mt.isBlank())) {
                m.put("modelType", "entity");
            }
        }
        return models;
    }

    static List<Map<String, Object>> synthesizePages(String pluginCode, List<Map<String, Object>> models,
                                                     List<Map<String, Object>> fields,
                                                     List<Map<String, Object>> pages) {
        if (pages != null && !pages.isEmpty()) {
            return pages;
        }
        if (models == null || models.size() != 1
                || !(models.get(0).get("code") instanceof String model)) {
            if (models != null && models.size() > 1) {
                log.warn("NL modeling generated {} models but no pages; cannot synthesize pages "
                        + "for ambiguous page→model assignment", models.size());
            }
            return pages != null ? pages : new ArrayList<>();
        }
        List<String> fieldCodes = new ArrayList<>();
        Set<String> requiredCodes = new HashSet<>();
        if (fields != null) {
            for (Map<String, Object> f : fields) {
                if (f != null && f.get("code") instanceof String fc) {
                    fieldCodes.add(fc);
                    if (f.get("constraints") instanceof Map<?, ?> c
                            && Boolean.TRUE.equals(c.get("required"))) {
                        requiredCodes.add(fc);
                    }
                }
            }
        }
        return new ArrayList<>(List.of(
                listPage(pluginCode, model, fieldCodes),
                formPage(pluginCode, model, fieldCodes, requiredCodes)));
    }

    private static Map<String, Object> listPage(String plugin, String model, List<String> fieldCodes) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (String fc : fieldCodes) {
            columns.add(om("field", fc, "width", 160, "sortable", true));
        }
        columns.add(om("field", "actions", "isActionColumn", true, "label", "$i18n:common.actions",
                "buttons", List.of(
                        om("code", "edit", "action", "edit", "navigateTo", model + "_form",
                                "label", "$i18n:common.button.edit"),
                        om("code", "delete", "action", "delete", "danger", true,
                                "commandCode", plugin + ":delete_" + model,
                                "label", "$i18n:common.button.delete"))));
        Map<String, Object> toolbar = om("id", model + "_toolbar", "blockType", "toolbar",
                "area", "toolbar", "buttons", List.of(
                        om("code", "create", "action", "create", "primary", true,
                                "label", "$i18n:common.button.create")));
        Map<String, Object> table = om("id", model + "_table", "blockType", "table",
                "props", om("rowClickAction", "drawer"),
                "columns", columns,
                "searchFields", new ArrayList<>(fieldCodes),
                "area", "main");
        return om("pageKey", model + "_list",
                "name:zh-CN", humanize(model) + " List", "name:en", humanize(model) + " List",
                "kind", "list", "schemaVersion", 4, "modelCode", model,
                "title", om("zh-CN", humanize(model) + " List", "en", humanize(model) + " List"),
                "layout", om("type", "stack"),
                "blocks", List.of(toolbar, table));
    }

    private static Map<String, Object> formPage(String plugin, String model, List<String> fieldCodes,
                                                Set<String> requiredCodes) {
        List<Map<String, Object>> formFields = new ArrayList<>();
        for (String fc : fieldCodes) {
            Map<String, Object> ff = om("field", fc, "colSpan", 6);
            if (requiredCodes != null && requiredCodes.contains(fc)) {
                ff.put("required", true);
            }
            formFields.add(ff);
        }
        Map<String, Object> section = om("id", "basic", "blockType", "form-section",
                "title", om("zh-CN", "Basic Information", "en-US", "Basic Information"),
                "fields", formFields, "area", "main");
        Map<String, Object> buttons = om("id", "buttons", "blockType", "form-buttons", "area", "footer",
                "buttons", List.of(
                        om("code", "submit", "action", "save", "commandCode", plugin + ":create_" + model,
                                "primary", true, "label", "$i18n:common.button.submit"),
                        om("code", "cancel", "action", "cancel", "label", "$i18n:common.button.cancel")));
        return om("pageKey", model + "_form",
                "name:zh-CN", humanize(model) + " Form", "name:en", humanize(model) + " Form",
                "kind", "form", "schemaVersion", 4, "modelCode", model,
                "title", om("zh-CN", humanize(model) + " Form", "en", humanize(model) + " Form"),
                "layout", om("type", "stack"),
                "blocks", List.of(section, buttons));
    }

    /**
     * Synthesizes a navigation menu pointing at the model's list page when the LLM
     * generated none (single-model). Uses the canonical {@code /p/<model>} dynamic-page
     * route (the convention every built-in plugin menu uses, e.g. {@code /p/tasset_asset});
     * the frontend resolves {@code /p/<model>} to the model's {@code <model>_list} page.
     * The model code must stay snake_case — kebab-casing it (e.g. {@code visit-log}) makes
     * the frontend derive {@code visit-log_list}, which does not match the published page.
     */
    static List<Map<String, Object>> synthesizeMenus(List<Map<String, Object>> models,
                                                    List<Map<String, Object>> menus) {
        if (menus != null && !menus.isEmpty()) {
            return menus;
        }
        if (models == null || models.size() != 1
                || !(models.get(0).get("code") instanceof String model)) {
            return menus != null ? menus : new ArrayList<>();
        }
        return new ArrayList<>(List.of(om(
                "code", "menu_" + model, "name:en", humanize(model), "icon", "table",
                "type", 1, "visible", true,
                "path", "/p/" + model)));
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
                    "schemaVersion": 4,
                    "modelCode": "book",
                    "title": { "zh-CN": "图书列表", "en": "Book List" },
                    "layout": { "type": "stack" },
                    "blocks": [
                      { "id": "book_toolbar", "blockType": "toolbar", "area": "toolbar", "buttons": [{ "code": "create", "action": "create", "primary": true, "label": "$i18n:common.button.create" }] },
                      { "id": "book_table", "blockType": "table", "area": "main", "props": { "rowClickAction": "drawer" }, "columns": [ { "field": "title", "width": 200, "sortable": true }, { "field": "author", "width": 150 }, { "field": "isbn", "width": 150 }, { "field": "price", "width": 100 }, { "field": "published_date", "width": 120 }, { "field": "actions", "isActionColumn": true, "label": "$i18n:common.actions", "buttons": [ { "code": "edit", "action": "edit", "navigateTo": "book_form", "label": "$i18n:common.button.edit" }, { "code": "delete", "action": "delete", "danger": true, "commandCode": "book_mgmt:delete_book", "label": "$i18n:common.button.delete" } ] } ], "searchFields": ["title", "author", "isbn"] }
                    ]
                  },
                  {
                    "pageKey": "book_form",
                    "name:zh-CN": "图书表单",
                    "name:en": "Book Form",
                    "kind": "form",
                    "schemaVersion": 4,
                    "modelCode": "book",
                    "title": { "zh-CN": "图书表单", "en": "Book Form" },
                    "layout": { "type": "stack" },
                    "blocks": [
                      { "id": "basic", "blockType": "form-section", "area": "main", "title": { "zh-CN": "基本信息", "en-US": "Basic Information" }, "fields": [ { "field": "title", "colSpan": 6, "required": true }, { "field": "author", "colSpan": 6 }, { "field": "isbn", "colSpan": 6 }, { "field": "price", "colSpan": 6 }, { "field": "published_date", "colSpan": 6 } ] },
                      { "id": "buttons", "blockType": "form-buttons", "area": "footer", "buttons": [ { "code": "submit", "action": "save", "commandCode": "book_mgmt:create_book", "primary": true, "label": "$i18n:common.button.submit" }, { "code": "cancel", "action": "cancel", "label": "$i18n:common.button.cancel" } ] }
                    ]
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
