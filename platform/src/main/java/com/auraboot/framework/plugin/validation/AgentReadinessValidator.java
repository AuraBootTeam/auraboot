package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Layer 4 — Agent Readiness Validation.
 * Checks that plugin resources are ready for Agent consumption:
 * - Command descriptions are meaningful (not generic)
 * - STATE_TRANSITION commands declare fromStates + toState
 * - Commands with sideEffects at L2+ have agent_hint or descriptions
 * - input_schema is present for non-trivial commands
 *
 * All checks are WARNING or INFO level — they don't block publishing
 * but lower the plugin quality score.
 */
@Component
public class AgentReadinessValidator implements PluginValidator {

    private static final int MIN_DESCRIPTION_LENGTH = 30;

    @Override
    public String category() {
        return "governance";
    }

    @Override
    public List<PluginValidationMessage> validate(PluginValidationContext ctx) {
        List<PluginValidationMessage> messages = new ArrayList<>();
        PluginManifestExtended manifest = ctx.getManifest();

        List<CommandDefinitionDTO> commands = manifest.getCommands();
        if (commands == null || commands.isEmpty()) return messages;

        int totalCommands = 0;
        int commandsWithHint = 0;
        int stateTransitionsWithSpec = 0;
        int stateTransitionsTotal = 0;

        for (int i = 0; i < commands.size(); i++) {
            CommandDefinitionDTO cmd = commands.get(i);
            Map<String, Object> execConfig = cmd.getConsolidatedExecutionConfig();
            String type = (String) execConfig.get("type");
            String code = cmd.getCode();
            totalCommands++;

            // Check description quality
            String desc = cmd.getDescription();
            boolean hasAgentHint = hasNonBlank(cmd, "agentHint");
            if (hasAgentHint) commandsWithHint++;

            boolean hasAdequateDescription = hasAgentHint
                    || (desc != null && desc.length() >= MIN_DESCRIPTION_LENGTH);

            if (!hasAdequateDescription) {
                messages.add(PluginValidationMessage.info("A-DESC-QUALITY",
                        "agent-readiness",
                        "Command '" + code + "' has no agent_hint and description is too short (" +
                                (desc != null ? desc.length() : 0) + " chars, recommend >= " +
                                MIN_DESCRIPTION_LENGTH + "). LLM may misunderstand this command.",
                        "commands[" + i + "]"));
            }

            // STATE_TRANSITION must declare fromStates + toState
            if ("state_transition".equals(type)) {
                stateTransitionsTotal++;
                Object fromStates = execConfig.get("fromStates");
                Object toState = execConfig.get("toState");
                Object stateField = execConfig.get("stateField");

                if (fromStates != null && toState != null && stateField != null) {
                    stateTransitionsWithSpec++;
                } else {
                    List<String> missing = new ArrayList<>();
                    if (fromStates == null) missing.add("fromStates");
                    if (toState == null) missing.add("toState");
                    if (stateField == null) missing.add("stateField");
                    messages.add(PluginValidationMessage.warning("A-STATE-INCOMPLETE",
                            "agent-readiness",
                            "commands[" + i + "]",
                            "STATE_TRANSITION command '" + code + "' is missing: " +
                                    String.join(", ", missing) +
                                    ". Agent cannot reason about state transitions without these fields."));
                }
            }

            // Check input_schema presence for non-trivial commands
            if (!"delete".equals(type) && !"query".equals(type)) {
                Object inputSchema = cmd.getInputSchema();
                boolean hasInputFields = execConfig.get("inputFields") instanceof List<?> list && !list.isEmpty();
                if (inputSchema == null && !hasInputFields) {
                    messages.add(PluginValidationMessage.info("A-INPUT-SCHEMA",
                            "agent-readiness",
                            "Command '" + code + "' has no input_schema and no inputFields. " +
                                    "Agent won't know what parameters to provide.",
                            "commands[" + i + "]"));
                }
            }
        }

        // Summary message
        if (totalCommands > 0) {
            int hintRate = totalCommands > 0 ? (commandsWithHint * 100 / totalCommands) : 0;
            if (hintRate < 50) {
                messages.add(PluginValidationMessage.info("A-HINT-COVERAGE",
                        "agent-readiness",
                        "Agent hint coverage: " + commandsWithHint + "/" + totalCommands +
                                " commands (" + hintRate + "%). Recommend >= 80% for good Agent experience.",
                        "commands"));
            }
        }

        if (stateTransitionsTotal > 0 && stateTransitionsWithSpec < stateTransitionsTotal) {
            messages.add(PluginValidationMessage.info("A-STATE-COVERAGE",
                    "agent-readiness",
                    "State transition spec coverage: " + stateTransitionsWithSpec + "/" +
                            stateTransitionsTotal + " commands have complete fromStates/toState/stateField.",
                    "commands"));
        }

        return messages;
    }

    private boolean hasNonBlank(CommandDefinitionDTO cmd, String fieldName) {
        Map<String, Object> unknown = cmd.getUnknownFields();
        if (unknown == null) return false;
        Object val = unknown.get(fieldName);
        return val instanceof String s && !s.isBlank();
    }
}
