package com.auraboot.framework.behavior.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.dto.BehaviorEventInput;
import com.auraboot.framework.behavior.entity.BehaviorEvent;
import com.auraboot.framework.behavior.mapper.BehaviorEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Server-side ingestion for /api/collect (M1; SoT §5.5/§2.5). Enriches tenant/user
 * from the auth context (never trusts the client), maps the envelope to
 * {@link BehaviorEvent}, and persists to the durable store with per-event idempotency
 * (unique {@code (tenant_id, event_id)}). The Kafka decoupling layer
 * (aura.behavior.events.v1) is the production ingestion path (follow-up).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BehaviorCollectService {

    private final BehaviorEventMapper behaviorEventMapper;
    private final ObjectMapper objectMapper;

    /**
     * Authenticated path (M1): tenant/user from the auth context (never trusts the client).
     * Returns the number accepted (duplicates count as accepted).
     */
    public int record(List<BehaviorEventInput> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "tenant_required");
        }
        return recordBatch(events, tenantId, MetaContext.getCurrentUserId());
    }

    /**
     * Anonymous/keyed path (SP2): the caller has already resolved the owning tenant from the
     * public site key, so the tenant is passed in explicitly and there is no user — the
     * client-supplied {@code anonId} is the only identity. Same entity mapping + idempotency
     * ({@code (tenant_id, event_id)}) as {@link #record}.
     */
    public int recordAnonymous(List<BehaviorEventInput> events, long tenantId) {
        return recordBatch(events, tenantId, null);
    }

    /** Shared batch persist for both the authenticated and keyed-anonymous paths. */
    private int recordBatch(List<BehaviorEventInput> events, Long tenantId, Long userId) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        int accepted = 0;
        for (BehaviorEventInput in : events) {
            if (in == null || in.getEventId() == null || in.getEventName() == null) {
                continue; // skip malformed (per-event, not batch-fatal); quarantine is a follow-up
            }
            try {
                behaviorEventMapper.insert(toEntity(in, tenantId, userId));
                accepted++;
            } catch (DuplicateKeyException dup) {
                accepted++; // client retry with same eventId — already stored, idempotent
            } catch (DataIntegrityViolationException bad) {
                // A single event that violates a column/constraint (e.g. an over-long field from a
                // misbehaving or hostile client on this PUBLIC unauthenticated endpoint) is skipped,
                // not fatal — never 500 the whole batch on one bad event. Same per-event resilience
                // as the malformed skip above; the rest of the batch still persists.
                log.warn("Skipping behavior event violating a DB constraint (eventId={}): {}",
                        in.getEventId(), bad.getMostSpecificCause().getMessage());
            }
        }
        return accepted;
    }

    private BehaviorEvent toEntity(BehaviorEventInput in, Long tenantId, Long userId) {
        BehaviorEvent e = new BehaviorEvent();
        e.setEventId(in.getEventId());
        e.setSchemaVersion(in.getSchemaVersion());
        e.setEventName(in.getEventName());
        e.setEventCategory(in.getEventCategory());
        e.setSource(in.getSource());
        e.setIdentityQuality(in.getIdentityQuality());
        e.setOccurredAt(in.getOccurredAt());
        e.setTenantId(tenantId);
        e.setUserId(userId);
        e.setAnonId(in.getAnonId());
        e.setClientSessionId(in.getClientSessionId());
        e.setInteractionId(in.getInteractionId());
        e.setCausedByEventId(in.getCausedByEventId());
        e.setTraceId(in.getTraceId());
        e.setSourceSpanId(in.getSourceSpanId());
        e.setRunId(in.getRunId());
        e.setUiElementId(in.getUiElementId());
        e.setAppId(in.getAppId());
        e.setPageId(in.getPageId());
        e.setBlockId(in.getBlockId());
        e.setElementCode(in.getElementCode());
        e.setProps(writeProps(in.getProps()));
        e.setConsentState(in.getConsentState());
        e.setConsentVersion(in.getConsentVersion());
        e.setSamplingUnit(in.getSamplingUnit());
        e.setSamplingProbability(in.getSamplingProbability());
        e.setProducerName(in.getProducerName());
        e.setProducerVersion(in.getProducerVersion());
        return e;
    }

    private String writeProps(Map<String, Object> props) {
        if (props == null || props.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(props);
        } catch (Exception ex) {
            log.warn("Failed to serialize behavior props: {}", ex.getMessage());
            return null;
        }
    }
}
