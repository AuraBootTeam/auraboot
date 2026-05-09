package com.auraboot.framework.im.pubsub;

import com.auraboot.framework.im.dto.WsFrame;
import com.auraboot.framework.im.websocket.ImSessionRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RedisBroadcasterTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ImSessionRegistry sessionRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RedisBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        broadcaster = new RedisBroadcaster(redisTemplate, sessionRegistry, objectMapper);
    }

    @Test
    void publish_sendsToRedisChannel() {
        WsFrame frame = WsFrame.builder().type("MESSAGE").requestId("r").data("x").build();
        broadcaster.publish(List.of(1L, 2L), frame);
        verify(redisTemplate).convertAndSend(eq(RedisBroadcaster.CHANNEL), anyString());
    }

    @Test
    void publish_swallowsExceptions() {
        doThrow(new RuntimeException("redis down"))
                .when(redisTemplate).convertAndSend(anyString(), anyString());
        // Should not propagate
        broadcaster.publish(List.of(1L),
                WsFrame.builder().type("X").build());
        verify(redisTemplate).convertAndSend(anyString(), anyString());
    }

    @Test
    void onMessage_deliversToLocalSessions() throws Exception {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.isOpen()).thenReturn(true);
        when(sessionRegistry.getSessions(5L)).thenReturn(List.of(s));

        RedisBroadcaster.BroadcastPayload p = new RedisBroadcaster.BroadcastPayload();
        p.setInstanceId("other");
        p.setTargetUserIds(List.of(5L));
        p.setFrame(WsFrame.builder().type("MESSAGE").build());
        String json = objectMapper.writeValueAsString(p);

        Message msg = mock(Message.class);
        when(msg.getBody()).thenReturn(json.getBytes());

        broadcaster.onMessage(msg, null);
        verify(s).sendMessage(any(TextMessage.class));
    }

    @Test
    void onMessage_skipsUserWithNoLocalSessions() {
        when(sessionRegistry.getSessions(99L)).thenReturn(List.of());

        try {
            RedisBroadcaster.BroadcastPayload p = new RedisBroadcaster.BroadcastPayload();
            p.setInstanceId("x");
            p.setTargetUserIds(List.of(99L));
            p.setFrame(WsFrame.builder().type("MESSAGE").build());
            String json = objectMapper.writeValueAsString(p);
            Message msg = mock(Message.class);
            when(msg.getBody()).thenReturn(json.getBytes());

            broadcaster.onMessage(msg, null);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        verify(sessionRegistry).getSessions(99L);
    }

    @Test
    void onMessage_invalidJson_swallowsException() {
        Message msg = mock(Message.class);
        when(msg.getBody()).thenReturn("{not json".getBytes());
        // Should not throw
        broadcaster.onMessage(msg, null);
    }

    @Test
    void onMessage_sessionSendIOException_continues() throws Exception {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.isOpen()).thenReturn(true);
        when(s.getId()).thenReturn("session-1");
        doThrow(new IOException("io")).when(s).sendMessage(any(TextMessage.class));
        when(sessionRegistry.getSessions(8L)).thenReturn(List.of(s));

        RedisBroadcaster.BroadcastPayload p = new RedisBroadcaster.BroadcastPayload();
        p.setTargetUserIds(List.of(8L));
        p.setFrame(WsFrame.builder().type("MESSAGE").build());
        String json = objectMapper.writeValueAsString(p);
        Message msg = mock(Message.class);
        when(msg.getBody()).thenReturn(json.getBytes());

        broadcaster.onMessage(msg, null);
        verify(s).sendMessage(any(TextMessage.class));
    }

    @Test
    void broadcastPayload_getterSetter() {
        RedisBroadcaster.BroadcastPayload p = new RedisBroadcaster.BroadcastPayload();
        p.setInstanceId("a");
        p.setTargetUserIds(List.of(1L));
        WsFrame f = WsFrame.builder().type("X").build();
        p.setFrame(f);
        assert p.getInstanceId().equals("a");
        assert p.getTargetUserIds().equals(List.of(1L));
        assert p.getFrame() == f;
    }
}
