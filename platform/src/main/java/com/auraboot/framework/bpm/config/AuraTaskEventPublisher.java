package com.auraboot.framework.bpm.config;

import com.alibaba.smart.framework.engine.configuration.TaskEventPublisher;
import com.alibaba.smart.framework.engine.model.instance.TaskInstance;
import com.alibaba.smart.framework.engine.pvm.event.EventConstant;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.event.BpmEvent;
import com.auraboot.framework.bpm.event.EventBusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Platform implementation of SmartEngine's TaskEventPublisher SPI.
 * Bridges task lifecycle events to AuraBoot's EventBusService,
 * which persists them and dispatches to Spring listeners (e.g. InboxEventListener).
 *
 * <p><b>Transaction boundary:</b> This publisher is called synchronously within
 * SmartEngine's transaction context (e.g. inside UserTaskBehavior.enter()).
 * The event log insert joins the outer transaction (REQUIRED propagation).
 * Downstream listeners MUST use {@code @TransactionalEventListener(phase = AFTER_COMMIT)}
 * to ensure they only act after successful commit — otherwise a rollback
 * could void the task creation while the listener has already acted.</p>
 *
 * @since 6.4.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuraTaskEventPublisher implements TaskEventPublisher {

    private final EventBusService eventBusService;

    @Override
    public void publish(EventConstant event, TaskInstance taskInstance,
                        String tenantId, Map<String, Object> extra) {
        if (taskInstance == null) {
            log.warn("TaskEventPublisher.publish called with null taskInstance for event {}", event);
            return;
        }

        String eventType = event.name().toLowerCase(); // e.g. "task_assigned"
        String processKey = taskInstance.getProcessDefinitionIdAndVersion();
        String processInstanceId = taskInstance.getProcessInstanceId();

        // Build payload: merge task info + extra
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskInstanceId", taskInstance.getInstanceId());
        payload.put("taskName", taskInstance.getTag() != null
                ? taskInstance.getTag()
                : taskInstance.getProcessDefinitionActivityId());
        payload.put("activityId", taskInstance.getProcessDefinitionActivityId());
        if (extra != null) {
            payload.putAll(extra);
        }

        Long tenantIdLong;
        try {
            tenantIdLong = tenantId != null ? Long.parseLong(tenantId) : null;
        } catch (NumberFormatException e) {
            log.warn("Invalid tenantId '{}' in task event, will try MetaContext fallback", tenantId);
            tenantIdLong = null;
        }
        // Fall back to MetaContext if tenantId was not propagated by SmartEngine
        // (e.g. DefaultTaskCommandService.complete() does not pass TENANT_ID from request)
        if (tenantIdLong == null || tenantIdLong == 0L) {
            try {
                Long ctxTenantId = MetaContext.getCurrentTenantId();
                if (ctxTenantId != null && ctxTenantId != 0L) {
                    tenantIdLong = ctxTenantId;
                }
            } catch (Exception ex) {
                log.debug("MetaContext fallback failed for task event tenantId: {}", ex.getMessage());
            }
        }
        if (tenantIdLong == null) {
            tenantIdLong = 0L;
        }

        BpmEvent bpmEvent = new BpmEvent(
                tenantIdLong, eventType, "bpm",
                processKey, processInstanceId,
                taskInstance.getProcessDefinitionActivityId(),
                payload);

        eventBusService.publish(bpmEvent);

        log.debug("Task event published: event={}, taskId={}, processInstance={}",
                eventType, taskInstance.getInstanceId(), processInstanceId);
    }
}
