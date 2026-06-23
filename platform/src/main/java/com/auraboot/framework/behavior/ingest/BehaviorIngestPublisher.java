package com.auraboot.framework.behavior.ingest;

import com.auraboot.framework.behavior.dto.BehaviorEventInput;
import com.auraboot.framework.infrastructure.mq.MqProvider;
import com.auraboot.framework.observability.W3cTraceparent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes validated behavior-event batches to the ingest topic and routes un-storable
 * events to the quarantine topic, over the platform MQ SPI ({@link MqProvider}). The MQ
 * implementation (memory|kafka|...) is selected by {@code aura.mq.type} — this publisher is
 * provider-agnostic.
 */
@Slf4j
@Component
public class BehaviorIngestPublisher {

    /** SoT §2.7 frozen topic names. */
    public static final String TOPIC_EVENTS = "aura.behavior.events.v1";
    public static final String TOPIC_QUARANTINE = "aura.behavior.quarantine.v1";

    private final MqProvider mqProvider;
    private final ObjectMapper objectMapper;
    private final BehaviorIngestMetrics metrics;

    @Autowired
    public BehaviorIngestPublisher(MqProvider mqProvider,
                                   ObjectMapper objectMapper,
                                   BehaviorIngestMetrics metrics) {
        this.mqProvider = mqProvider;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    BehaviorIngestPublisher(MqProvider mqProvider, ObjectMapper objectMapper) {
        this(mqProvider, objectMapper, BehaviorIngestMetrics.noop());
    }

    /** Publish a validated batch (tenant/user already resolved) to the events topic; returns count enqueued. */
    public int publish(long tenantId, Long userId, List<BehaviorEventInput> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("tenantId", Long.toString(tenantId));
        headers.put("eventCount", Integer.toString(events.size()));
        String traceparent = traceparent(events);
        if (traceparent != null) {
            headers.put(W3cTraceparent.HEADER, traceparent);
        }
        send(TOPIC_EVENTS, new BehaviorIngestEnvelope(tenantId, userId, events), headers);
        metrics.recordEnqueued(TOPIC_EVENTS, events.size());
        return events.size();
    }

    /** Route a single un-storable event to the quarantine topic with a reason. */
    public void publishQuarantine(long tenantId, Long userId, String reason, String detail, BehaviorEventInput event) {
        Map<String, String> headers = new HashMap<>();
        headers.put("tenantId", Long.toString(tenantId));
        headers.put("reason", reason);
        send(TOPIC_QUARANTINE, new BehaviorQuarantineEnvelope(tenantId, userId, reason, detail, event), headers);
        metrics.recordEnqueued(TOPIC_QUARANTINE, 1);
    }

    private void send(String topic, Object payload, Map<String, String> headers) {
        try {
            mqProvider.send(topic, objectMapper.writeValueAsString(payload), headers);
        } catch (JsonProcessingException e) {
            metrics.recordPublishFailure(topic, "serialization");
            // Serialization of our own envelope should never fail; surface loudly rather than drop silently.
            throw new IllegalStateException("Failed to serialize behavior ingest payload for topic " + topic, e);
        } catch (RuntimeException e) {
            metrics.recordPublishFailure(topic, "runtime");
            throw e;
        }
    }

    private String traceparent(List<BehaviorEventInput> events) {
        for (BehaviorEventInput event : events) {
            if (event == null) {
                continue;
            }
            String traceparent = W3cTraceparent.format(event.getTraceId(), event.getSourceSpanId(), true);
            if (traceparent != null) {
                return traceparent;
            }
        }
        return null;
    }
}
