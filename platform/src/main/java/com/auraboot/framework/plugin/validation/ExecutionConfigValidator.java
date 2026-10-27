package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.meta.constant.CommandTargetVersionPolicy;
import com.auraboot.framework.meta.constant.DslRegistry;
import com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

import static com.auraboot.framework.plugin.validation.PluginValidationMessage.error;
import static com.auraboot.framework.plugin.validation.PluginValidationMessage.warning;

/**
 * S-EXEC: Validates command executionConfig format and content.
 * <p>
 * Checks:
 * - command type is valid (CREATE, UPDATE, DELETE, STATE_TRANSITION, ACTION)
 * - STATE_TRANSITION has required fields (stateField, fromStates/toState or stateTransitionRules)
 * - autoSetFields strategies are valid
 * - preconditions operators are valid
 */
@Component
public class ExecutionConfigValidator implements PluginValidator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Set<String> VALID_EXEC_TYPES = DslRegistry.CommandType.codes();
    private static final Set<String> VALID_AUTO_SET_STRATEGIES = DslRegistry.AutoSetStrategy.codes();
    private static final Set<String> VALID_PRECONDITION_OPERATORS = DslRegistry.PreconditionOperator.codes();

    @Override
    public String category() {
        return "semantic";
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<PluginValidationMessage> validate(PluginValidationContext ctx) {
        List<PluginValidationMessage> messages = new ArrayList<>();
        PluginManifestExtended manifest = ctx.getManifest();

        if (manifest.getCommands() == null) return messages;

        for (int i = 0; i < manifest.getCommands().size(); i++) {
            CommandDefinitionDTO cmd = manifest.getCommands().get(i);
            if (cmd == null) continue;

            // Use the consolidated map which merges executionConfig + DSL flat fields
            Map<String, Object> execConfig = cmd.getConsolidatedExecutionConfig();
            if (execConfig == null) continue;

            String path = "commands[" + i + "]";

            // Preserve all existing modes; the new version-free intent is deliberately bounded.
            try {
                Map<String, Object> declared = new LinkedHashMap<>(execConfig);
                if (cmd.getUnknownFields() != null)
                    cmd.getUnknownFields().forEach(declared::putIfAbsent);
                var policy = CommandTargetVersionPolicy.parse(OBJECT_MAPPER.valueToTree(declared));
                if (policy == CommandTargetVersionPolicy.UNSUPPORTED) {
                    boolean local =
                            cmd.getBindingRules() != null && !cmd.getBindingRules().isEmpty();
                    boolean global =
                            manifest.getBindingRules() != null
                                    && manifest.getBindingRules().stream()
                                            .anyMatch(
                                                    rule ->
                                                            rule != null
                                                                    && Objects.equals(
                                                                            cmd.getCode(),
                                                                            rule.getCommandCode()));
                    if (local || global)
                        throw new IllegalArgumentException(
                                "Unsupported-version intent cannot declare binding rules");
                }
            } catch (IllegalArgumentException invalid) {
                messages.add(
                        error(
                                "S-EXEC-TARGET-VERSION-POLICY",
                                category(),
                                path + ".executionConfig.options.targetVersionPolicy",
                                "Command target version policy is invalid or incompatible with its"
                                    + " execution contract"));
            }

            try {
                com.auraboot.framework.meta.constant.CommandResultCachePolicy.enabled(execConfig);
            } catch (IllegalArgumentException invalid) {
                messages.add(error("S-EXEC-RESULT-CACHE", category(),
                        path + ".executionConfig.cacheResult", invalid.getMessage()));
            }

            // Validate type
            Object type = execConfig.get("type");
            if (type != null && !VALID_EXEC_TYPES.contains(type.toString())) {
                messages.add(error("S-EXEC-TYPE", category(), path + ".type",
                        "Command '" + cmd.getCode() + "' has invalid type: '" +
                                type + "'. Valid: " + VALID_EXEC_TYPES));
            }

            // STATE_TRANSITION should have stateField and (toState or stateTransitionRules)
            // Downgraded to WARNING: many existing commands omit these fields
            // and rely on runtime CommandHandler to manage state transitions directly.
            if ("state_transition".equals(String.valueOf(type))) {
                if (!execConfig.containsKey("stateField")) {
                    messages.add(warning("S-EXEC-ST-FIELD", category(), path,
                            "Command '" + cmd.getCode() + "': STATE_TRANSITION should declare 'stateField'"));
                }
                boolean hasToState = execConfig.containsKey("toState");
                boolean hasRules = execConfig.containsKey("stateTransitionRules");
                if (!hasToState && !hasRules) {
                    messages.add(warning("S-EXEC-ST-TO", category(), path,
                            "Command '" + cmd.getCode() + "': STATE_TRANSITION should declare 'toState' or 'stateTransitionRules'"));
                }
            }

            // Validate autoSetFields strategies
            Object autoSetFields = execConfig.get("autoSetFields");
            if (autoSetFields instanceof Map<?, ?> fieldsMap) {
                for (Map.Entry<?, ?> entry : fieldsMap.entrySet()) {
                    if (entry.getValue() instanceof Map<?, ?> fieldSpec) {
                        Object strategy = fieldSpec.get("strategy");
                        if (strategy != null && !VALID_AUTO_SET_STRATEGIES.contains(strategy.toString())) {
                            messages.add(warning("S-EXEC-AUTOSET", category(),
                                    path + ".autoSetFields." + entry.getKey() + ".strategy",
                                    "Command '" + cmd.getCode() + "' has unknown autoSet strategy: '" +
                                            strategy + "'"));
                        }
                    }
                }
            }

            // Validate preconditions operators
            Object preconditions = execConfig.get("preconditions");
            if (preconditions instanceof List<?> condList) {
                for (int j = 0; j < condList.size(); j++) {
                    if (condList.get(j) instanceof Map<?, ?> cond) {
                        Object op = cond.get("operator");
                        if (op != null && !VALID_PRECONDITION_OPERATORS.contains(op.toString())) {
                            messages.add(warning("S-EXEC-PRECOND", category(),
                                    path + ".preconditions[" + j + "].operator",
                                    "Command '" + cmd.getCode() + "' has unknown precondition operator: '" +
                                            op + "'"));
                        }
                    }
                }
            }
        }

        return messages;
    }
}
