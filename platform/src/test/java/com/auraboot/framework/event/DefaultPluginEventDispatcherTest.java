package com.auraboot.framework.event;

import com.auraboot.framework.plugin.extension.EventListenerExtension;
import com.auraboot.framework.plugin.extension.EventListenerExtension.EventContext;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DefaultPluginEventDispatcherTest {

    @Mock
    private ExtensionRegistry extensionRegistry;

    @InjectMocks
    private DefaultPluginEventDispatcher dispatcher;

    static class TestEvent extends AuraEvent {
        TestEvent() {
            super(1L, "order:created", "order", "123", Map.of("title", "Test"));
        }
    }

    @Test
    void shouldDispatchToMatchingListeners() {
        var mockListener = mock(EventListenerExtension.class);
        when(extensionRegistry.getEventListeners("order:created")).thenReturn(List.of(mockListener));

        dispatcher.dispatch(new TestEvent());

        var captor = ArgumentCaptor.forClass(EventContext.class);
        verify(mockListener).onEvent(captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo("order:created");
        assertThat(captor.getValue().tenantId()).isEqualTo(1L);
        assertThat(captor.getValue().sourceModel()).isEqualTo("order");
        assertThat(captor.getValue().recordId()).isEqualTo("123");
    }

    @Test
    void shouldNotFailWhenListenerThrows() {
        var badListener = mock(EventListenerExtension.class);
        doThrow(new RuntimeException("plugin error")).when(badListener).onEvent(any());
        when(extensionRegistry.getEventListeners("order:created")).thenReturn(List.of(badListener));

        assertDoesNotThrow(() -> dispatcher.dispatch(new TestEvent()));
    }

    @Test
    void shouldSkipWhenNoListeners() {
        when(extensionRegistry.getEventListeners("order:created")).thenReturn(List.of());
        assertDoesNotThrow(() -> dispatcher.dispatch(new TestEvent()));
    }

    @Test
    void shouldContinueWhenOneListenerFails() {
        var badListener = mock(EventListenerExtension.class);
        doThrow(new RuntimeException("fail")).when(badListener).onEvent(any());
        var goodListener = mock(EventListenerExtension.class);
        when(extensionRegistry.getEventListeners("order:created"))
                .thenReturn(List.of(badListener, goodListener));

        dispatcher.dispatch(new TestEvent());

        verify(goodListener).onEvent(any(EventContext.class));
    }
}
