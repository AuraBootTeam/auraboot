package com.auraboot.framework.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Generates plugin JSON structures from model specifications.
 * Java equivalent of the CLI `aura dsl scaffold` command for programmatic Agent use.
 *
 * <p>All generated structures are in-memory maps — nothing is persisted.
 * Callers (e.g. AgentRuntimeController) are responsible for persistence if needed.</p>
 */
@Slf4j
@Service
public class PluginScaffoldService {

    // Risk level mapping for command types
    private static final Map<String, String> COMMAND_RISK_MAP = Map.of(
            "create", "L1",
            "update", "L1",
            "delete", "L4",
            "state_transition", "L1"
    );

    /**
     * Generate plugin JSON structures from a model specification.
     *
     * @param modelCode   e.g., "equipment_inspection"
     * @param namespace   e.g., "insp"
     * @param fields      list of maps each containing at minimum: code, dataType,
     *                    and optionally referenceModel
     * @param description human-readable description of the model
     * @return map with keys: model, fields, commands, fieldBindings
     */
    public Map<String, Object> scaffold(String modelCode,
                                        String namespace,
                                        List<Map<String, Object>> fields,
                                        String description) {
        List<Map<String, Object>> fieldDefs = buildFieldDefs(modelCode, fields);
        Map<String, Object> model = buildModel(modelCode, namespace, description);
        List<Map<String, Object>> fieldBindings = buildFieldBindings(modelCode, fieldDefs);
        List<Map<String, Object>> commands = buildCommands(modelCode, namespace, fieldDefs, fields);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", model);
        result.put("fields", fieldDefs);
        result.put("fieldBindings", fieldBindings);
        result.put("commands", commands);
        return result;
    }

    // ──────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────

    private Map<String, Object> buildModel(String modelCode, String namespace, String description) {
        String desc = (description != null && !description.isBlank())
                ? description
                : "Auto-generated model: " + modelCode;

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("code", modelCode);
        model.put("displayName:zh-CN", modelCode);
        model.put("displayName:en", modelCode);
        model.put("description", desc);
        model.put("semantic_description", desc);
        model.put("modelType", "entity");
        model.put("extension", Map.of());
        return model;
    }

    private List<Map<String, Object>> buildFieldDefs(String modelCode,
                                                      List<Map<String, Object>> rawFields) {
        List<Map<String, Object>> defs = new ArrayList<>();
        for (Map<String, Object> raw : rawFields) {
            String code = (String) raw.get("code");
            String dataType = raw.get("dataType") != null
                    ? ((String) raw.get("dataType")).toUpperCase()
                    : "string";
            String referenceModel = (String) raw.get("referenceModel");

            Map<String, Object> field = new LinkedHashMap<>();
            field.put("code", modelCode + "_" + code);
            field.put("displayName:zh-CN", code);
            field.put("displayName:en", code);
            field.put("dataType", dataType);

            Map<String, Object> constraints = new LinkedHashMap<>();
            if ("string".equals(dataType)) {
                constraints.put("maxLength", 200);
            }
            field.put("constraints", constraints);

            Map<String, Object> extension = new LinkedHashMap<>();
            if (referenceModel != null && !referenceModel.isBlank()) {
                extension.put("referenceModel", referenceModel);
            }
            field.put("extension", extension);

            defs.add(field);
        }
        return defs;
    }

    private List<Map<String, Object>> buildFieldBindings(String modelCode,
                                                          List<Map<String, Object>> fieldDefs) {
        List<Map<String, Object>> bindings = new ArrayList<>();
        for (int i = 0; i < fieldDefs.size(); i++) {
            String fieldCode = (String) fieldDefs.get(i).get("code");
            Map<String, Object> displayConfig = new LinkedHashMap<>();
            displayConfig.put("searchable", i < 3);
            displayConfig.put("sortable", i < 3);

            Map<String, Object> binding = new LinkedHashMap<>();
            binding.put("modelCode", modelCode);
            binding.put("fieldCode", fieldCode);
            binding.put("sequence", (i + 1) * 10);
            binding.put("required", i == 0);
            binding.put("visible", true);
            binding.put("editable", true);
            binding.put("displayConfig", displayConfig);
            bindings.add(binding);
        }
        return bindings;
    }

    private List<Map<String, Object>> buildCommands(String modelCode,
                                                     String namespace,
                                                     List<Map<String, Object>> fieldDefs,
                                                     List<Map<String, Object>> rawFields) {
        String shortCode = modelCode.startsWith(namespace + "_")
                ? modelCode.substring(namespace.length() + 1)
                : modelCode;
        String permCode = namespace.toUpperCase() + "." + shortCode + ".manage";

        List<String> fieldCodes = fieldDefs.stream()
                .map(f -> (String) f.get("code"))
                .toList();

        List<Map<String, Object>> commands = new ArrayList<>();

        // CREATE
        commands.add(buildCommand(
                namespace + ":create_" + shortCode,
                "Create " + modelCode,
                "create",
                modelCode,
                fieldCodes,
                permCode,
                "Create a new " + shortCode + " record with all required fields.",
                false,
                false
        ));

        // UPDATE
        commands.add(buildCommand(
                namespace + ":update_" + shortCode,
                "Update " + modelCode,
                "update",
                modelCode,
                fieldCodes,
                permCode,
                "Update an existing " + shortCode + " record by its ID.",
                true,
                true
        ));

        // DELETE
        commands.add(buildCommand(
                namespace + ":delete_" + shortCode,
                "Delete " + modelCode,
                "delete",
                modelCode,
                List.of(),
                permCode,
                "Permanently delete a " + shortCode + " record. This action is irreversible.",
                true,
                false
        ));

        // STATE_TRANSITION if a status/SELECT field exists
        boolean hasStatus = rawFields.stream()
                .anyMatch(f -> "status".equals(f.get("code"))
                        || "select".equalsIgnoreCase((String) f.get("dataType")));
        if (hasStatus) {
            Map<String, Object> stCmd = buildCommand(
                    namespace + ":change_status_" + shortCode,
                    "Change Status " + modelCode,
                    "state_transition",
                    modelCode,
                    List.of(modelCode + "_status"),
                    permCode,
                    "Transition the status of a " + shortCode + " record.",
                    true,
                    true
            );
            commands.add(stCmd);
        }

        return commands;
    }

    private Map<String, Object> buildCommand(String code,
                                              String displayName,
                                              String type,
                                              String modelCode,
                                              List<String> inputFields,
                                              String permCode,
                                              String agentHint,
                                              boolean idempotent,
                                              boolean reversible) {
        String riskLevel = COMMAND_RISK_MAP.getOrDefault(type, "L1");

        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("code", code);
        cmd.put("displayName:zh-CN", displayName);
        cmd.put("displayName:en", displayName);
        cmd.put("type", type);
        cmd.put("modelCode", modelCode);
        cmd.put("inputFields", new ArrayList<>(inputFields));
        cmd.put("permissions", List.of(permCode));
        cmd.put("agent_hint", agentHint);
        cmd.put("cmd_risk_level", riskLevel);
        cmd.put("idempotent", idempotent);
        cmd.put("reversible", reversible);
        return cmd;
    }
}
