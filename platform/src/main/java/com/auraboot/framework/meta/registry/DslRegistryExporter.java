package com.auraboot.framework.meta.registry;

import com.auraboot.framework.meta.constant.DslRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * Exports the full DSL registry (closed enums + open extension registries)
 * as a structured Map or pretty-printed JSON.
 */
@Component
@RequiredArgsConstructor
public class DslRegistryExporter {

    private final ObjectMapper objectMapper;
    private final CommandHandlerRegistry commandHandlerRegistry;
    private final SideEffectHandlerRegistry sideEffectHandlerRegistry;
    private final AutomationActionRegistry automationActionRegistry;
    private final ExpressionFunctionRegistry expressionFunctionRegistry;
    private final RenderComponentRegistry renderComponentRegistry;
    private final BlockRendererRegistry blockRendererRegistry;
    private final ChartTypeRegistry chartTypeRegistry;

    /**
     * Export full registry snapshot.
     */
    public Map<String, Object> export() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", "2.0");
        result.put("exportedAt", Instant.now().toString());
        result.put("enums", buildEnums());
        result.put("extensions", buildExtensions());
        result.put("mappings", buildMappings());
        return result;
    }

    /**
     * Export as pretty-printed JSON string.
     */
    public String exportAsJson() {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(export());
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize DSL registry", e);
        }
    }

    @SuppressWarnings("rawtypes")
    private Map<String, Object> buildEnums() {
        Map<String, Object> enums = new LinkedHashMap<>();

        // Reflectively iterate all inner enum classes implementing DslEnum
        for (Class<?> inner : DslRegistry.class.getDeclaredClasses()) {
            if (inner.isEnum() && DslRegistry.DslEnum.class.isAssignableFrom(inner)) {
                String name = inner.getSimpleName();

                // For ChartType, use the open registry (includes runtime-registered types)
                if ("ChartType".equals(name)) {
                    enums.put(name, chartTypeRegistry.exportEntries());
                    continue;
                }

                List<Map<String, String>> values = new ArrayList<>();
                for (Object constant : inner.getEnumConstants()) {
                    DslRegistry.DslEnum dslEnum = (DslRegistry.DslEnum) constant;
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("code", dslEnum.code());
                    entry.put("label", dslEnum.label());
                    entry.put("since", dslEnum.since());
                    values.add(entry);
                }
                enums.put(name, values);
            }
        }
        return enums;
    }

    private Map<String, Object> buildExtensions() {
        Map<String, Object> extensions = new LinkedHashMap<>();
        extensions.put("commandHandlers", commandHandlerRegistry.exportEntries());
        extensions.put("sideEffectHandlers", sideEffectHandlerRegistry.exportEntries());
        extensions.put("automationActions", automationActionRegistry.exportEntries());
        extensions.put("expressionFunctions", expressionFunctionRegistry.exportEntries());
        extensions.put("renderComponents", renderComponentRegistry.exportEntries());
        extensions.put("blockRenderers", blockRendererRegistry.exportEntries());
        return extensions;
    }

    private Map<String, Object> buildMappings() {
        Map<String, Object> mappings = new LinkedHashMap<>();

        Map<String, String> dataTypeDefaults = new LinkedHashMap<>();
        dataTypeDefaults.put("string", "input");
        dataTypeDefaults.put("text", "textarea");
        dataTypeDefaults.put("integer", "number");
        dataTypeDefaults.put("decimal", "number");
        dataTypeDefaults.put("boolean", "switch");
        dataTypeDefaults.put("date", "date");
        dataTypeDefaults.put("datetime", "datetime");
        dataTypeDefaults.put("json", "code-editor");
        dataTypeDefaults.put("enum", "select");
        dataTypeDefaults.put("reference", "reference-picker");
        dataTypeDefaults.put("computed", "readonly");
        dataTypeDefaults.put("ai_text", "ai-input");
        dataTypeDefaults.put("money", "money-input");

        mappings.put("dataTypeDefaults", dataTypeDefaults);
        return mappings;
    }
}
