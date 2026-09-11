package com.auraboot.framework.behavior.ingest;

import com.auraboot.framework.behavior.dto.BehaviorEventInput;
import com.auraboot.framework.infrastructure.mq.memory.InMemoryMqProvider;
import com.auraboot.framework.observability.W3cTraceparent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void onMessage_backfillsEventTraceFieldsFromTraceparentHeader() throws Exception {
        BehaviorEventPersister persister = mock(BehaviorEventPersister.class);
        BehaviorIngestConsumer consumer = new BehaviorIngestConsumer(new InMemoryMqProvider(), persister, objectMapper);
        BehaviorEventInput e = new BehaviorEventInput();
        e.setEventId("01TRACE");
        e.setEventName("agent.task.completed");
        String body = objectMapper.writeValueAsString(new BehaviorIngestEnvelope(42L, 7L, List.of(e)));

        consumer.onMessage(BehaviorIngestPublisher.TOPIC_EVENTS, body,
                Map.of(W3cTraceparent.HEADER,
                        "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"));

        ArgumentCaptor<BehaviorIngestEnvelope> cap = ArgumentCaptor.forClass(BehaviorIngestEnvelope.class);
        verify(persister).persistBatch(cap.capture());
        BehaviorEventInput traced = cap.getValue().events().get(0);
        assertThat(traced.getTraceId()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(traced.getSourceSpanId()).isEqualTo("b7ad6b7169203331");
    }

    @Test
    void onMessageRunsPersistenceInsideConsumerChildSpan() throws Exception {
        BehaviorEventPersister persister = mock(BehaviorEventPersister.class);
        Tracer tracer = mock(Tracer.class);
        TraceContext.Builder parentBuilder = mock(TraceContext.Builder.class);
        TraceContext parent = mock(TraceContext.class);
        Span.Builder spanBuilder = mock(Span.Builder.class);
        Span consumerSpan = mock(Span.class);
        Tracer.SpanInScope scope = mock(Tracer.SpanInScope.class);
        when(tracer.traceContextBuilder()).thenReturn(parentBuilder);
        when(parentBuilder.traceId("0af7651916cd43dd8448eb211c80319c")).thenReturn(parentBuilder);
        when(parentBuilder.spanId("b7ad6b7169203331")).thenReturn(parentBuilder);
        when(parentBuilder.sampled(true)).thenReturn(parentBuilder);
        when(parentBuilder.build()).thenReturn(parent);
        when(tracer.spanBuilder()).thenReturn(spanBuilder);
        when(spanBuilder.name("behavior.ingest.consume")).thenReturn(spanBuilder);
        when(spanBuilder.kind(Span.Kind.CONSUMER)).thenReturn(spanBuilder);
        when(spanBuilder.tag(anyString(), anyString())).thenReturn(spanBuilder);
        when(spanBuilder.setParent(parent)).thenReturn(spanBuilder);
        when(spanBuilder.start()).thenReturn(consumerSpan);
        when(tracer.withSpan(consumerSpan)).thenReturn(scope);
        BehaviorIngestConsumer consumer = new BehaviorIngestConsumer(
                new InMemoryMqProvider(), persister, objectMapper,
                BehaviorIngestMetrics.noop(), tracer, BehaviorIngestConsumer.CONSUMER_GROUP);
        BehaviorEventInput event = new BehaviorEventInput();
        event.setEventId("01CHILD");
        event.setEventName("agent.task.completed");
        String body = objectMapper.writeValueAsString(new BehaviorIngestEnvelope(42L, 7L, List.of(event)));

        consumer.onMessage(BehaviorIngestPublisher.TOPIC_EVENTS, body,
                Map.of(W3cTraceparent.HEADER,
                        "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"));

        verify(spanBuilder).setParent(parent);
        verify(spanBuilder).kind(Span.Kind.CONSUMER);
        InOrder order = inOrder(tracer, persister, scope, consumerSpan);
        order.verify(tracer).withSpan(consumerSpan);
        order.verify(persister).persistBatch(any());
        order.verify(scope).close();
        order.verify(consumerSpan).end();
    }
}
