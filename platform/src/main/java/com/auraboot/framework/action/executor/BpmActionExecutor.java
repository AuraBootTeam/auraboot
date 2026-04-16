package com.auraboot.framework.action.executor;

import com.auraboot.framework.bpm.engine.BpmEngine;
import com.auraboot.framework.bpm.engine.dto.ProcessInstanceInfo;
import com.auraboot.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Executes action definitions whose {@code executionMode == "bpm"} by starting
 * a BPM process instance via the {@link BpmEngine} abstraction.
 *
 * <p>Single-direction dependency: {@code framework/action/executor} → {@code framework/bpm},
 * never the reverse.
 *
 * <p>Design choice: This is a standalone Spring component rather than implementing
 * {@link com.auraboot.framework.automation.executor.ActionExecutor}. The reason is
 * that the automation interface uses {@code AutomationAction} (a DSL-persisted entity),
 * while BPM action execution works from raw action-definition maps provided by the
 * frontend action dispatcher. Keeping them separate avoids forcing BPM concerns into
 * the automation entity model. The dispatcher calls {@code execute(Map, Map)} directly.
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BpmActionExecutor {

    /** executionMode value handled by this executor */
    private static final String EXECUTION_MODE_BPM = "bpm";

    private final BpmEngine bpmEngine;

    /**
     * Execute a BPM action definition by starting a process instance.
     *
     * @param actionDef raw action definition map (contains "bpm" sub-config)
     * @param record    the source record used for businessKey + variable extraction
     * @return map with keys: {@code processInstanceId}, {@code processKey}, {@code businessKey}
     * @throws IllegalArgumentException if required config fields are missing
     * @throws BusinessException        if a running instance already exists for the same businessKey
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Object execute(Map<String, Object> actionDef, Map<String, Object> record) {
        Map<String, Object> bpmConfig = (Map<String, Object>) actionDef.get("bpm");
        if (bpmConfig == null) {
            throw new IllegalArgumentException("action.bpm config is required for executionMode=bpm");
        }

        String processKey = requireString(bpmConfig, "processKey");
        String businessKeyField = requireString(bpmConfig, "businessKeyField");

        Object businessKeyVal = record.get(businessKeyField);
        if (businessKeyVal == null) {
            throw new IllegalArgumentException(
                    "Record missing businessKeyField: " + businessKeyField);
        }
        String businessKey = String.valueOf(businessKeyVal);

        // Dedup: reject if a running instance already exists for this businessKey
        if (bpmEngine.hasRunningInstanceForBusinessKey(processKey, businessKey)) {
            throw new BusinessException(
                    "A process instance already exists for businessKey=" + businessKey);
        }

        // Extract variables via simple JSONPath-style "$.<field>" traversal
        // (no jayway dependency needed — all paths reference top-level record fields)
        Map<String, Object> variables = new HashMap<>();
        Object varsConfig = bpmConfig.get("variables");
        if (varsConfig instanceof Map<?, ?> varMap) {
            varMap.forEach((k, v) -> {
                Object extracted = extractVariable(record, String.valueOf(v));
                if (extracted != null) {
                    variables.put(String.valueOf(k), extracted);
                }
            });
        }

        ProcessInstanceInfo info = bpmEngine.startProcess(processKey, businessKey, variables);
        log.info("Started process via action executor: processKey={}, businessKey={}, instanceId={}",
                processKey, businessKey, info.getProcessInstanceId());

        return Map.of(
                "processInstanceId", info.getProcessInstanceId(),
                "processKey", processKey,
                "businessKey", businessKey);
    }

    /**
     * Return true when this executor handles the given executionMode.
     *
     * @param executionMode action executionMode string
     * @return true for "bpm"
     */
    public boolean supports(String executionMode) {
        return EXECUTION_MODE_BPM.equalsIgnoreCase(executionMode);
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private String requireString(Map<String, Object> cfg, String key) {
        Object v = cfg.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalArgumentException("action.bpm." + key + " is required");
        }
        return String.valueOf(v);
    }

    /**
     * Extract a value from the record using a simple path expression.
     *
     * <p>Supports:
     * <ul>
     *   <li>{@code $.fieldName} — top-level field lookup</li>
     *   <li>{@code $.parent.child} — nested field traversal via dot notation</li>
     *   <li>Any non-{@code $} value — returned as a literal</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private Object extractVariable(Map<String, Object> record, String path) {
        if (path == null || !path.startsWith("$")) {
            // Treat as literal value
            return path;
        }
        // Strip leading "$." and split by "."
        String stripped = path.startsWith("$.") ? path.substring(2) : path.substring(1);
        if (stripped.isBlank()) {
            return null;
        }
        String[] parts = stripped.split("\\.");
        Object current = record;
        for (String part : parts) {
            if (current instanceof Map<?, ?> map) {
                current = ((Map<String, Object>) map).get(part);
            } else {
                return null;
            }
        }
        return current;
    }
}
