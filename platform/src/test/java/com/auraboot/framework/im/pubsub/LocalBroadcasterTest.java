package com.auraboot.framework.im.pubsub;

import com.auraboot.framework.im.dto.WsFrame;
import com.auraboot.framework.im.websocket.ImSessionRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LocalBroadcasterTest {

    @Mock
    private ImSessionRegistry sessionRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LocalBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        broadcaster = new LocalBroadcaster(sessionRegistry, objectMapper);
    }

    @Test
    void publish_sendsToOpenSessionsOnly() throws Exception {
        WebSocketSession openSession = mock(WebSocketSession.class);
        WebSocketSession closedSession = mock(WebSocketSession.class);
        when(openSession.isOpen()).thenReturn(true);
        when(closedSession.isOpen()).thenReturn(false);
        when(sessionRegistry.getSessions(1L)).thenReturn(List.of(openSession, closedSession));

        WsFrame frame = WsFrame.builder().type("MESSAGE").requestId("r1").data("hi").build();
        broadcaster.publish(List.of(1L), frame);

        verify(openSession).sendMessage(any(TextMessage.class));
        verify(closedSession, never()).sendMessage(any());
    }

    @Test
    void publish_swallowsIOException() throws Exception {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.isOpen()).thenReturn(true);
        doThrow(new IOException("io")).when(s).sendMessage(any(TextMessage.class));
        when(sessionRegistry.getSessions(2L)).thenReturn(List.of(s));

        WsFrame frame = WsFrame.builder().type("MESSAGE").build();
        broadcaster.publish(List.of(2L), frame);

        verify(s).sendMessage(any(TextMessage.class));
    }

    @Test
    void publish_returnsEarly_whenSerializationFails() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any(WsFrame.class)))
                .thenThrow(new JsonProcessingException("boom") {});
        LocalBroadcaster b = new LocalBroadcaster(sessionRegistry, failingMapper);

        b.publish(List.of(3L), WsFrame.builder().type("X").build());

        verify(sessionRegistry, never()).getSessions(anyLong());
    }

    @Test
    void publish_emptyTargets_noLookup() {
        broadcaster.publish(List.of(), WsFrame.builder().type("MESSAGE").build());
        verifyNoInteractions(sessionRegistry);
    }

    @Test
    void publishToUser_delegatesToPublishWithListOfOne() throws Exception {
        when(sessionRegistry.getSessions(7L)).thenReturn(List.of());
        broadcaster.publishToUser(7L, WsFrame.builder().type("MESSAGE").build());
        verify(sessionRegistry).getSessions(7L);
    }
}
