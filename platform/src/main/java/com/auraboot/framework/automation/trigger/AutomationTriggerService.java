package com.auraboot.framework.automation.trigger;

import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.automation.entity.AutomationLog;

import java.util.Map;

/**
 * Service for triggering and executing automations
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
public interface AutomationTriggerService {

    /**
     * Handle record creation event
     *
     * @param modelCode model code
     * @param recordId created record ID
     * @param recordData created record data
     */
    void onRecordCreate(String modelCode, String recordId, Map<String, Object> recordData);

    /**
     * Handle record update event
     *
     * @param modelCode model code
     * @param recordId updated record ID
     * @param beforeData data before update
     * @param afterData data after update
     */
    void onRecordUpdate(String modelCode, String recordId,
                        Map<String, Object> beforeData, Map<String, Object> afterData);

    /**
     * Handle field change event
     *
     * @param modelCode model code
     * @param recordId record ID
     * @param fieldCode changed field code
     * @param oldValue old value
     * @param newValue new value
     */
    void onFieldChange(String modelCode, String recordId,
                       String fieldCode, Object oldValue, Object newValue);

    /**
     * Handle state change event
     *
     * @param modelCode model code
     * @param recordId record ID
     * @param fromState previous state
     * @param toState new state
     */
    void onStateChange(String modelCode, String recordId, String fromState, String toState);

    /**
     * Handle BPM event (process started, task completed, etc.)
     *
     * @param eventType BPM event type (e.g. PROCESS_STARTED, TASK_COMPLETED)
     * @param processKey process definition key
     * @param instanceId process instance ID
     * @param payload event payload
     */
    void onBpmEvent(String eventType, String processKey, String instanceId, Map<String, Object> payload);

    /**
     * Execute an automation
     *
     * @param automation the automation to execute
     * @param recordId triggering record ID (may be null for scheduled)
     * @param triggerPayload trigger context data
     * @return execution log
     */
    AutomationLog executeAutomation(Automation automation, String recordId, Map<String, Object> triggerPayload);

    /**
     * Evaluate trigger condition using SpEL
     *
     * @param condition SpEL expression
     * @param context evaluation context
     * @return true if condition is met
     */
    boolean evaluateCondition(String condition, Map<String, Object> context);
}
