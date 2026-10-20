package com.auraboot.framework.eventpolicy.runtime;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.eventpolicy.mapper.DrtOutboxMapper;
import com.auraboot.framework.eventpolicy.service.OutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Resumes durable pending events after a process restart, using each event's stored actor. */
@Component
@RequiredArgsConstructor
public class EventPolicyOutboxWorker {
    private final DrtOutboxMapper mapper;
    private final OutboxService outbox;

    @Scheduled(fixedDelayString = "${auraboot.event-policy.outbox-delay-ms:5000}")
    public void processPending() {
        for (Long tenantId : mapper.pendingTenantIds(50)) {
            var identity = new MetaContext.Snapshot(tenantId, null, null, null,
                    java.util.Set.of(), null, null, null, null);
            MetaContext.runWithSnapshot(identity, () -> {
                for (var event : mapper.findPending(tenantId, 50)) outbox.processEvent(event.getEventId());
            });
        }
    }
}
