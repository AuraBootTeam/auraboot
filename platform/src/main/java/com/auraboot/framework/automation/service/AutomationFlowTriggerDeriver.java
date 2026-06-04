package com.auraboot.framework.automation.service;

import com.auraboot.framework.automation.entity.TriggerConfig;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Derives trigger fields ({@code triggerType}, {@code modelCode}, {@code triggerConfig})
 * from the {@code flowConfig} saved by the visual automation designer.
 *
 * <p>The visual designer stores all trigger parameters inside the trigger node's
 * {@code data.config} map — it does NOT send flat request fields. This component
 * bridges that gap so designer-created automations actually fire.
 *
 * <p>Node shape (canonical — mirrors {@code AutomationFlowCompiler} which reads
 * {@code node.data.config}):
 * <pre>
 * {
 *   "id": "...",
 *   "type": "trigger-record-create",        // or any other trigger-* type
 *   "data": {
 *     "label": "...",
 *     "config": {
 *       "triggerType": "on_record_create",   // required
 *       "modelCode": "crm_lead",             // optional — absent for scheduled/webhook
 *       ...additional TriggerConfig fields...
 *     }
 *   }
 * }
 * </pre>
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Slf4j
@Component
public class AutomationFlowTriggerDeriver {

    /**
     * Result of deriving trigger fields from a flowConfig.
     *
     * <p>When {@link #isEmpty()} is {@code true} the flowConfig had no nodes (or no
     * trigger node) and the caller should leave the existing flat request fields intact.
     */
    public record DerivedTrigger(
            String triggerType,
            String modelCode,
            TriggerConfig triggerConfig) {

        /** Returns true when derivation found nothing to set (empty/no-nodes flow). */
        public boolean isEmpty() {
            return triggerType == null && modelCode == null && triggerConfig == null;
        }

        /** Convenience factory for "nothing to derive". */
        public static DerivedTrigger empty() {
            return new DerivedTrigger(null, null, null);
        }
    }

    // A private ObjectMapper copy configured to tolerate unknown properties.
    // The node config map carries "triggerType" which TriggerConfig lacks as a field
    // (it lives on the Automation entity, not inside TriggerConfig). We must NOT
    // mutate the shared Spring-managed ObjectMapper's global configuration.
    private final ObjectMapper lenientMapper;

    public AutomationFlowTriggerDeriver(ObjectMapper objectMapper) {
        this.lenientMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Derive trigger fields from a visual-designer {@code flowConfig}.
     *
     * <p>Rules:
     * <ul>
     *   <li>If {@code flowConfig} is null or has no {@code nodes} list → returns
     *       {@link DerivedTrigger#empty()} (legacy flat-field path, no exception).</li>
     *   <li>If {@code nodes} is non-empty but contains <em>no</em> trigger node
     *       (type starts with {@code "trigger"}) → throws {@link ValidationException}
     *       (malformed designer output).</li>
     *   <li>If {@code nodes} contains <em>more than one</em> trigger node →
     *       throws {@link ValidationException} (invalid designer output).</li>
     *   <li>Otherwise reads {@code triggerType} and {@code modelCode} from
     *       {@code node.data.config}, converts the entire config map into a
     *       {@link TriggerConfig} (ignoring unknown properties), and returns a
     *       populated {@link DerivedTrigger}.</li>
     * </ul>
     *
     * @param flowConfig the flowConfig map from the designer save request
     * @return derived trigger fields, or {@link DerivedTrigger#empty()} if nothing to derive
     * @throws ValidationException if the flowConfig is structurally invalid
     */
    @SuppressWarnings("unchecked")
    public DerivedTrigger derive(Map<String, Object> flowConfig) {
        if (flowConfig == null) {
            return DerivedTrigger.empty();
        }

        Object nodesObj = flowConfig.get("nodes");
        if (!(nodesObj instanceof List<?> rawList) || rawList.isEmpty()) {
            // No nodes list or empty — actions-only / empty config, leave flat path intact.
            return DerivedTrigger.empty();
        }

        List<Map<String, Object>> nodes;
        try {
            nodes = (List<Map<String, Object>>) rawList;
        } catch (ClassCastException e) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "flowConfig.nodes must be a list of node objects");
        }

        // Collect all trigger nodes (type starts with "trigger")
        List<Map<String, Object>> triggerNodes = new ArrayList<>();
        for (Map<String, Object> node : nodes) {
            Object typeObj = node.get("type");
            if (typeObj instanceof String type && type.startsWith("trigger")) {
                triggerNodes.add(node);
            }
        }

        if (triggerNodes.isEmpty()) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "flowConfig has nodes but no trigger node (type must start with 'trigger')");
        }
        if (triggerNodes.size() > 1) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "flowConfig must have exactly one trigger node, found " + triggerNodes.size());
        }

        Map<String, Object> triggerNode = triggerNodes.get(0);

        // Read data.config — this is the canonical path used by AutomationFlowCompiler
        Object dataObj = triggerNode.get("data");
        Map<String, Object> data = (dataObj instanceof Map<?, ?>)
                ? (Map<String, Object>) dataObj
                : Map.of();

        Object configObj = data.get("config");
        if (!(configObj instanceof Map<?, ?>)) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "trigger node data.config is missing or not a map");
        }
        Map<String, Object> cfg = (Map<String, Object>) configObj;

        Object triggerTypeObj = cfg.get("triggerType");
        String triggerType = (triggerTypeObj instanceof String s && !s.isBlank()) ? s : null;
        if (triggerType == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "trigger node data.config.triggerType is required but missing");
        }

        Object modelCodeObj = cfg.get("modelCode");
        String modelCode = (modelCodeObj instanceof String s && !s.isBlank()) ? s : null;

        // Convert the entire config map to TriggerConfig, tolerating unknown properties
        // (e.g. "triggerType" lives on Automation, not inside TriggerConfig).
        TriggerConfig triggerConfig;
        try {
            triggerConfig = lenientMapper.convertValue(cfg, TriggerConfig.class);
        } catch (Exception e) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Failed to parse trigger node config: " + e.getMessage());
        }

        log.debug("Derived trigger from flowConfig: triggerType={}, modelCode={}", triggerType, modelCode);
        return new DerivedTrigger(triggerType, modelCode, triggerConfig);
    }
}
