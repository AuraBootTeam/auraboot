package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.cloudconfig.entity.CloudConfig;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handlebars-like prompt template engine backed by CloudConfig.
 * <p>
 * Templates are stored in {@code ab_cloud_config} with {@code service_type = 'prompt_template'}
 * and {@code provider_code} as the template code.
 * <p>
 * Supported syntax:
 * <ul>
 *   <li>{@code {{variable}}} — simple variable replacement</li>
 *   <li>{@code {{#if var}}...{{/if}}} — conditional block (truthy check)</li>
 *   <li>{@code {{#each var}}...{{/each}}} — iteration (List or Map)</li>
 *   <li>{@code {{@key}}} / {@code {{this}}} — inside each blocks</li>
 * </ul>
 *
 * @since 6.4.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptTemplateService {

    private static final String SERVICE_TYPE = "prompt_template";

    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{([^#/][^}]*)}}");
    private static final Pattern IF_PATTERN = Pattern.compile(
            "\\{\\{#if\\s+(\\w+)}}(.*?)\\{\\{/if}}", Pattern.DOTALL);
    private static final Pattern EACH_PATTERN = Pattern.compile(
            "\\{\\{#each\\s+(\\w+)}}(.*?)\\{\\{/each}}", Pattern.DOTALL);

    private final CloudConfigService cloudConfigService;
    private final ObjectMapper objectMapper;

    /**
     * Load the raw template string from CloudConfig.
     *
     * @param tenantId     tenant ID for config layering
     * @param templateCode the provider_code identifying the template
     * @return the template string, or null if not found
     */
    public String loadTemplate(Long tenantId, String templateCode) {
        CloudConfig config = cloudConfigService.getEffectiveConfig(tenantId, SERVICE_TYPE, templateCode);
        if (config == null || config.getConfig() == null) {
            log.warn("Prompt template not found: tenantId={}, code={}", tenantId, templateCode);
            return null;
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(config.getConfig(),
                    objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            Object template = parsed.get("template");
            return template != null ? template.toString() : null;
        } catch (Exception e) {
            log.error("Failed to parse prompt template config: code={}", templateCode, e);
            return null;
        }
    }

    /**
     * Render a template with the given variables.
     *
     * @param tenantId     tenant ID for config layering
     * @param templateCode the provider_code identifying the template
     * @param variables    replacement variables
     * @return the rendered string, or null if template not found
     */
    public String render(Long tenantId, String templateCode, Map<String, Object> variables) {
        String template = loadTemplate(tenantId, templateCode);
        if (template == null) {
            return null;
        }
        return renderTemplate(template, variables);
    }

    /**
     * Render a raw template string (no CloudConfig lookup).
     */
    public String renderTemplate(String template, Map<String, Object> variables) {
        if (template == null || variables == null) {
            return template;
        }
        String result = template;
        result = processIfBlocks(result, variables);
        result = processEachBlocks(result, variables);
        result = replaceVariables(result, variables);
        return result;
    }

    // ---- internal processing ----

    private String processIfBlocks(String template, Map<String, Object> variables) {
        Matcher matcher = IF_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1);
            String body = matcher.group(2);
            Object value = variables.get(varName);
            String replacement = isTruthy(value) ? body : "";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String processEachBlocks(String template, Map<String, Object> variables) {
        Matcher matcher = EACH_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1);
            String body = matcher.group(2);
            Object value = variables.get(varName);
            StringBuilder expansion = new StringBuilder();

            if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String line = body;
                    line = line.replace("{{@key}}", String.valueOf(entry.getKey()));
                    line = line.replace("{{this}}", String.valueOf(entry.getValue()));
                    expansion.append(line);
                }
            } else if (value instanceof List<?> list) {
                for (Object item : list) {
                    String line = body;
                    if (item instanceof Map<?, ?> itemMap) {
                        // Replace named fields: {{fieldName}}
                        for (Map.Entry<?, ?> entry : itemMap.entrySet()) {
                            line = line.replace("{{" + entry.getKey() + "}}",
                                    entry.getValue() != null ? String.valueOf(entry.getValue()) : "");
                        }
                    }
                    line = line.replace("{{this}}", String.valueOf(item));
                    expansion.append(line);
                }
            } else {
                log.debug("{{#each {}}} value is not iterable: {}", varName,
                        value != null ? value.getClass().getSimpleName() : "null");
            }

            matcher.appendReplacement(sb, Matcher.quoteReplacement(expansion.toString()));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String replaceVariables(String template, Map<String, Object> variables) {
        Matcher matcher = VAR_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1).trim();
            Object value = variables.get(varName);
            String replacement = value != null ? String.valueOf(value) : "";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private boolean isTruthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return !s.isEmpty();
        if (value instanceof Collection<?> c) return !c.isEmpty();
        if (value instanceof Map<?, ?> m) return !m.isEmpty();
        return true;
    }
}
