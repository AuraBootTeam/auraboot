package com.auraboot.framework.action.executor;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.service.ProcessEngineService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.model.instance.InstanceStatus;
import com.auraboot.smart.framework.engine.model.instance.ProcessInstance;
import com.auraboot.smart.framework.engine.service.param.query.ProcessInstanceQueryParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes action definitions whose {@code executionMode == "bpm"} by starting
 * a BPM process instance via {@link ProcessEngineService}. This is a thin trigger
 * shell — variable extraction + duplicate-business-key check + delegation. All
 * tenant injection, initiator wiring, audit, and form-binding-snapshot logic
 * lives in ProcessEngineService.
 *
 * <p>Accepted action.bpm config shape:
 * <pre>{@code
 * {
 *   "executionMode": "bpm",
 *   "bpm": {
 *     "processKey": "<required string>",
 *     "businessKeyField": "<required string — record field name>",
 *     "variables": { "varName": "$.recordField", ... }   // optional; jsonpath only
 *   }
 * }
 * }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BpmActionExecutor {

    private static final String EXECUTION_MODE_BPM = "bpm";

    private final ProcessEngineService processEngineService;
    private final SmartEngine smartEngine;

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
        if (businessKey.isBlank()) {
            throw new IllegalArgumentException(
                    "businessKey resolved to blank for field: " + businessKeyField);
        }

        if (hasRunningInstance(processKey, businessKey)) {
            throw new BusinessException(
                    "A process instance already exists for businessKey=" + businessKey);
        }

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

        ProcessInstance instance = processEngineService.startProcess(processKey, businessKey, variables);
        log.info("Started process via action executor: processKey={}, businessKey={}, instanceId={}",
                processKey, businessKey, instance.getInstanceId());

        return Map.of(
                "processInstanceId", instance.getInstanceId(),
                "processKey", processKey,
                "businessKey", businessKey);
    }

    public boolean supports(String executionMode) {
        return EXECUTION_MODE_BPM.equalsIgnoreCase(executionMode);
    }

    /** Return true when a non-completed process instance exists for the (processKey, businessKey). */
    private boolean hasRunningInstance(String processKey, String businessKey) {
        ProcessInstanceQueryParam param = new ProcessInstanceQueryParam();
        param.setTenantId(MetaContext.getCurrentTenantIdAsString());
        param.setBizUniqueId(businessKey);
        List<ProcessInstance> instances = smartEngine.getProcessQueryService().findList(param);
        if (instances == null) return false;
        return instances.stream()
                .filter(i -> processKey.equals(i.getProcessDefinitionId()))
                .anyMatch(i -> InstanceStatus.running == i.getStatus() && !i.isSuspend());
    }

    private String requireString(Map<String, Object> cfg, String key) {
        Object v = cfg.get(key);
        if (v == null || String.valueOf(v).isBlank()) {
            throw new IllegalArgumentException("action.bpm." + key + " is required");
        }
        return String.valueOf(v);
    }

    /**
     * Extract a value from the record using a strict path expression.
     *
     * <p>Supported:
     * <ul>
     *   <li>{@code $.field}</li>
     *   <li>{@code $.parent.child}</li>
     *   <li>Any non-{@code $}-prefixed value — treated as literal</li>
     * </ul>
     *
     * <p>Rejected (no silent fallback):
     * <ul>
     *   <li>Bracket syntax: {@code $.list[0]}, {@code $..filter[*]}</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private Object extractVariable(Map<String, Object> record, String path) {
        if (path == null) return null;
        if (!path.startsWith("$")) {
            return path;
        }
        if (path.indexOf('[') >= 0) {
            throw new IllegalArgumentException(
                    "JSONPath bracket syntax not supported: " + path
                    + " — use simple dot paths only ($.field or $.parent.child)");
        }
        String stripped = path.startsWith("$.") ? path.substring(2) : path.substring(1);
        if (stripped.isBlank()) {
            return null;
        }
        Object current = record;
        for (String part : stripped.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = ((Map<String, Object>) map).get(part);
            } else {
                return null;
            }
        }
        return current;
    }
}
