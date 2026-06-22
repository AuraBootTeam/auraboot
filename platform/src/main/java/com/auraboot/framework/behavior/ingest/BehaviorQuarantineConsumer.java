package com.auraboot.framework.behavior.ingest;

import com.auraboot.framework.behavior.dto.BehaviorEventInput;
import com.auraboot.framework.behavior.entity.BehaviorQuarantine;
import com.auraboot.framework.behavior.mapper.BehaviorQuarantineMapper;
import com.auraboot.framework.infrastructure.mq.MqProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Subscribes to {@code aura.behavior.quarantine.v1} and persists each quarantined event to the
 * durable sink {@code ab_behavior_quarantine} (observable + replayable). Runs without request
 * MetaContext — tenant is carried in the envelope and set explicitly.
 */
@Slf4j
@Component
public class BehaviorQuarantineConsumer {

    private static final String GROUP = "aura-behavior-quarantine";

    private final MqProvider mqProvider;
    private final BehaviorQuarantineMapper quarantineMapper;
    private final ObjectMapper objectMapper;
    private final String group;

    @Autowired
    public BehaviorQuarantineConsumer(MqProvider mqProvider,
                                      BehaviorQuarantineMapper quarantineMapper,
                                      ObjectMapper objectMapper) {
        this(mqProvider, quarantineMapper, objectMapper, GROUP);
    }

    BehaviorQuarantineConsumer(MqProvider mqProvider,
                               BehaviorQuarantineMapper quarantineMapper,
                               ObjectMapper objectMapper,
                               String group) {
        this.mqProvider = mqProvider;
        this.quarantineMapper = quarantineMapper;
        this.objectMapper = objectMapper;
        this.group = group;
    }

    @PostConstruct
    public void subscribe() {
        mqProvider.subscribe(BehaviorIngestPublisher.TOPIC_QUARANTINE, group, this::onMessage);
        log.info("Behavior quarantine consumer subscribed: topic={}, group={}",
                BehaviorIngestPublisher.TOPIC_QUARANTINE, group);
    }

    void onMessage(String topic, String body, Map<String, String> headers) {
        BehaviorQuarantineEnvelope env;
        try {
            env = objectMapper.readValue(body, BehaviorQuarantineEnvelope.class);
        } catch (JsonProcessingException e) {
            log.error("Dropping unparseable quarantine envelope: {}", e.getMessage());
            return;
        }
        BehaviorQuarantine q = new BehaviorQuarantine();
        q.setTenantId(env.tenantId());
        q.setUserId(env.userId());
        q.setReason(env.reason());
        q.setDetail(env.detail());
        BehaviorEventInput ev = env.event();
        if (ev != null) {
            q.setEventId(ev.getEventId());
            q.setEventName(ev.getEventName());
            q.setAnonId(ev.getAnonId());
            q.setRawEvent(writeRaw(ev));
        }
        quarantineMapper.insert(q);
    }

    private String writeRaw(BehaviorEventInput ev) {
        try {
            return objectMapper.writeValueAsString(ev);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize quarantined raw event: {}", e.getMessage());
            return null;
        }
    }
}
