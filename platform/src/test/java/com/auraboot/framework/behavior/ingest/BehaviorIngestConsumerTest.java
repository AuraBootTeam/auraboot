package com.auraboot.framework.behavior.ingest;

import com.auraboot.framework.behavior.dto.BehaviorEventInput;
import com.auraboot.framework.infrastructure.mq.memory.InMemoryMqProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Verifies the ingest consumer's wiring through the real in-memory MQ: after it subscribes,
 * a message published to the events topic is deserialized into an envelope and handed to the
 * persister with the resolved tenant/user intact.
 */
class BehaviorIngestConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void subscribedConsumer_deserializesEnvelope_andPersistsBatch() throws Exception {
        InMemoryMqProvider mq = new InMemoryMqProvider();
        BehaviorEventPersister persister = mock(BehaviorEventPersister.class);
        BehaviorIngestConsumer consumer = new BehaviorIngestConsumer(mq, persister, objectMapper);
        consumer.subscribe(); // @PostConstruct, invoked directly in the unit test

        BehaviorEventInput e = new BehaviorEventInput();
        e.setEventId("01ABC");
        e.setEventName("page_view");
        String body = objectMapper.writeValueAsString(new BehaviorIngestEnvelope(42L, 7L, List.of(e)));

        mq.send(BehaviorIngestPublisher.TOPIC_EVENTS, body, Map.of());

        ArgumentCaptor<BehaviorIngestEnvelope> cap = ArgumentCaptor.forClass(BehaviorIngestEnvelope.class);
        verify(persister).persistBatch(cap.capture());
        assertThat(cap.getValue().tenantId()).isEqualTo(42L);
        assertThat(cap.getValue().userId()).isEqualTo(7L);
        assertThat(cap.getValue().events()).hasSize(1);
        assertThat(cap.getValue().events().get(0).getEventId()).isEqualTo("01ABC");
    }
}
