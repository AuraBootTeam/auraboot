package com.auraboot.framework.bpm.listener;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.SlaConfigEntity;
import com.auraboot.framework.bpm.event.BpmEvent;
import com.auraboot.framework.bpm.service.SlaConfigService;
import com.auraboot.framework.bpm.service.SlaRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Listens to {@code task_assigned} BPM events and creates an SLA record for each
 * matching {@link SlaConfigEntity} targeting the activated node.
 *
 * <p>Lookup strategy: {@code targetType="NODE"} + {@code targetKey=activityId}.
 * If no matching SLA config exists the event is silently ignored (SLA is optional).
 * Deadline is computed as {@code now + deadlineValue} (ISO-8601 duration, e.g. {@code PT30S}).
 *
 * <p>Transaction boundary: called within the same transaction as the task-creation event.
 * The record insert joins the outer transaction (REQUIRED propagation).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaActivationListener {

    private final SlaConfigService slaConfigService;
    private final SlaRecordService slaRecordService;

    @EventListener
    public void onBpmEvent(BpmEvent event) {
        if (!"task_assigned".equals(event.getBpmEventType())) {
            return;
        }

        String activityId = event.getNodeId();
        String processInstanceId = event.getInstanceId();
        String taskId = extractTaskId(event);

        if (activityId == null || processInstanceId == null) {
            log.debug("SlaActivationListener: missing activityId or processInstanceId in task_assigned event, skipping");
            return;
        }

        Long tenantId = event.getTenantId();
        if (tenantId == null || tenantId == 0L) {
            log.debug("SlaActivationListener: no tenantId in task_assigned event for activityId={}", activityId);
            return;
        }

        // Only set MetaContext if not already initialised by the outer request thread.
        // If it IS already set (normal HTTP request path), we must NOT clear it on exit
        // — doing so would corrupt the context for subsequent filters/handlers.
        boolean contextOwner = false;
        try {
            MetaContext.getCurrentTenantId(); // throws if not initialised
        } catch (Exception e) {
            // MetaContext not set — we own it for this invocation
            MetaContext.setContext(tenantId, 0L, null, "system");
            contextOwner = true;
        }

        try {
            // Look up SLA configs targeting this node (targetType may be stored as "NODE" or "node")
            List<SlaConfigEntity> configs = slaConfigService.findByTarget("NODE", activityId);
            if (configs.isEmpty()) {
                // Also try lowercase (UI-created configs may use lowercase)
                configs = slaConfigService.findByTarget("node", activityId);
            }

            if (configs.isEmpty()) {
                log.debug("No SLA config found for NODE/{} — skipping SLA record creation", activityId);
                return;
            }

            for (SlaConfigEntity config : configs) {
                if (!Boolean.TRUE.equals(config.getEnabled())) {
                    continue;
                }
                try {
                    Instant deadline = computeDeadline(config);
                    slaRecordService.createRecord(config, processInstanceId, taskId, activityId, deadline);
                    log.info("SLA record created at task activation: activityId={}, configPid={}, deadline={}",
                            activityId, config.getPid(), deadline);
                } catch (Exception e) {
                    // CATCH: non-critical — SLA record creation must not block task activation
                    log.error("Failed to create SLA record for config={}, activityId={}: {}",
                            config.getPid(), activityId, e.getMessage(), e);
                }
            }
        } finally {
            // Only clear if we were the ones who set it — never clear the caller's context
            if (contextOwner) {
                MetaContext.clear();
            }
        }
    }

    private String extractTaskId(BpmEvent event) {
        if (event.getPayload() == null) return null;
        Object v = event.getPayload().get("taskInstanceId");
        return v != null ? v.toString() : null;
    }

    /**
     * Compute the SLA deadline from the config.
     * Currently supports {@code deadlineMode=FIXED} with an ISO-8601 duration string
     * (e.g. {@code PT30S}, {@code PT8H}).
     * Falls back to 24 hours for unknown modes.
     */
    private Instant computeDeadline(SlaConfigEntity config) {
        Instant now = Instant.now();
        String mode = config.getDeadlineMode();
        String value = config.getDeadlineValue();

        if ("FIXED".equalsIgnoreCase(mode) && value != null && !value.isBlank()) {
            try {
                Duration duration = Duration.parse(value);
                return now.plus(duration);
            } catch (Exception e) {
                log.warn("Cannot parse SLA deadlineValue '{}' for config={}: {}",
                        value, config.getPid(), e.getMessage());
            }
        }

        // Fallback: 24 hours
        log.debug("SLA deadlineMode='{}' not handled or value missing — defaulting to PT24H for config={}",
                mode, config.getPid());
        return now.plus(Duration.ofHours(24));
    }
}
