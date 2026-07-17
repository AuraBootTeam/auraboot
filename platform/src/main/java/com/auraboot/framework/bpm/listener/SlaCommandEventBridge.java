package com.auraboot.framework.bpm.listener;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.module.meta.event.CommandCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Set;

/**
 * Bridges command-created dynamic records to record-level SLA activation.
 *
 * <p>DSL commands persist through {@code CommandFieldMapExecutor} and do not call
 * {@code DynamicDataService.create}, so the direct record-create hook there cannot
 * see those records. This listener consumes the committed command event and reuses
 * {@link SlaActivationListener#onRecordCreate(String, String, Map)}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaCommandEventBridge {

    private final SlaActivationListener slaActivationListener;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommandCompleted(CommandCompletedEvent event) {
        if (event == null || !"create".equalsIgnoreCase(event.getOperationType())) {
            return;
        }
        String modelCode = event.getModelCode();
        String recordPid = event.getRecordId();
        if (!StringUtils.hasText(modelCode) || !StringUtils.hasText(recordPid) || event.getTenantId() == null) {
            log.debug("Skipping command-created SLA activation: command={}, model={}, record={}, tenant={}",
                    event.getCommandCode(), modelCode, recordPid, event != null ? event.getTenantId() : null);
            return;
        }

        ContextSnapshot previous = ContextSnapshot.capture();
        try {
            Long actorId = extractLong(event.getMetadata(), "actorId");
            String actorName = extractString(event.getMetadata(), "actorName");
            MetaContext.setContext(event.getTenantId(), actorId != null ? actorId : 0L, null,
                    StringUtils.hasText(actorName) ? actorName : "system");
            slaActivationListener.onRecordCreate(modelCode, recordPid, event.getPayload());
        } catch (Exception e) {
            log.error("Failed to bridge command create to record-level SLA: command={}, model={}, record={}: {}",
                    event.getCommandCode(), modelCode, recordPid, e.getMessage(), e);
        } finally {
            previous.restore();
        }
    }

    private Long extractLong(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String extractString(Map<String, Object> metadata, String key) {
        Object value = metadata != null ? metadata.get(key) : null;
        return value != null ? String.valueOf(value) : null;
    }

    private record ContextSnapshot(
            boolean exists,
            Long tenantId,
            Long userId,
            String userPid,
            String username,
            Long memberId,
            Long environmentId,
            Set<Long> roleIds) {

        static ContextSnapshot capture() {
            if (!MetaContext.exists()) {
                return new ContextSnapshot(false, null, null, null, null, null, null, Set.of());
            }
            return new ContextSnapshot(
                    true,
                    MetaContext.getCurrentTenantId(),
                    MetaContext.getCurrentUserId(),
                    MetaContext.getCurrentUserPid(),
                    MetaContext.getCurrentUsername(),
                    MetaContext.getCurrentMemberId(),
                    MetaContext.getCurrentEnvironmentId(),
                    MetaContext.getCurrentRoleIds());
        }

        void restore() {
            MetaContext.clear();
            if (!exists) {
                return;
            }
            MetaContext.setContext(tenantId, userId, userPid, username, roleIds);
            MetaContext.setMemberId(memberId);
            MetaContext.setEnvironmentId(environmentId);
        }
    }
}
