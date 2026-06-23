package com.auraboot.framework.behavior.ingest;

import com.auraboot.framework.behavior.dto.BehaviorEventInput;
import com.auraboot.framework.behavior.entity.BehaviorQuarantine;
import com.auraboot.framework.behavior.mapper.BehaviorQuarantineMapper;
import com.auraboot.framework.infrastructure.mq.memory.InMemoryMqProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Verifies the quarantine consumer's wiring through the real in-memory MQ: after it subscribes,
 * a message on the quarantine topic is deserialized and persisted to the quarantine sink with
 * reason, identity fields lifted from the event, and the raw event retained for replay.
 */
class BehaviorQuarantineConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void subscribedConsumer_persistsQuarantineToSink_withReasonAndRawEvent() throws Exception {
        InMemoryMqProvider mq = new InMemoryMqProvider();
        BehaviorQuarantineMapper mapper = mock(BehaviorQuarantineMapper.class);
        BehaviorQuarantineConsumer consumer = new BehaviorQuarantineConsumer(mq, mapper, objectMapper);
        consumer.subscribe();

        BehaviorEventInput bad = new BehaviorEventInput();
        bad.setEventName("oops");
        bad.setAnonId("anon-9");
        String body = objectMapper.writeValueAsString(
                new BehaviorQuarantineEnvelope(99L, null, "malformed_missing_event_id", "event_id is required", bad));

        mq.send(BehaviorIngestPublisher.TOPIC_QUARANTINE, body, Map.of());

        ArgumentCaptor<BehaviorQuarantine> cap = ArgumentCaptor.forClass(BehaviorQuarantine.class);
        verify(mapper).insert(cap.capture());
        BehaviorQuarantine q = cap.getValue();
        assertThat(q.getTenantId()).isEqualTo(99L);
        assertThat(q.getReason()).isEqualTo("malformed_missing_event_id");
        assertThat(q.getDetail()).isEqualTo("event_id is required");
        assertThat(q.getEventName()).isEqualTo("oops");
        assertThat(q.getAnonId()).isEqualTo("anon-9");
        assertThat(q.getEventId()).isNull();
        assertThat(q.getRawEvent()).contains("oops"); // raw event retained as jsonb text
    }
}
