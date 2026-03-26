package com.auraboot.framework.bpm.listener;

import com.alibaba.smart.framework.engine.constant.RequestMapSpecialKeyConstant;
import com.alibaba.smart.framework.engine.context.ExecutionContext;
import com.alibaba.smart.framework.engine.listener.Listener;
import com.alibaba.smart.framework.engine.pvm.event.EventConstant;
import com.auraboot.framework.bpm.audit.BpmAuditService;
import com.auraboot.framework.bpm.event.EventBusService;
import com.auraboot.framework.bpm.service.BpmNodeHookService;
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
                        bpmAuditService.recordProcessEvent(processInstanceId, "process_start",
                                "Process started", startUserId, tenantId);
                        eventBusService.publishProcessEvent("process_started", null, processInstanceId,
                                new HashMap<>(Map.of("startUserId", startUserId != null ? startUserId : "")));
                    } else {
                        bpmAuditService.recordActivityEvent(processInstanceId, activityId, "activity_start",
                                "Activity started", startUserId, tenantId);
                    }
                }
                case PROCESS_END, end -> {
                    if (activityId == null) {
                        bpmAuditService.recordProcessEvent(processInstanceId, "process_end",
                                "Process ended", null, tenantId);
                        eventBusService.publishProcessEvent("process_ended", null, processInstanceId, Map.of());
                    } else {
                        bpmAuditService.recordActivityEvent(processInstanceId, activityId, "activity_end",
                                "Activity ended", null, tenantId);
                        eventBusService.publishProcessEvent("activity_completed", null, processInstanceId,
                                new HashMap<>(Map.of("activityId", activityId != null ? activityId : "")));
                    }
                }
                case ACTIVITY_START -> {
                    bpmAuditService.recordActivityEvent(processInstanceId, activityId, "activity_start",
                            "Activity started", startUserId, tenantId);
                    // Execute pre-check hooks
                    if (activityId != null) {
                        try {
                            String processKey = executionContext.getProcessInstance() != null
                                    ? executionContext.getProcessInstance().getProcessDefinitionId() : null;
                            if (processKey != null) {
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
                    bpmAuditService.recordActivityEvent(processInstanceId, activityId, "activity_end",
                            "Activity ended", null, tenantId);
                    eventBusService.publishProcessEvent("activity_completed", null, processInstanceId,
                            new HashMap<>(Map.of("activityId", activityId != null ? activityId : "")));
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
