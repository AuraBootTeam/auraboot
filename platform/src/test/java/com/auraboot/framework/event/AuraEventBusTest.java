package com.auraboot.framework.event;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

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
}
