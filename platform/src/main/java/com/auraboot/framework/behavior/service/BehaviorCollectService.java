package com.auraboot.framework.behavior.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.dto.BehaviorEventInput;
import com.auraboot.framework.behavior.ingest.BehaviorIngestPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Server-side ingestion for /api/collect (M1; SoT §5.5/§2.5) and /api/collect/keyed (SP2).
 * Enriches tenant/user from context (never trusts the client), then <b>enqueues</b> the batch
 * onto the ingest topic ({@code aura.behavior.events.v1}) via {@link BehaviorIngestPublisher}
 * — the endpoint only validates synchronously and returns the number enqueued. The durable
 * write to {@code ab_behavior_event} (with per-event idempotency and quarantine routing) is
 * performed asynchronously by the ingest consumer. With {@code aura.mq.type=memory} delivery is
 * synchronous (equivalent to the old in-request persist); with {@code kafka} it is decoupled.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BehaviorCollectService {

    private final BehaviorIngestPublisher publisher;

    /**
     * Authenticated path (M1): tenant/user from the auth context (never trusts the client).
     * Returns the number of events enqueued for asynchronous persistence.
     */
    public int record(List<BehaviorEventInput> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        if (!MetaContext.exists()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "tenant_required");
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "tenant_required");
        }
        return publisher.publish(tenantId, MetaContext.getCurrentUserId(), events);
    }

    /**
     * Anonymous/keyed path (SP2): the caller has already resolved the owning tenant from the
     * public site key, so the tenant is passed in explicitly and there is no user — the
     * client-supplied {@code anonId} is the only identity. Returns the number enqueued.
     */
    public int recordAnonymous(List<BehaviorEventInput> events, long tenantId) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        return publisher.publish(tenantId, null, events);
    }
}
