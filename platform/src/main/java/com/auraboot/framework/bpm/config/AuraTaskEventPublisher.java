package com.auraboot.framework.bpm.config;

import com.auraboot.smart.framework.engine.configuration.TaskEventPublisher;
import com.auraboot.smart.framework.engine.model.instance.TaskInstance;
import com.auraboot.smart.framework.engine.pvm.event.EventConstant;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.event.BpmEvent;
import com.auraboot.framework.bpm.event.EventBusService;
import com.auraboot.framework.plugin.entity.BpmProcessDefinition;
import com.auraboot.framework.plugin.mapper.BpmProcessDefinitionMapper;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
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
    private final BpmProcessDefinitionMapper processDefinitionMapper;
    private final UserService userService;

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

        // Enrich with process display name from BpmProcessDefinition
        enrichWithProcessName(payload, processKey, tenantIdLong);

        // Enrich with initiator display name if startUserId is available
        enrichWithInitiatorName(payload);

        BpmEvent bpmEvent = new BpmEvent(
                tenantIdLong, eventType, "bpm",
                processKey, processInstanceId,
                taskInstance.getProcessDefinitionActivityId(),
                payload);

        eventBusService.publish(bpmEvent);

        log.debug("Task event published: event={}, taskId={}, processInstance={}",
                eventType, taskInstance.getInstanceId(), processInstanceId);
    }

    /**
     * Look up the human-readable process name from ab_bpm_process_definition
     * and add it to the payload as "processName".
     */
    private void enrichWithProcessName(Map<String, Object> payload, String processKeyWithVersion, Long tenantId) {
        if (processKeyWithVersion == null || tenantId == null || tenantId == 0L) return;
        try {
            // processKey from SmartEngine includes version suffix (e.g. "pr_xxx:1.0.0")
            // Strip version to get the base process key
            String baseKey = processKeyWithVersion.contains(":")
                    ? processKeyWithVersion.substring(0, processKeyWithVersion.indexOf(':'))
                    : processKeyWithVersion;

            BpmProcessDefinition definition = processDefinitionMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<BpmProcessDefinition>()
                            .eq("tenant_id", tenantId)
                            .eq("process_key", baseKey)
                            .eq("is_current", true)
                            .eq("deleted_flag", false));

            if (definition != null && definition.getProcessName() != null) {
                payload.put("processName", definition.getProcessName());
            }
        } catch (Exception e) {
            // CATCH: non-transactional enrichment, safe to handle — do not block event publishing
            log.debug("Could not enrich process name for key={}: {}", processKeyWithVersion, e.getMessage());
        }
    }

    /**
     * If the payload contains a startUserId, resolve the user's display name
     * and add it as "initiatorName".
     */
    private void enrichWithInitiatorName(Map<String, Object> payload) {
        Object startUserId = payload.get("startUserId");
        if (startUserId == null) return;
        try {
            String userIdStr = startUserId.toString();
            User user = null;
            try {
                Long userId = Long.parseLong(userIdStr);
                user = userService.findByUserId(userId);
            } catch (NumberFormatException nfe) {
                // Could be a ULID/PID — try findByPid
                user = userService.findByPid(userIdStr);
            }
            if (user != null) {
                String name = user.getNickName() != null ? user.getNickName() : user.getUserName();
                if (name != null) {
                    payload.put("initiatorName", name);
                }
            }
        } catch (Exception e) {
            // CATCH: non-transactional enrichment, safe to handle
            log.debug("Could not enrich initiator name for startUserId={}: {}", startUserId, e.getMessage());
        }
    }
}
