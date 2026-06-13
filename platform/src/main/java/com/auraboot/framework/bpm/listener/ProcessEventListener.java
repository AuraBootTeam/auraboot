package com.auraboot.framework.bpm.listener;

import com.auraboot.smart.framework.engine.constant.RequestMapSpecialKeyConstant;
import com.auraboot.smart.framework.engine.context.ExecutionContext;
import com.auraboot.smart.framework.engine.listener.Listener;
import com.auraboot.smart.framework.engine.pvm.event.EventConstant;
import com.auraboot.framework.bpm.audit.BpmAuditService;
import com.auraboot.framework.bpm.extension.BpmExtensionAccessor;
import com.auraboot.framework.bpm.event.EventBusService;
import com.auraboot.framework.bpm.service.BpmNodeHookService;
import com.auraboot.framework.bpm.service.BpmRuleBindingRuntimeService;
import com.auraboot.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Process event listener.
 * Listens to SmartEngine process events and integrates audit logging.
 *
 * @author AuraBoot Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessEventListener implements Listener {

    private final BpmAuditService bpmAuditService;
    private final EventBusService eventBusService;
    private final BpmNodeHookService hookService;
    private final BpmExtensionAccessor extensionAccessor;
    private final BpmRuleBindingRuntimeService ruleBindingRuntimeService;

    @Override
    public void execute(EventConstant event, ExecutionContext executionContext) {
        try {
            Map<String, Object> request = executionContext.getRequest();
            String tenantId = request != null
                    ? (String) request.get(RequestMapSpecialKeyConstant.TENANT_ID)
                    : null;

            String processInstanceId = executionContext.getProcessInstance() != null
                    ? executionContext.getProcessInstance().getInstanceId()
                    : null;

            String activityId = executionContext.getExecutionInstance() != null
                    ? executionContext.getExecutionInstance().getProcessDefinitionActivityId()
                    : null;

            String startUserId = request != null
                    ? (String) request.get(RequestMapSpecialKeyConstant.PROCESS_INSTANCE_START_USER_ID)
                    : null;

            log.debug("Process event received: event={}, processInstanceId={}, activityId={}, tenantId={}",
                    event, processInstanceId, activityId, tenantId);

            if (tenantId == null || processInstanceId == null) {
                return;
            }

            switch (event) {
                case PROCESS_START, start -> {
                    if (activityId == null) {
                        // ProcessEngineService records the canonical process_start audit
                        // after SmartEngine returns; avoid duplicating it as process_event.
                        eventBusService.publishTransientProcessEvent("process_started", null, processInstanceId,
                                new HashMap<>(Map.of("startUserId", startUserId != null ? startUserId : "")));
                    }
                }
                case PROCESS_END, end -> {
                    if (activityId == null) {
                        bpmAuditService.recordProcessEvent(processInstanceId, "process_end",
                                "Process ended", null, tenantId);
                        eventBusService.publishTransientProcessEvent("process_ended", null, processInstanceId, Map.of());
                    }
                }
                case ACTIVITY_START -> {
                    // Execute pre-check hooks
                    if (activityId != null) {
                        bpmAuditService.recordActivityEvent(processInstanceId, activityId,
                                "activity_start", "Activity started", startUserId, tenantId);
                        try {
                            String processKey = executionContext.getProcessInstance() != null
                                    ? executionContext.getProcessInstance().getProcessDefinitionId() : null;
                            if (processKey != null) {
                                extensionAccessor.getRuleConsumerBinding(processKey, activityId)
                                        .ifPresent(binding -> ruleBindingRuntimeService.evaluateAndApply(
                                                binding, processKey, activityId, processInstanceId,
                                                request != null ? request : Map.of()));
                                BpmNodeHookService.HookExecutionResult preCheck =
                                        hookService.executePreChecks(processKey, activityId, request != null ? request : Map.of());
                                if (!preCheck.passed()) {
                                    throw new BusinessException("Pre-check failed for node " + activityId + ": " + preCheck.message());
                                }
                            }
                        } catch (RuntimeException e) {
                            throw e;
                        } catch (Exception e) {
                            log.error("Error executing pre-check hooks: activityId={}", activityId, e);
                        }
                    }
                }
                case ACTIVITY_END -> {
                    // Execute post-action hooks
                    if (activityId != null) {
                        try {
                            String processKey = executionContext.getProcessInstance() != null
                                    ? executionContext.getProcessInstance().getProcessDefinitionId() : null;
                            if (processKey != null) {
                                hookService.executePostActions(processKey, activityId, request != null ? request : Map.of());
                            }
                        } catch (Exception e) {
                            log.error("Error executing post-action hooks: activityId={}", activityId, e);
                        }
                        bpmAuditService.recordActivityEvent(processInstanceId, activityId,
                                "activity_end", "Activity ended", startUserId, tenantId);
                    }
                }
                case ACTIVITY_EXECUTE -> {
                    log.debug("Activity executing: processInstanceId={}, activityId={}", processInstanceId, activityId);
                    // Task events (TASK_ASSIGNED etc.) are now fired directly by SmartEngine
                    // via TaskEventPublisher SPI — no need to query pending tasks here.
                }
                default ->
                    log.debug("Unhandled event: {}", event);
            }

        } catch (Exception e) {
            log.error("Error processing process event: {}", event, e);
        }
    }

}
