package com.auraboot.framework.behavior.ingest;

import com.auraboot.framework.infrastructure.mq.MqProvider;
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

    private static final String GROUP = "aura-behavior-ingest";

    private final MqProvider mqProvider;
    private final BehaviorEventPersister persister;
    private final ObjectMapper objectMapper;
    private final String group;

    @Autowired
    public BehaviorIngestConsumer(MqProvider mqProvider,
                                  BehaviorEventPersister persister,
                                  ObjectMapper objectMapper) {
        this(mqProvider, persister, objectMapper, GROUP);
    }

    BehaviorIngestConsumer(MqProvider mqProvider,
                           BehaviorEventPersister persister,
                           ObjectMapper objectMapper,
                           String group) {
        this.mqProvider = mqProvider;
        this.persister = persister;
        this.objectMapper = objectMapper;
        this.group = group;
    }

    @PostConstruct
    public void subscribe() {
        mqProvider.subscribe(BehaviorIngestPublisher.TOPIC_EVENTS, group, this::onMessage);
        log.info("Behavior ingest consumer subscribed: topic={}, group={}",
                BehaviorIngestPublisher.TOPIC_EVENTS, group);
    }

    void onMessage(String topic, String body, Map<String, String> headers) {
        BehaviorIngestEnvelope env;
        try {
            env = objectMapper.readValue(body, BehaviorIngestEnvelope.class);
        } catch (JsonProcessingException e) {
            log.error("Dropping unparseable behavior ingest envelope: {}", e.getMessage());
            return;
        }
        persister.persistBatch(env);
    }
}
