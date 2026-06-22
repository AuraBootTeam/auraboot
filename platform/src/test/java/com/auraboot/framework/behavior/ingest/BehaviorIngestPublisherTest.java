package com.auraboot.framework.behavior.ingest;

import com.auraboot.framework.behavior.dto.BehaviorEventInput;
import com.auraboot.framework.infrastructure.mq.memory.InMemoryMqProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the events/quarantine publisher over the real in-memory MQ provider
 * (synchronous same-thread delivery) — verifies the wire envelope shape on each topic,
 * not a mock's call count.
 */
class BehaviorIngestPublisherTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void publish_sendsBatchEnvelopeToEventsTopic_andReturnsEnqueuedCount() throws Exception {
        InMemoryMqProvider mq = new InMemoryMqProvider();
        AtomicReference<String> captured = new AtomicReference<>();
        mq.subscribe(BehaviorIngestPublisher.TOPIC_EVENTS, "test", (t, body, h) -> captured.set(body));
        BehaviorIngestPublisher publisher = new BehaviorIngestPublisher(mq, objectMapper);

        BehaviorEventInput e1 = new BehaviorEventInput();
        e1.setEventId("01ABC");
        e1.setEventName("page_view");
        BehaviorEventInput e2 = new BehaviorEventInput();
        e2.setEventId("01DEF");
        e2.setEventName("click");

        int enqueued = publisher.publish(42L, 7L, List.of(e1, e2));

        assertThat(enqueued).isEqualTo(2);
        JsonNode env = objectMapper.readTree(captured.get());
        assertThat(env.get("tenantId").asLong()).isEqualTo(42L);
        assertThat(env.get("userId").asLong()).isEqualTo(7L);
        assertThat(env.get("events")).hasSize(2);
        assertThat(env.get("events").get(0).get("eventId").asText()).isEqualTo("01ABC");
        assertThat(env.get("events").get(0).get("eventName").asText()).isEqualTo("page_view");
    }

    @Test
    void publish_emptyBatch_isNoOpReturnsZero() {
        InMemoryMqProvider mq = new InMemoryMqProvider();
        AtomicReference<String> captured = new AtomicReference<>();
        mq.subscribe(BehaviorIngestPublisher.TOPIC_EVENTS, "test", (t, body, h) -> captured.set(body));
        BehaviorIngestPublisher publisher = new BehaviorIngestPublisher(mq, objectMapper);

        int enqueued = publisher.publish(1L, null, List.of());

        assertThat(enqueued).isZero();
        assertThat(captured.get()).isNull(); // an empty batch publishes nothing
    }

    @Test
    void publishQuarantine_sendsToQuarantineTopicWithReasonAndEvent() throws Exception {
        InMemoryMqProvider mq = new InMemoryMqProvider();
        AtomicReference<String> captured = new AtomicReference<>();
        mq.subscribe(BehaviorIngestPublisher.TOPIC_QUARANTINE, "test", (t, body, h) -> captured.set(body));
        BehaviorIngestPublisher publisher = new BehaviorIngestPublisher(mq, objectMapper);

        BehaviorEventInput bad = new BehaviorEventInput();
        bad.setEventName("oops"); // missing eventId
        bad.setAnonId("anon-1");

        publisher.publishQuarantine(99L, null, "malformed_missing_event_id", "no event id", bad);

        JsonNode env = objectMapper.readTree(captured.get());
        assertThat(env.get("tenantId").asLong()).isEqualTo(99L);
        assertThat(env.get("reason").asText()).isEqualTo("malformed_missing_event_id");
        assertThat(env.get("detail").asText()).isEqualTo("no event id");
        assertThat(env.get("event").get("eventName").asText()).isEqualTo("oops");
        assertThat(env.get("event").get("anonId").asText()).isEqualTo("anon-1");
    }
}
