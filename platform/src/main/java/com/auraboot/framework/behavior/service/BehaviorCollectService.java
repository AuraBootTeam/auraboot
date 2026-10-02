package com.auraboot.framework.behavior.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.dto.BehaviorEventInput;
import com.auraboot.framework.behavior.ingest.BehaviorIngestMetrics;
import com.auraboot.framework.behavior.ingest.BehaviorIngestPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

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
public class BehaviorCollectService {

    private static final Set<String> CLIENT_SCHEMA_VERSIONS = Set.of("1", "2");
    private static final Set<String> CLIENT_IDENTITY_QUALITIES =
            Set.of("stable", "heuristic", "declared", "anonymous");

    private final BehaviorIngestPublisher publisher;
    private final BehaviorIngestMetrics metrics;

    @Autowired
    public BehaviorCollectService(BehaviorIngestPublisher publisher, BehaviorIngestMetrics metrics) {
        this.publisher = publisher;
        this.metrics = metrics;
    }

    BehaviorCollectService(BehaviorIngestPublisher publisher) {
        this(publisher, BehaviorIngestMetrics.noop());
    }

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
        rejectReservedEvents(events);
        validateClientEnvelopes(events);
        int enqueued = publisher.publish(tenantId, MetaContext.getCurrentUserId(), events);
        metrics.recordAccepted("authenticated", enqueued);
        return enqueued;
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
        rejectReservedEvents(events);
        validateClientEnvelopes(events);
        int enqueued = publisher.publish(tenantId, null, events);
        metrics.recordAccepted("keyed", enqueued);
        return enqueued;
    }
    private void rejectReservedEvents(List<BehaviorEventInput> events) {
        if (events.stream().anyMatch(event -> event != null && (
                normalized(event.getEventName()).startsWith("analytics_")
                || "server".equals(normalized(event.getSource()))
                || "business_outcome".equals(normalized(event.getEventCategory()))
                || normalized(event.getProducerName()).startsWith("server-")
                || "aurabot-analytics".equals(normalized(event.getProducerName()))))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reserved_server_event");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private void validateClientEnvelopes(List<BehaviorEventInput> events) {
        for (BehaviorEventInput event : events) {
            if (event == null || event.getSchemaVersion() == null
                    || !CLIENT_SCHEMA_VERSIONS.contains(event.getSchemaVersion())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported_client_schema_version");
            }
            if (event.getIdentityQuality() != null
                    && !CLIENT_IDENTITY_QUALITIES.contains(event.getIdentityQuality())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_client_identity_quality");
            }
            if (event.getProps() == null) continue;
            // The browser SDK emits only an optional route template; UI attribution
            // has dedicated envelope fields. Never enqueue arbitrary business content.
            for (var entry : event.getProps().entrySet()) {
                if (!"routeTemplate".equals(entry.getKey())
                        || !(entry.getValue() instanceof String route)
                        || !route.matches("/[A-Za-z0-9_./:-]{0,511}")) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_client_event_properties");
                }
            }
        }
    }

}
