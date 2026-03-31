package com.auraboot.framework.automation.listener;

import com.auraboot.framework.automation.trigger.AutomationTriggerService;
import com.auraboot.framework.meta.service.impl.CommandStateCheckExecutor;
import com.auraboot.module.meta.event.CommandCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Bridges {@link CommandCompletedEvent} to {@link AutomationTriggerService}.
 *
 * <p>This is the missing link that makes automations fire when records are
 * created or updated via DSL Commands. Previously, {@code CommandFieldMapExecutor}
 * wrote directly to {@code dynamicDataMapper} and published the event, but no
 * listener forwarded it to the automation engine.
 *
 * <p>Uses {@code @TransactionalEventListener(AFTER_COMMIT)} so automations only
 * run after the command transaction has fully committed — consistent with how
 * the BPM event bridge and the field-change audit listener work.
 *
 * <p>Uses {@code @Async("eventTaskExecutor")} so automation execution is
 * non-blocking and cannot slow down the HTTP response.
 *
 * @author AuraBoot Team
 * @since 6.0.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutomationCommandEventBridge {

    private final AutomationTriggerService automationTriggerService;
    private final CommandStateCheckExecutor commandStateCheckExecutor;

    @Async("eventTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommandCompleted(CommandCompletedEvent event) {
        String modelCode = event.getModelCode();
        String recordId = event.getRecordId();
        String operationType = event.getOperationType();

        if (!StringUtils.hasText(modelCode) || !StringUtils.hasText(operationType)) {
            log.debug("Skipping CommandCompletedEvent — missing modelCode or operationType: command={}",
                    event.getCommandCode());
            return;
        }

        log.debug("Bridging CommandCompletedEvent to automation: command={}, model={}, record={}, op={}",
                event.getCommandCode(), modelCode, recordId, operationType);

        try {
            switch (operationType.toLowerCase()) {
                case "create" -> handleCreate(event, modelCode, recordId);
                case "update" -> handleUpdate(event, modelCode, recordId);
                case "state_transition" -> handleStateTransition(event, modelCode, recordId);
                case "delete" -> {
                    // delete triggers are not currently modelled in AutomationTriggerService;
                    // log at trace level so we don't spam logs for every delete command.
                    log.trace("Automation bridge: skipping delete operationType for now (model={}, record={})",
                            modelCode, recordId);
                }
                default -> log.debug("Automation bridge: unrecognized operationType='{}', skipping (model={}, record={})",
                        operationType, modelCode, recordId);
            }
        } catch (Exception e) {
            // Automation failures must never propagate back and break the caller.
            // The command already committed — we only lose the automation side-effect.
            log.error("Error bridging CommandCompletedEvent to automation: command={}, model={}, record={}, op={}: {}",
                    event.getCommandCode(), modelCode, recordId, operationType, e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void handleCreate(CommandCompletedEvent event, String modelCode, String recordId) {
        Map<String, Object> recordData = event.getPayload();
        log.debug("Triggering onRecordCreate automation: model={}, record={}", modelCode, recordId);
        automationTriggerService.onRecordCreate(modelCode, recordId, recordData);
    }

    private void handleUpdate(CommandCompletedEvent event, String modelCode, String recordId) {
        Map<String, Object> metadata = event.getMetadata();

        @SuppressWarnings("unchecked")
        Map<String, Object> beforeData = (metadata != null)
                ? (Map<String, Object>) metadata.get("beforeSnapshot")
                : null;

        Map<String, Object> afterData = event.getPayload();

        log.debug("Triggering onRecordUpdate automation: model={}, record={}", modelCode, recordId);
        automationTriggerService.onRecordUpdate(modelCode, recordId, beforeData, afterData);

        // Also fire per-field change triggers for each field that changed
        if (beforeData != null && afterData != null) {
            for (Map.Entry<String, Object> entry : afterData.entrySet()) {
                String fieldCode = entry.getKey();
                Object newValue = entry.getValue();
                Object oldValue = beforeData.get(fieldCode);
                if (!java.util.Objects.equals(oldValue, newValue)) {
                    log.trace("Triggering onFieldChange automation: model={}, record={}, field={}",
                            modelCode, recordId, fieldCode);
                    automationTriggerService.onFieldChange(modelCode, recordId, fieldCode, oldValue, newValue);
                }
            }
        }
    }

    private void handleStateTransition(CommandCompletedEvent event, String modelCode, String recordId) {
        Map<String, Object> metadata = event.getMetadata();
        Map<String, Object> payload = event.getPayload();

        // Determine the state field for this model so we can extract from/to state values.
        String stateField = commandStateCheckExecutor.getStateFieldForModel(modelCode);

        String fromState = null;
        String toState = null;

        if (StringUtils.hasText(stateField)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> beforeSnapshot = (metadata != null)
                    ? (Map<String, Object>) metadata.get("beforeSnapshot")
                    : null;

            if (beforeSnapshot != null && beforeSnapshot.get(stateField) != null) {
                fromState = String.valueOf(beforeSnapshot.get(stateField));
            }
            if (payload != null && payload.get(stateField) != null) {
                toState = String.valueOf(payload.get(stateField));
            }
        }

        if (!StringUtils.hasText(toState)) {
            // Fallback: try well-known status key names in payload
            for (String candidate : new String[]{"status", "state"}) {
                if (payload != null && payload.get(candidate) != null) {
                    toState = String.valueOf(payload.get(candidate));
                    break;
                }
            }
        }

        log.debug("Triggering onStateChange automation: model={}, record={}, {} -> {}",
                modelCode, recordId, fromState, toState);
        automationTriggerService.onStateChange(modelCode, recordId, fromState, toState);

        // Also fire update trigger so automations watching field changes also respond
        @SuppressWarnings("unchecked")
        Map<String, Object> beforeData = (metadata != null)
                ? (Map<String, Object>) metadata.get("beforeSnapshot")
                : null;
        automationTriggerService.onRecordUpdate(modelCode, recordId, beforeData, payload);
    }
}
