package com.auraboot.framework.permission.service;

import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Derives permission actions from a model's command definitions.
 *
 * <p>Mapping rules:
 * <ul>
 *   <li>exec_type=create → action "create"</li>
 *   <li>exec_type=update → action "update"</li>
 *   <li>exec_type=delete → action "delete"</li>
 *   <li>exec_type=query → skip (covered by "read")</li>
 *   <li>exec_type=state_transition/custom/action → extract verb from command code</li>
 * </ul>
 * <p>"read" is always included regardless of commands.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommandActionDeriver {

    private static final Set<String> STANDARD_EXEC_TYPES = Set.of("create", "update", "delete");
    private static final Set<String> SKIP_EXEC_TYPES = Set.of("query");
    private static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() {};

    private final CommandDefinitionMapper commandDefinitionMapper;
    private final ObjectMapper objectMapper;

    /**
     * Derive the set of permission actions for a given model code.
     * Always includes "read". Standard CRUD exec types map directly.
     * Other exec types (state_transition, custom, action) extract the verb from the command code.
     *
     * @param modelCode the model code to derive actions for
     * @return ordered list of unique action strings
     */
    public List<String> deriveActions(String modelCode) {
        Set<String> actions = new LinkedHashSet<>();
        actions.add("read");

        List<CommandDefinition> commands = commandDefinitionMapper.findByModelCode(modelCode);
        if (commands == null || commands.isEmpty()) {
            return new ArrayList<>(actions);
        }

        for (CommandDefinition cmd : commands) {
            String execType = extractExecType(cmd);
            if (execType == null || SKIP_EXEC_TYPES.contains(execType)) {
                continue;
            }

            if (STANDARD_EXEC_TYPES.contains(execType)) {
                actions.add(execType);
            } else {
                String verb = extractVerb(cmd.getCode(), modelCode);
                if (verb != null && !verb.isBlank()) {
                    actions.add(verb);
                }
            }
        }

        return new ArrayList<>(actions);
    }

    private String extractExecType(CommandDefinition cmd) {
        String configJson = cmd.getExecutionConfig();
        if (configJson == null || configJson.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> config = objectMapper.readValue(configJson, MAP_TYPE_REF);
            Object type = config.get("type");
            return type != null ? type.toString().toLowerCase() : null;
        } catch (Exception e) {
            log.warn("Failed to parse executionConfig for command {}: {}", cmd.getCode(), e.getMessage());
            return null;
        }
    }

    /**
     * Extract verb from command code.
     * E.g., "crm:qualify_lead" with modelCode "crm_lead" → "qualify"
     */
    private String extractVerb(String commandCode, String modelCode) {
        if (commandCode == null) {
            return null;
        }
        // Strip namespace: "crm:qualify_lead" → "qualify_lead"
        String withoutNs = commandCode.contains(":")
                ? commandCode.substring(commandCode.indexOf(':') + 1)
                : commandCode;

        // Try to strip model name suffix
        String verb = withoutNs;
        String[] modelParts = modelCode.split("_");
        for (int i = 0; i < modelParts.length; i++) {
            String suffix = "_" + String.join("_", Arrays.copyOfRange(modelParts, i, modelParts.length));
            if (withoutNs.endsWith(suffix) && withoutNs.length() > suffix.length()) {
                verb = withoutNs.substring(0, withoutNs.length() - suffix.length());
                break;
            }
        }

        // Skip if verb is a standard CRUD type (already handled by exec_type)
        if (STANDARD_EXEC_TYPES.contains(verb) || SKIP_EXEC_TYPES.contains(verb)) {
            return null;
        }

        return verb;
    }
}
