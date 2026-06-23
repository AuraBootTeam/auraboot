package com.auraboot.framework.behavior.ingest;

import com.auraboot.framework.behavior.dto.BehaviorEventInput;
import com.auraboot.framework.behavior.entity.BehaviorEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Shared conversion for ingest persistence and quarantine replay so replay uses the
 * same durable event shape as the async consumer path.
 */
public final class BehaviorEventEntityFactory {

    private BehaviorEventEntityFactory() {
    }

    public static BehaviorEvent toEntity(BehaviorEventInput in,
                                         Long tenantId,
                                         Long userId,
                                         ObjectMapper objectMapper) {
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
        e.setProps(writeProps(in.getProps(), objectMapper));
        e.setConsentState(in.getConsentState());
        e.setConsentVersion(in.getConsentVersion());
        e.setSamplingUnit(in.getSamplingUnit());
        e.setSamplingProbability(in.getSamplingProbability());
        e.setProducerName(in.getProducerName());
        e.setProducerVersion(in.getProducerVersion());
        return e;
    }

    private static String writeProps(Map<String, Object> props, ObjectMapper objectMapper) {
        if (props == null || props.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(props);
        } catch (Exception ignored) {
            return null;
        }
    }
}
