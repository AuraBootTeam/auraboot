package com.auraboot.framework.behavior.ingest;

import com.auraboot.framework.behavior.dto.BehaviorEventInput;
import com.auraboot.framework.behavior.entity.BehaviorEvent;
import com.auraboot.framework.behavior.mapper.BehaviorEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Persists ingested behavior events to {@code ab_behavior_event} with per-event idempotency
 * (unique {@code (tenant_id, event_id)}) and routes events that cannot be durably stored —
 * malformed (missing event_id/event_name) or constraint-violating — to the quarantine topic
 * instead of dropping them. Runs on the MQ consumer thread (no request MetaContext); the
 * tenant is always carried in the envelope and set explicitly on the entity.
 */
@Component
public class BehaviorEventPersister {

    private static final int MAX_DETAIL = 500;

    private final BehaviorEventMapper behaviorEventMapper;
    private final BehaviorIngestPublisher publisher;
    private final ObjectMapper objectMapper;
    private final BehaviorIngestMetrics metrics;

    @Autowired
    public BehaviorEventPersister(BehaviorEventMapper behaviorEventMapper,
                                  BehaviorIngestPublisher publisher,
                                  ObjectMapper objectMapper,
                                  BehaviorIngestMetrics metrics) {
        this.behaviorEventMapper = behaviorEventMapper;
        this.publisher = publisher;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    BehaviorEventPersister(BehaviorEventMapper behaviorEventMapper,
                           BehaviorIngestPublisher publisher,
                           ObjectMapper objectMapper) {
        this(behaviorEventMapper, publisher, objectMapper, BehaviorIngestMetrics.noop());
    }

    /** Persist every event in the envelope; returns the number durably stored (or idempotently present). */
    public int persistBatch(BehaviorIngestEnvelope env) {
        if (env == null || env.events() == null || env.events().isEmpty()) {
            return 0;
        }
        int persisted = 0;
        for (BehaviorEventInput in : env.events()) {
            if (persistOne(env.tenantId(), env.userId(), in)) {
                persisted++;
            }
        }
        return persisted;
    }

    /** @return true if durably stored (or already present / idempotent), false if quarantined. */
    boolean persistOne(Long tenantId, Long userId, BehaviorEventInput in) {
        if (in == null || in.getEventId() == null) {
            publisher.publishQuarantine(tenantId, userId, "malformed_missing_event_id", "event_id is required", in);
            return false;
        }
        if (in.getEventName() == null) {
            publisher.publishQuarantine(tenantId, userId, "malformed_missing_event_name", "event_name is required", in);
            return false;
        }
        try {
            behaviorEventMapper.insert(toEntity(in, tenantId, userId));
            metrics.recordPersisted("inserted");
            return true;
        } catch (DuplicateKeyException dup) {
            // idempotent: same (tenant_id, event_id) already stored — at-least-once redelivery
            metrics.recordPersisted("duplicate");
            return true;
        } catch (DataIntegrityViolationException ex) {
            // deterministic bad data (e.g. an over-long client field) — retrying won't help; quarantine it
            publisher.publishQuarantine(tenantId, userId, "constraint_violation", mostSpecific(ex), in);
            return false;
        }
    }

    private String mostSpecific(DataIntegrityViolationException ex) {
        Throwable cause = ex.getMostSpecificCause();
        String msg = cause != null ? cause.getMessage() : ex.getMessage();
        if (msg == null) {
            return "constraint violation";
        }
        msg = msg.replaceAll("\\s+", " ").trim();
        return msg.length() > MAX_DETAIL ? msg.substring(0, MAX_DETAIL) : msg;
    }

    private BehaviorEvent toEntity(BehaviorEventInput in, Long tenantId, Long userId) {
        return BehaviorEventEntityFactory.toEntity(in, tenantId, userId, objectMapper);
    }
}
