package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.ActionRecord;
import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.dto.FieldChange;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * ACP ActionEngine: records business actions executed by agents.
 *
 * Kernel invariant: "No side effect without Action."
 * Every tool execution (command or query) produces an Action record.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActionRecorder {

    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;
    private final FidelityGrader fidelityGrader;

    /**
     * Record an Action for a dsl_command execution.
     * Called from AgentRunService.executeDslCommandWithAction() after command completes.
     *
     * Model code and operation type are resolved from ab_command_definition (authoritative source),
     * NOT parsed from tool name (which uses namespace:operation_model format, not model_operation).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String recordAction(Long tenantId, String runPid, String commandCode,
                                AgentToolDefinition toolDef, Map<String, Object> input,
                                CommandExecuteResult cmdResult,
                                Map<String, Object> beforeData, Map<String, Object> afterData,
                                String error) {
        try {
            // 1. Resolve model_code and execution type from ab_command_definition (authoritative)
            CommandMeta meta = resolveCommandMeta(tenantId, commandCode);

            // 2. Compute field changes
            List<FieldChange> fieldChanges = computeFieldChanges(beforeData, afterData);

            // 3. Derive action metadata from authoritative command meta
            String actionType = deriveActionType(meta.executionType);
            String transactionScope = deriveTransactionScope(actionType);
            String sideEffectType = deriveSideEffectType(actionType);
            String reversalMode = deriveReversalMode(actionType);
            String businessDomain = deriveBusinessDomain(meta.modelCode);
            boolean isSuccess = error == null;

            // 4. Extract record PID
            String recordPid = extractRecordPid(input, cmdResult, afterData);

            // 5. Build Action record
            String actionPid = UniqueIdGenerator.generate();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pid", actionPid);
            row.put("tenant_id", tenantId);
            row.put("run_id", runPid);
            Integer stepIndex = StepContext.getStepIndex();
            if (stepIndex != null) {
                row.put("step_index", stepIndex);
            }
            // ACP P0-5: stamp parallel-batch coordinates so audits can group
            // Actions emitted from the same LLM parallel tool_use block.
            String parallelGroupId = StepContext.getParallelGroupId();
            Integer parallelIndex = StepContext.getParallelIndex();
            if (parallelGroupId != null) {
                row.put("parallel_group_id", parallelGroupId);
            }
            if (parallelIndex != null) {
                row.put("parallel_index", parallelIndex);
            }
            row.put("action_code", meta.modelCode + "." + meta.executionType);
            row.put("action_type", actionType);
            row.put("transaction_scope", transactionScope);
            row.put("side_effect_type", sideEffectType);
            row.put("intent_summary", buildIntentSummary(meta, input, beforeData, afterData));
            row.put("business_domain", businessDomain);
            row.put("business_operation", meta.modelCode + "_" + meta.executionType);
            row.put("target_model", meta.modelCode);
            row.put("target_record_id", recordPid);
            row.put("affected_count", 1);
            row.put("command_code", commandCode);
            row.put("command_result", isSuccess ? "success" : "failed");
            row.put("risk_level", toolDef != null ? toolDef.getRiskLevel() : "L1");
            row.put("estimated_risk", toolDef != null ? toolDef.getRiskLevel() : "L1");
            row.put("risk_deviation", false);
            row.put("reversal_mode", reversalMode);
            row.put("action_status", isSuccess ? "success" : "failed");
            row.put("error_message", error);
            row.put("actor_type", "agent");
            row.put("executed_at", LocalDateTime.now());
            row.put("created_at", LocalDateTime.now());

            // v1.1 Action Contract (specs/01 §1.3)
            String toolType = toolDef != null ? toolDef.getToolType() : "dsl_command";
            row.put("fidelity", fidelityGrader.grade(toolType));
            if (toolDef != null) {
                row.put("tool_ref", toolDef.getName());
            }
            com.auraboot.framework.agent.dto.BusinessIntentFrame bif = BifContext.getCurrentBif();
            if (bif != null && bif.getCandidateSkills() != null && !bif.getCandidateSkills().isEmpty()) {
                row.put("skill_code", bif.getCandidateSkills().get(0));
            }
            String signature = fidelityGrader.commandSignature(commandCode, input);
            if (signature != null) {
                row.put("command_signature", signature);
            }
            if (fieldChanges != null && !fieldChanges.isEmpty()) {
                row.put("change_summary", buildChangeSummary(fieldChanges));
            }

            // JSONB fields
            if (beforeData != null) {
                row.put("before_snapshot", objectMapper.writeValueAsString(filterSnapshotFields(beforeData)));
            }
            if (afterData != null) {
                row.put("after_snapshot", objectMapper.writeValueAsString(filterSnapshotFields(afterData)));
            }
            if (fieldChanges != null && !fieldChanges.isEmpty()) {
                row.put("field_changes", objectMapper.writeValueAsString(fieldChanges));
            }

            // 6. INSERT with JSONB awareness
            Set<String> jsonbColumns = Set.of("before_snapshot", "after_snapshot", "field_changes",
                    "target_record_ids", "affected_entities", "artifact_refs");
            dynamicDataMapper.insertWithJsonb("ab_agent_action", row, jsonbColumns);

            log.info("Action recorded: pid={}, code={}.{}, model={}, status={}",
                    actionPid, meta.modelCode, meta.executionType, meta.modelCode, isSuccess ? "success" : "failed");
            return actionPid;

        } catch (Exception e) {
            log.error("Failed to record action for command {}: {}", commandCode, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Record a lightweight read Action for dsl_query execution.
     * No before/after snapshots needed.
     * queryCode is the NamedQuery code (e.g., crm_lead_list), which maps to model via ab_meta_named_query.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String recordReadAction(Long tenantId, String runPid, String queryCode,
                                    AgentToolDefinition toolDef, Map<String, Object> input,
                                    int resultCount, String error) {
        try {
            // Resolve model from NQ definition or tool source
            String modelCode = resolveNqModelCode(tenantId, queryCode);
            String actionPid = UniqueIdGenerator.generate();
            boolean isSuccess = error == null;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pid", actionPid);
            row.put("tenant_id", tenantId);
            row.put("run_id", runPid);
            Integer stepIndex = StepContext.getStepIndex();
            if (stepIndex != null) {
                row.put("step_index", stepIndex);
            }
            String parallelGroupId = StepContext.getParallelGroupId();
            Integer parallelIndex = StepContext.getParallelIndex();
            if (parallelGroupId != null) {
                row.put("parallel_group_id", parallelGroupId);
            }
            if (parallelIndex != null) {
                row.put("parallel_index", parallelIndex);
            }
            row.put("action_code", modelCode + ".query");
            row.put("action_type", "read");
            row.put("transaction_scope", "read_only");
            row.put("intent_summary", "Query " + modelCode + " (" + resultCount + " results)");
            row.put("business_domain", deriveBusinessDomain(modelCode));
            row.put("business_operation", modelCode + "_query");
            row.put("target_model", modelCode);
            row.put("affected_count", resultCount);
            row.put("command_code", queryCode);
            row.put("command_result", isSuccess ? "success" : "failed");
            row.put("risk_level", "L0");
            row.put("reversal_mode", "irreversible");
            row.put("action_status", isSuccess ? "success" : "failed");
            row.put("error_message", error);
            row.put("actor_type", "agent");
            row.put("executed_at", LocalDateTime.now());
            row.put("created_at", LocalDateTime.now());

            // v1.1 Action Contract (specs/01 §1.3) — reads grade as semantic:
            // no side effect → no before/after diff to reconstruct.
            row.put("fidelity", fidelityGrader.grade(toolDef != null ? toolDef.getToolType() : "dsl_query"));
            if (toolDef != null) {
                row.put("tool_ref", toolDef.getName());
            }
            com.auraboot.framework.agent.dto.BusinessIntentFrame bif = BifContext.getCurrentBif();
            if (bif != null && bif.getCandidateSkills() != null && !bif.getCandidateSkills().isEmpty()) {
                row.put("skill_code", bif.getCandidateSkills().get(0));
            }
            String signature = fidelityGrader.commandSignature(queryCode, input);
            if (signature != null) {
                row.put("command_signature", signature);
            }

            dynamicDataMapper.insert("ab_agent_action", row);
            log.info("Read action recorded: pid={}, model={}, count={}", actionPid, modelCode, resultCount);
            return actionPid;

        } catch (Exception e) {
            log.error("Failed to record read action for query {}: {}", queryCode, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Read a record's current state by PID from its dynamic table.
     * Used for before/after snapshot capture.
     */
    public Map<String, Object> readRecordByPid(String modelCode, String recordPid) {
        if (modelCode == null || recordPid == null) return null;
        try {
            String tableName = "mt_" + modelCode;
            String sql = "SELECT * FROM " + tableName + " WHERE pid = #{params.recordPid} LIMIT 1";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql, Map.of("recordPid", recordPid));
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            log.debug("Cannot read record {}.{}: {}", modelCode, recordPid, e.getMessage());
            return null;
        }
    }

    // ========== Internal helpers ==========

    static class CommandMeta {
        String modelCode;
        String executionType;   // create | update | delete | state_transition | query
        String commandCode;
    }

    /**
     * Resolve model_code and execution type from ab_command_definition (authoritative source).
     * This is the ONLY correct way to get model info — never parse from tool name.
     */
    private CommandMeta resolveCommandMeta(Long tenantId, String commandCode) {
        CommandMeta meta = new CommandMeta();
        meta.commandCode = commandCode;

        String sql = "SELECT model_code, execution_config FROM ab_command_definition " +
                "WHERE tenant_id = #{params.tenantId} AND code = #{params.code} LIMIT 1";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId, "code", commandCode));

        if (!rows.isEmpty()) {
            Map<String, Object> row = rows.get(0);
            meta.modelCode = (String) row.get("model_code");

            // Parse execution_config.type
            Object execConfig = row.get("execution_config");
            if (execConfig != null) {
                try {
                    Map<String, Object> config;
                    if (execConfig instanceof String s) {
                        config = objectMapper.readValue(s, Map.class);
                    } else if (execConfig instanceof Map<?, ?> m) {
                        config = (Map<String, Object>) m;
                    } else {
                        config = Map.of();
                    }
                    meta.executionType = (String) config.getOrDefault("type", "update");
                } catch (Exception e) {
                    meta.executionType = "update";
                }
            } else {
                meta.executionType = "update";
            }
        } else {
            log.warn("Command not found in ab_command_definition: {}", commandCode);
            meta.modelCode = "unknown";
            meta.executionType = "unknown";
        }

        return meta;
    }

    /**
     * Resolve model_code from NamedQuery's from_sql.
     * Extracts mt_{model_code} table name from the SQL, then strips the mt_ prefix.
     */
    private String resolveNqModelCode(Long tenantId, String queryCode) {
        try {
            String sql = "SELECT from_sql FROM ab_named_query " +
                    "WHERE tenant_id = #{params.tenantId} AND code = #{params.code} LIMIT 1";
            List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                    Map.of("tenantId", tenantId, "code", queryCode));

            if (!rows.isEmpty()) {
                String fromSql = (String) rows.get(0).get("from_sql");
                if (fromSql != null) {
                    // Extract mt_xxx from SQL (e.g., "SELECT ... FROM mt_crm_lead WHERE ...")
                    String upper = fromSql.toUpperCase();
                    int mtIdx = upper.indexOf("MT_");
                    if (mtIdx >= 0) {
                        String rest = fromSql.substring(mtIdx + 3);
                        // Take until whitespace or non-identifier char
                        int end = 0;
                        while (end < rest.length() && (Character.isLetterOrDigit(rest.charAt(end)) || rest.charAt(end) == '_')) {
                            end++;
                        }
                        if (end > 0) {
                            return rest.substring(0, end).toLowerCase();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Cannot resolve NQ model for {}: {}", queryCode, e.getMessage());
        }

        // Fallback: strip common suffixes
        for (String suffix : List.of("_list", "_stats", "_summary", "_detail", "_count", "_search",
                "_pipeline_stats", "_source_distribution", "_conversion")) {
            if (queryCode.endsWith(suffix)) {
                return queryCode.substring(0, queryCode.length() - suffix.length());
            }
        }
        return queryCode;
    }

    /**
     * One-line human-readable summary of what changed, for audit reports.
     * Example: "3 fields changed: status (draft → active), owner, budget"
     */
    private String buildChangeSummary(List<FieldChange> changes) {
        if (changes == null || changes.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append(changes.size()).append(changes.size() == 1 ? " field changed: " : " fields changed: ");
        int limit = Math.min(3, changes.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(", ");
            FieldChange c = changes.get(i);
            sb.append(c.getFieldCode());
            if (c.getOldValue() != null || c.getNewValue() != null) {
                sb.append(" (").append(truncate(c.getOldValue()))
                        .append(" → ").append(truncate(c.getNewValue())).append(")");
            }
        }
        if (changes.size() > limit) sb.append(", …");
        return sb.toString();
    }

    private String truncate(Object v) {
        if (v == null) return "null";
        String s = v.toString();
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private List<FieldChange> computeFieldChanges(Map<String, Object> before, Map<String, Object> after) {
        if (before == null || after == null) return List.of();

        List<FieldChange> changes = new ArrayList<>();
        Set<String> skipFields = Set.of("id", "pid", "tenant_id", "created_at", "updated_at",
                "created_by", "updated_by", "deleted_flag");

        for (Map.Entry<String, Object> entry : after.entrySet()) {
            String key = entry.getKey();
            if (skipFields.contains(key)) continue;

            Object newVal = entry.getValue();
            Object oldVal = before.get(key);
            if (!Objects.equals(oldVal, newVal)) {
                changes.add(FieldChange.builder()
                        .fieldCode(key)
                        .oldValue(oldVal)
                        .newValue(newVal)
                        .build());
            }
        }
        return changes;
    }

    private String extractRecordPid(Map<String, Object> input, CommandExecuteResult cmdResult,
                                     Map<String, Object> afterData) {
        // Try input first (update/delete/transition)
        if (input != null) {
            Object pid = input.get("recordPid");
            if (pid == null) pid = input.get("pid");
            if (pid == null) pid = input.get("id");
            if (pid != null) return pid.toString();
        }

        // For CREATE: extract from command result or afterData
        if (cmdResult != null && cmdResult.getData() != null) {
            Object pid = cmdResult.getData().get("pid");
            if (pid == null) pid = cmdResult.getData().get("id");
            if (pid != null) return pid.toString();
        }

        if (afterData != null) {
            Object pid = afterData.get("pid");
            if (pid != null) return pid.toString();
        }

        return null;
    }

    private Map<String, Object> filterSnapshotFields(Map<String, Object> data) {
        if (data == null) return null;
        // Keep only business fields, exclude system metadata
        Map<String, Object> filtered = new LinkedHashMap<>();
        Set<String> excludeFields = Set.of("created_at", "updated_at", "created_by", "updated_by", "deleted_flag");
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (!excludeFields.contains(entry.getKey())) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }

    private String deriveActionType(String executionType) {
        return switch (executionType) {
            case "create" -> "create";
            case "update" -> "update";
            case "delete" -> "delete";
            case "state_transition" -> "transition";
            case "bulk_update" -> "bulk_update";
            case "bulk_delete" -> "bulk_delete";
            case "query" -> "read";
            default -> "update";
        };
    }

    private String deriveTransactionScope(String actionType) {
        return switch (actionType) {
            case "read" -> "read_only";
            case "bulk_update", "bulk_delete" -> "bulk_records";
            default -> "single_record";
        };
    }

    private String deriveSideEffectType(String actionType) {
        return switch (actionType) {
            case "transition" -> "workflow_transition";
            case "read" -> null;
            default -> "state_change";
        };
    }

    private String deriveReversalMode(String actionType) {
        return switch (actionType) {
            case "read" -> "irreversible";
            case "create", "update" -> "auto_undo";
            case "delete" -> "irreversible";
            case "transition" -> "manual_compensate";
            default -> "auto_undo";
        };
    }

    private String deriveBusinessDomain(String modelCode) {
        if (modelCode == null) return null;
        if (modelCode.startsWith("crm_")) return "crm";
        if (modelCode.startsWith("pm_")) return "project";
        if (modelCode.startsWith("hr_") || modelCode.startsWith("thr_")) return "hr";
        if (modelCode.startsWith("fin_") || modelCode.startsWith("cc_")) return "finance";
        if (modelCode.startsWith("inv_") || modelCode.startsWith("wh_")) return "inventory";
        if (modelCode.startsWith("qc_") || modelCode.startsWith("dp_")) return "quality";
        if (modelCode.startsWith("proc_") || modelCode.startsWith("po_")) return "procurement";
        if (modelCode.startsWith("doc_") || modelCode.startsWith("kb_")) return "knowledge";
        return "general";
    }

    private String buildIntentSummary(CommandMeta meta, Map<String, Object> input,
                                       Map<String, Object> before, Map<String, Object> after) {
        String model = meta.modelCode;
        String op = meta.executionType;

        return switch (op) {
            case "create" -> "Create new " + model + " record";
            case "update" -> "Update " + model + " record";
            case "delete" -> "Delete " + model + " record";
            case "state_transition" -> {
                String fromState = before != null ? findStateField(before) : "?";
                String toState = after != null ? findStateField(after) : "?";
                yield "Transition " + model + " from " + fromState + " to " + toState;
            }
            case "query" -> "Query " + model;
            default -> op + " " + model;
        };
    }

    private String findStateField(Map<String, Object> data) {
        // Try common state field names
        for (String field : List.of("status", "state", "stage")) {
            for (String key : data.keySet()) {
                if (key.endsWith("_" + field) || key.equals(field)) {
                    Object val = data.get(key);
                    if (val != null) return val.toString();
                }
            }
        }
        return "unknown";
    }
}
