package com.auraboot.framework.notification.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("NotificationSseServiceImpl")
class NotificationSseServiceImplTest {

    private NotificationSseServiceImpl service;
    private MockedStatic<MetaContext> metaContextMock;

    @BeforeEach
    void setUp() {
        service = new NotificationSseServiceImpl();
        metaContextMock = Mockito.mockStatic(MetaContext.class);
    }

    @AfterEach
    void tearDown() {
        if (metaContextMock != null) metaContextMock.close();
    }

    @Test
    @DisplayName("subscribe rejects when current user differs from target")
    void subscribeCrossUser() {
        metaContextMock.when(MetaContext::getCurrentUserId).thenReturn(2L);
        assertThrows(SecurityException.class, () -> service.subscribe(1L));
    }

    @Test
    @DisplayName("subscribe allows when current user matches target")
    void subscribeMatch() {
        metaContextMock.when(MetaContext::getCurrentUserId).thenReturn(7L);
        SseEmitter e = service.subscribe(7L);
        assertNotNull(e);
        assertEquals(1, service.getActiveConnectionCount(7L));
        assertEquals(1, service.getTotalActiveConnections());
    }

    @Test
    @DisplayName("subscribe allows when MetaContext returns null currentUserId")
    void subscribeNoContext() {
        metaContextMock.when(MetaContext::getCurrentUserId).thenReturn(null);
        SseEmitter e = service.subscribe(7L);
        assertNotNull(e);
    }

    @Test
    @DisplayName("subscribe enforces MAX_CONNECTIONS_PER_USER limit by completing oldest")
    void subscribeLimitsConnections() {
        metaContextMock.when(MetaContext::getCurrentUserId).thenReturn(null);
        for (int i = 0; i < 5; i++) {
            service.subscribe(7L);
        }
        // limit is 3
        assertEquals(3, service.getActiveConnectionCount(7L));
    }

    @Test
    @DisplayName("pushUnreadCount no-op when no active connection")
    void pushUnreadNone() {
        // should not throw
        service.pushUnreadCount(123L, 5);
        assertEquals(0, service.getActiveConnectionCount(123L));
    }

    @Test
    @DisplayName("pushUnreadCount reaches active connections without error")
    void pushUnreadActive() {
        metaContextMock.when(MetaContext::getCurrentUserId).thenReturn(null);
        service.subscribe(7L);
        service.pushUnreadCount(7L, 3);
        assertEquals(1, service.getActiveConnectionCount(7L));
    }

    @Test
    @DisplayName("removeEmitter cleans up empty list and decrements count")
    void removeEmitterCleansUp() {
        metaContextMock.when(MetaContext::getCurrentUserId).thenReturn(null);
        SseEmitter e = service.subscribe(7L);
        assertEquals(1, service.getActiveConnectionCount(7L));
        service.removeEmitter(7L, e);
        assertEquals(0, service.getActiveConnectionCount(7L));
        // subsequent removal of unknown user is a no-op
        service.removeEmitter(999L, e);
    }

    @Test
    @DisplayName("sendHeartbeat is no-op when no users connected")
    void heartbeatEmpty() {
        service.sendHeartbeat();
        assertEquals(0, service.getTotalActiveConnections());
    }

    @Test
    @DisplayName("sendHeartbeat iterates user emitters when connections present")
    void heartbeatBroadcasts() {
        metaContextMock.when(MetaContext::getCurrentUserId).thenReturn(null);
        service.subscribe(7L);
        service.subscribe(8L);
        service.sendHeartbeat();
        assertEquals(2, service.getTotalActiveConnections());
    }
}
