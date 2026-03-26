package com.auraboot.framework.meta.event;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.event.DomainEventType;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Event published when a formal decision is made.
 * Standalone POJO for outbox serialization — NOT a Spring ApplicationEvent.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Getter
public class DecisionEvent {

    private final String eventId;
    private final String eventType;
    private final Instant timestamp;
    private final String subjectType;
    private final String subjectId;
    private final String stage;
    private final String outcome;
    private final Map<String, Object> evidenceSummary;
    private final List<Map<String, Object>> invariantResults;
    private final Map<String, Object> trace;
    private final Long tenantId;
    private final Long userId;

    @JsonCreator
    public DecisionEvent(@JsonProperty("subjectType") String subjectType,
                         @JsonProperty("subjectId") String subjectId,
                         @JsonProperty("stage") String stage,
                         @JsonProperty("outcome") String outcome,
                         @JsonProperty("evidenceSummary") Map<String, Object> evidenceSummary,
                         @JsonProperty("invariantResults") List<Map<String, Object>> invariantResults,
                         @JsonProperty("trace") Map<String, Object> trace,
                         @JsonProperty("tenantId") Long tenantId,
                         @JsonProperty("userId") Long userId) {
        this.eventId = UniqueIdGenerator.generate();
        this.eventType = DomainEventType.DECISION.getValue();
        this.timestamp = Instant.now();
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.stage = stage;
        this.outcome = outcome;
        this.evidenceSummary = evidenceSummary;
        this.invariantResults = invariantResults;
        this.trace = trace;
        this.tenantId = tenantId;
        this.userId = userId;
    }
}
