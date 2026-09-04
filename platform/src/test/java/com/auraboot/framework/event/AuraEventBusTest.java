package com.auraboot.framework.event;

import com.auraboot.framework.infrastructure.mq.MqProvider;
import com.auraboot.framework.observability.W3cTraceparent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuraEventBusTest {

    @Mock
    private ApplicationEventPublisher springPublisher;

    private AuraEventBus eventBus;

    static class OrderCreatedEvent extends AuraEvent {
        OrderCreatedEvent(Long tenantId, String recordId, Map<String, Object> payload) {
            super(tenantId, "order:created", "order", recordId, payload);
        }
    }

    @BeforeEach
    void setUp() {
        eventBus = new AuraEventBus(springPublisher);
    }

    @Test
    void publishShouldDelegateToSpring() {
        var event = new OrderCreatedEvent(1L, "123", Map.of("title", "Test"));
        eventBus.publish(event);

        var captor = ArgumentCaptor.forClass(AuraEvent.class);
        verify(springPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("order:created");
        assertThat(captor.getValue().getTenantId()).isEqualTo(1L);
    }

    @Test
    void publishShouldPropagateSpringPublisherException() {
        doThrow(new RuntimeException("boom")).when(springPublisher).publishEvent(any());
        var event = new OrderCreatedEvent(1L, "123", Map.of());
        assertThrows(RuntimeException.class, () -> eventBus.publish(event));
    }

    @Test
    void publishShouldIgnoreNullEvent() {
        assertDoesNotThrow(() -> eventBus.publish(null));
        verify(springPublisher, never()).publishEvent(any());
    }

    @Test
    void mqBridgeInjectsTraceparentFromCurrentSpan() {
        MqProvider mqProvider = mock(MqProvider.class);
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext traceContext = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(traceContext);
        when(traceContext.traceId()).thenReturn("0af7651916cd43dd8448eb211c80319c");
        when(traceContext.spanId()).thenReturn("b7ad6b7169203331");
        when(traceContext.sampled()).thenReturn(true);
        ReflectionTestUtils.setField(eventBus, "mqBridgeEnabled", true);
        ReflectionTestUtils.setField(eventBus, "mqProvider", mqProvider);
        ReflectionTestUtils.setField(eventBus, "objectMapper", new ObjectMapper().findAndRegisterModules());
        ReflectionTestUtils.setField(eventBus, "tracer", tracer);

        eventBus.publish(new OrderCreatedEvent(1L, "123", Map.of("title", "Test")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(mqProvider).send(eq("aura.event.order:created"), anyString(), headers.capture());
        assertThat(headers.getValue()).containsEntry(W3cTraceparent.HEADER,
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
    }
}
