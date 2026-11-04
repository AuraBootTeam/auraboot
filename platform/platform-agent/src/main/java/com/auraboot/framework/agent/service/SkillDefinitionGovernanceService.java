package com.auraboot.framework.agent.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.agent.dto.SkillResult;
import com.auraboot.framework.versioning.dto.DesignVersionDTO;
import com.auraboot.framework.versioning.service.VersionHistoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Skill publication uses the platform history store without overwriting the editable draft. */
@Service
@RequiredArgsConstructor
public class SkillDefinitionGovernanceService {
    private final JdbcTemplate jdbc;
    private final VersionHistoryService history;
    private final AgentSkillService skills;
    private final ObjectMapper json;
    private final com.auraboot.framework.permission.service.UserPermissionService permissions;

    @Transactional(readOnly = true)
    public Map<String, Object> inspect(String pid) {
        Map<String, Object> row = ownRow(pid, false);
        Map<String, Object> draft = SkillDefinitionSnapshot.capture(row, json);
        Map<String, Object> published = SkillDefinitionSnapshot.publication(row, json);
        Map<String, Object> active = published.isEmpty() ? Map.of()
                : snapshot(pid, Objects.toString(published.get("versionPid"), ""));
        if (!published.isEmpty() && !Objects.equals(published.get("hash"), SkillDefinitionSnapshot.hash(active, json)))
            throw new IllegalStateException("Published skill fingerprint mismatch");
        Map<String, Object> changes = new LinkedHashMap<>();
        for (String field : SkillDefinitionSnapshot.FIELDS) {
            if (!Objects.equals(active.get(field), draft.get(field))) {
                Map<String, Object> diff = new LinkedHashMap<>();
                diff.put("published", active.get(field)); diff.put("draft", draft.get(field));
                changes.put(field, diff);
            }
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("draftHash", SkillDefinitionSnapshot.hash(draft, json));
        view.put("publication", published);
        view.put("managed", !published.isEmpty());
        view.put("legacyStatus", row.get("skill_status"));
        view.put("readOnly", Boolean.TRUE.equals(row.get("is_builtin")));
        view.put("validationErrors", validate(draft));
        view.put("changes", changes);
        Map<String, Object> effective = published.isEmpty() ? draft : active;
        Map<String, Object> trialDefinition = new LinkedHashMap<>();
        trialDefinition.put("inputSchema", effective.get("skill_input_schema"));
        trialDefinition.put("executionMode", effective.get("execution_mode"));
        view.put("trialDefinition", trialDefinition);
        view.put("versions", history.getHistory(SkillDefinitionSnapshot.RESOURCE_TYPE, pid));
        // Inputs/outputs are deliberately excluded from trial audit snapshots.
        view.put("trials", history.getHistory("agent-skill-trial", pid).stream().limit(50)
                .map(version -> history.getVersion(version.getPid())).toList());
        return view;
    }

    @Transactional
    public Map<String, Object> publish(String pid, String expectedDraftHash, String expectedPublishedVersion, String description) {
        Map<String, Object> row = ownRow(pid, true);
        assertEditable(row);
        Map<String, Object> draft = SkillDefinitionSnapshot.capture(row, json);
        assertExpected(row, draft, expectedDraftHash, expectedPublishedVersion);
        List<String> errors = validate(draft);
        if (!errors.isEmpty()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, String.join("; ", errors));
        activate(row, draft, "PUBLISH", description);
        return inspect(pid);
    }

    @Transactional
    public Map<String, Object> rollback(String pid, String versionPid, String expectedDraftHash,
                                        String expectedPublishedVersion, String description) {
        Map<String, Object> row = ownRow(pid, true);
        assertEditable(row);
        assertExpected(row, SkillDefinitionSnapshot.capture(row, json), expectedDraftHash, expectedPublishedVersion);
        Map<String, Object> target = snapshot(pid, versionPid);
        if (!Objects.equals(row.get("skill_code"), target.get("skill_code")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Restore the published skill code before rollback");
        // Current tool/permission contracts are revalidated; history never grants permissions.
        List<String> errors = validate(target);
        if (!errors.isEmpty()) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, String.join("; ", errors));
        activate(row, target, "ROLLBACK", description);
        return inspect(pid);
    }

    @Transactional(readOnly = true)
    public DesignVersionDTO version(String pid, String versionPid) {
        ownRow(pid, false);
        return checkedVersion(pid, versionPid);
    }

    private void assertExpected(Map<String, Object> row, Map<String, Object> draft, String expectedHash, String expectedVersion) {
        String actual = Objects.toString(SkillDefinitionSnapshot.publication(row, json).get("versionPid"), "");
        if (expectedHash == null || !expectedHash.equals(SkillDefinitionSnapshot.hash(draft, json))
                || !actual.equals(Objects.toString(expectedVersion, "")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Skill changed; reload and review the current draft and publication");
    }

    private void assertEditable(Map<String, Object> row) {
        if (Boolean.TRUE.equals(row.get("is_builtin")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Built-in skills are read-only");
    }

    private List<String> validate(Map<String, Object> draft) {
        List<String> errors = new ArrayList<>();
        String code = Objects.toString(draft.get("skill_code"), "");
        if (!code.matches("[A-Za-z][A-Za-z0-9_.:-]{0,99}")) errors.add("A stable skill code is required");
        if (Objects.toString(draft.get("skill_name"), "").isBlank()) errors.add("Skill name is required");
        String mode = Objects.toString(draft.get("execution_mode"), "template");
        if (!Set.of("template", "sequential", "orchestration", "dsl_dispatch").contains(mode)) errors.add("Unsupported execution mode");
        Object rawTools = draft.get("skill_tools");
        List<String> codes = new ArrayList<>();
        if (rawTools instanceof List<?> list) {
            for (Object value : list) {
                if (!(value instanceof String tool) || tool.isBlank()) errors.add("Tool codes must be nonempty strings");
                else codes.add(tool);
            }
        } else if (rawTools != null) errors.add("Tool declarations must be an array");
        if (!"dsl_dispatch".equals(mode) && codes.isEmpty()) errors.add("At least one tool is required");
        if ("template".equals(mode) && codes.size() != 1) errors.add("Template mode requires exactly one tool");
        Set<String> resolved = new HashSet<>();
        for (var tool : skills.resolveToolCodes(MetaContext.getCurrentTenantId(), codes)) resolved.add(tool.getName());
        for (String tool : codes) if (!resolved.contains(tool)) errors.add("Tool unavailable to the current caller: " + tool);
        Object rawSchema = draft.get("skill_input_schema");
        if (rawSchema != null) {
            try {
                var schema = json.valueToTree(rawSchema);
                if (!schema.isObject()) errors.add("Input schema must be a JSON object");
                else {
                    rejectExternalReferences(schema);
                    com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012,
                            builder -> builder.schemaLoaders(loaders -> loaders.values(values -> {
                                values.clear();
                                values.add(uri -> { throw new IllegalArgumentException("External skill schema loading is disabled"); });
                            }))).getSchema(schema);
                }
            } catch (RuntimeException e) { errors.add("Input schema is invalid"); }
        }
        for (String field : List.of("max_steps", "timeout_sec")) {
            Object value = draft.get(field);
            if (value != null && (!(value instanceof Number number) || number.intValue() < 1)) errors.add(field + " must be positive");
        }
        return errors;
    }

    private void rejectExternalReferences(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isObject()) {
            for (String key : List.of("$ref", "$dynamicRef"))
                if (node.has(key) && (!node.get(key).isTextual() || !node.get(key).asText().startsWith("#")))
                    throw new IllegalArgumentException("External skill schema references are disabled");
            node.elements().forEachRemaining(this::rejectExternalReferences);
        } else if (node.isArray()) node.elements().forEachRemaining(this::rejectExternalReferences);
    }

    private Map<String, Object> ownRow(String pid, boolean lock) {
        if (!permissions.hasPermission(MetaContext.getCurrentUserId(), "acp_agent_skill"))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Skill authoring access required");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM ab_agent_skill WHERE tenant_id = ? AND pid = ? "
                + "AND deleted_flag = FALSE" + (lock ? " FOR UPDATE" : ""), MetaContext.getCurrentTenantId(), pid);
        if (rows.size() != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill not found");
        return rows.get(0);
    }

    private DesignVersionDTO checkedVersion(String pid, String versionPid) {
        DesignVersionDTO version = history.getVersion(versionPid);
        if (version == null || !SkillDefinitionSnapshot.RESOURCE_TYPE.equals(version.getResourceType())
                || !pid.equals(version.getResourceId()) || version.getSchemaSnapshot() == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Skill version not found");
        return version;
    }
    private Map<String, Object> snapshot(String pid, String versionPid) {
        return json.convertValue(checkedVersion(pid, versionPid).getSchemaSnapshot(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }

    private void activate(Map<String, Object> row, Map<String, Object> definition, String operation, String description) {
        String pid = (String) row.get("pid");
        var version = history.recordVersionWithSnapshot(SkillDefinitionSnapshot.RESOURCE_TYPE, pid,
                json.valueToTree(definition), operation, description);
        Map<String, Object> config = SkillDefinitionSnapshot.jsonObject(row.get("execution_config"), json);
        config.put(SkillDefinitionSnapshot.PUBLICATION, Map.of("versionPid", version.getPid(),
                "hash", SkillDefinitionSnapshot.hash(definition, json), "version", version.getVersion()));
        try {
            int updated = jdbc.update("UPDATE ab_agent_skill SET execution_config = CAST(? AS jsonb), updated_at = CURRENT_TIMESTAMP "
                    + "WHERE tenant_id = ? AND pid = ? AND deleted_flag = FALSE", json.writeValueAsString(config), MetaContext.getCurrentTenantId(), pid);
            if (updated != 1) throw new IllegalStateException("Skill publication lost its owner row");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    /** Store a bounded receipt, not prompts, user inputs, raw tool results or provider configuration. */
    @Transactional
    public void recordTrial(String skillPid, SkillResult result) {
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("runPid", result.getSkillRunPid());
        receipt.put("versionPid", result.getSkillVersionPid());
        receipt.put("versionHash", result.getSkillVersionHash());
        receipt.put("versionLabel", result.getSkillVersionLabel());
        receipt.put("status", result.getStatus());
        receipt.put("durationMs", result.getDurationMs());
        receipt.put("toolCallCount", result.getToolCallCount());
        receipt.put("actionCount", result.getActionCount());
        history.recordVersionWithSnapshot("agent-skill-trial", skillPid, json.valueToTree(receipt),
                "EXECUTE", Objects.toString(result.getStatus(), "UNKNOWN"));
    }
}
