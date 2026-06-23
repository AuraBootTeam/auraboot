package com.auraboot.framework.behavior.ingest;

import com.auraboot.framework.infrastructure.mq.MqProvider;
import com.auraboot.framework.observability.W3cTraceparent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Subscribes to {@code aura.behavior.events.v1} and persists each batch via the persister.
 * With the memory MQ provider delivery is synchronous (equivalent to the old in-request path);
 * with the kafka provider it runs asynchronously on a consumer thread.
 *
 * <p>An unparseable envelope (a producer bug) is logged and dropped — retrying cannot help.
 * A transient persistence failure propagates out so the transport (kafka) can retry and, after
 * {@code maxAttempts}, route the message to its dead-letter topic.
 */
@Slf4j
@Component
public class BehaviorIngestConsumer {

    public static final String CONSUMER_GROUP = "aura-behavior-ingest";

    private final MqProvider mqProvider;
    private final BehaviorEventPersister persister;
    private final ObjectMapper objectMapper;
    private final BehaviorIngestMetrics metrics;
    private final String group;

    @Autowired
    public BehaviorIngestConsumer(MqProvider mqProvider,
                                  BehaviorEventPersister persister,
                                  ObjectMapper objectMapper,
                                  BehaviorIngestMetrics metrics) {
        this(mqProvider, persister, objectMapper, metrics, CONSUMER_GROUP);
    }

    BehaviorIngestConsumer(MqProvider mqProvider,
                           BehaviorEventPersister persister,
                           ObjectMapper objectMapper) {
        this(mqProvider, persister, objectMapper, BehaviorIngestMetrics.noop(), CONSUMER_GROUP);
    }

    BehaviorIngestConsumer(MqProvider mqProvider,
                           BehaviorEventPersister persister,
                           ObjectMapper objectMapper,
                           String group) {
        this(mqProvider, persister, objectMapper, BehaviorIngestMetrics.noop(), group);
    }

    BehaviorIngestConsumer(MqProvider mqProvider,
                           BehaviorEventPersister persister,
                           ObjectMapper objectMapper,
                           BehaviorIngestMetrics metrics,
                           String group) {
        this.mqProvider = mqProvider;
        this.persister = persister;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.group = group;
    }

    @PostConstruct
    public void subscribe() {
        mqProvider.subscribe(BehaviorIngestPublisher.TOPIC_EVENTS, group, this::onMessage);
        metrics.recordConsumerLag(BehaviorIngestPublisher.TOPIC_EVENTS, group, 0);
        log.info("Behavior ingest consumer subscribed: topic={}, group={}",
                BehaviorIngestPublisher.TOPIC_EVENTS, group);
    }

    void onMessage(String topic, String body, Map<String, String> headers) {
        BehaviorIngestEnvelope env;
        try {
            env = objectMapper.readValue(body, BehaviorIngestEnvelope.class);
        } catch (JsonProcessingException e) {
            log.error("Dropping unparseable behavior ingest envelope: {}", e.getMessage());
            metrics.recordConsumerLag(topic, group, 0);
            return;
        }
        backfillTraceparent(env, headers);
        persister.persistBatch(env);
        metrics.recordConsumerLag(topic, group, 0);
    }

    private void backfillTraceparent(BehaviorIngestEnvelope env, Map<String, String> headers) {
        if (env == null || env.events() == null || headers == null) {
            return;
        }
        W3cTraceparent.TraceIds ids = W3cTraceparent.parse(headers.get(W3cTraceparent.HEADER));
        if (ids == null) {
            return;
        }
        for (var event : env.events()) {
            if (event == null) {
                continue;
            }
            if (event.getTraceId() == null || event.getTraceId().isBlank()) {
                event.setTraceId(ids.traceId());
            }
            if (event.getSourceSpanId() == null || event.getSourceSpanId().isBlank()) {
                event.setSourceSpanId(ids.spanId());
            }
        }
    }
}
