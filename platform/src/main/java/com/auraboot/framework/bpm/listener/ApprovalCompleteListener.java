package com.auraboot.framework.bpm.listener;

import com.alibaba.smart.framework.engine.SmartEngine;
import com.alibaba.smart.framework.engine.model.instance.VariableInstance;
import com.auraboot.framework.bpm.event.BpmEvent;
import com.auraboot.framework.meta.service.DynamicDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Listens for BPM process completion events and updates the corresponding
 * dynamic model record status based on the approval outcome.
 *
 * <p>When a process ends, this listener:
 * <ol>
 *   <li>Checks if the event is a PROCESS_ENDED event</li>
 *   <li>Looks up process variables to find businessKey and stateField</li>
 *   <li>Parses businessKey (format: "modelCode:recordId")</li>
 *   <li>Determines outcome from process variables (approved/rejected)</li>
 *   <li>Updates the record's state field accordingly</li>
 * </ol>
 *
 * <p>The businessKey is set by {@link com.auraboot.framework.meta.handler.BuiltinStartApprovalHandler}
 * in format "modelCode:recordId". Process variables include:
 * <ul>
 *   <li>{@code businessKey} — modelCode:recordId</li>
 *   <li>{@code stateField} — the field name to update on the record</li>
 *   <li>{@code approvalOutcome} — "approved" or "rejected" (set by approval task)</li>
 * </ul>
 *
 * @author AuraBoot Team
 * @since 6.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalCompleteListener {

    private static final String APPROVED_STATE = "approved";
    private static final String REJECTED_STATE = "rejected";

    private final DynamicDataService dynamicDataService;
    private final SmartEngine smartEngine;

    @EventListener
    public void onProcessEnded(BpmEvent event) {
        if (!"process_ended".equals(event.getBpmEventType())) {
            return;
        }

        String instanceId = event.getInstanceId();
        if (!StringUtils.hasText(instanceId)) {
            log.debug("Skipping PROCESS_ENDED event without instanceId");
            return;
        }

        try {
            // Look up process variables to find businessKey and stateField
            Map<String, Object> variables = getProcessVariables(instanceId);

            String businessKey = getStringVariable(variables, "businessKey");
            if (!StringUtils.hasText(businessKey) || !businessKey.contains(":")) {
                log.debug("Skipping PROCESS_ENDED event without valid businessKey: instanceId={}, businessKey={}",
                        instanceId, businessKey);
                return;
            }

            String stateField = getStringVariable(variables, "stateField");
            if (!StringUtils.hasText(stateField)) {
                log.debug("No stateField in process variables, skipping status update: instanceId={}", instanceId);
                return;
            }

            // Parse businessKey: "modelCode:recordId"
            String[] parts = businessKey.split(":", 2);
            String modelCode = parts[0];
            String recordId = parts[1];

            // Determine approval outcome
            String outcome = getStringVariable(variables, "approvalOutcome");
            String newState;
            if ("rejected".equalsIgnoreCase(outcome)) {
                newState = REJECTED_STATE;
            } else {
                // Default to APPROVED when process completes normally
                newState = APPROVED_STATE;
            }

            // Update record status
            Map<String, Object> updateData = new HashMap<>();
            updateData.put(stateField, newState);
            dynamicDataService.update(modelCode, recordId, updateData);

            log.info("Approval process completed: instanceId={}, businessKey={}, outcome={}, newState={}",
                    instanceId, businessKey, outcome, newState);

        } catch (Exception e) {
            log.error("Error handling approval completion: instanceId={}", instanceId, e);
        }
    }

    /**
     * Retrieve process variables from SmartEngine by instance ID.
     */
    private Map<String, Object> getProcessVariables(String processInstanceId) {
        Map<String, Object> variables = new HashMap<>();
        try {
            List<VariableInstance> variableInstances = smartEngine.getVariableQueryService()
                    .findProcessInstanceVariableList(processInstanceId);
            if (variableInstances != null) {
                for (VariableInstance vi : variableInstances) {
                    variables.put(vi.getFieldKey(), vi.getFieldValue());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to retrieve process variables: instanceId={}", processInstanceId, e);
        }
        return variables;
    }

    /**
     * Safely get a string value from the variables map.
     */
    private String getStringVariable(Map<String, Object> variables, String key) {
        Object value = variables.get(key);
        return value != null ? value.toString() : null;
    }
}
