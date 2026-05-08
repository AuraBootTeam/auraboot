package com.auraboot.framework.im.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ImSessionRegistry}.
 *
 * <p>The registry is a thread-safe in-memory lookup of userId -> sessions.
 * Tests exercise the full register/unregister/lookup lifecycle without any
 * real WebSocket I/O.
 */
@ExtendWith(MockitoExtension.class)
class ImSessionRegistryTest {

    private ImSessionRegistry registry;

    @Mock
    private WebSocketSession sessionA;

    @Mock
    private WebSocketSession sessionB;

    @Mock
    private WebSocketSession sessionC;

    @BeforeEach
    void setUp() {
        registry = new ImSessionRegistry();
    }

    @Test
    void register_singleSession_isOnlineAndReturnsSession() {
        registry.register(100L, sessionA);

        assertThat(registry.isOnline(100L)).isTrue();
        assertThat(registry.getSessions(100L)).containsExactly(sessionA);
        assertThat(registry.getOnlineUserIds()).contains(100L);
    }

    @Test
    void register_multipleSessionsForSameUser_allTracked() {
        registry.register(100L, sessionA);
        registry.register(100L, sessionB);

        List<WebSocketSession> sessions = registry.getSessions(100L);
        assertThat(sessions).containsExactlyInAnyOrder(sessionA, sessionB);
        assertThat(registry.isOnline(100L)).isTrue();
    }

    @Test
    void register_differentUsers_isolated() {
        registry.register(100L, sessionA);
        registry.register(200L, sessionB);

        assertThat(registry.getSessions(100L)).containsExactly(sessionA);
        assertThat(registry.getSessions(200L)).containsExactly(sessionB);
        assertThat(registry.getOnlineUserIds()).containsExactlyInAnyOrder(100L, 200L);
    }

    @Test
    void unregister_oneOfMultiple_keepsOthers() {
        registry.register(100L, sessionA);
        registry.register(100L, sessionB);

        registry.unregister(100L, sessionA);

        assertThat(registry.getSessions(100L)).containsExactly(sessionB);
        assertThat(registry.isOnline(100L)).isTrue();
    }

    @Test
    void unregister_lastSession_userRemovedFromOnlineSet() {
        registry.register(100L, sessionA);

        registry.unregister(100L, sessionA);

        assertThat(registry.isOnline(100L)).isFalse();
        assertThat(registry.getSessions(100L)).isEmpty();
        assertThat(registry.getOnlineUserIds()).doesNotContain(100L);
    }

    @Test
    void unregister_unknownUser_doesNothing() {
        // No exception, no state change
        registry.unregister(999L, sessionA);
        assertThat(registry.isOnline(999L)).isFalse();
    }

    @Test
    void unregister_unknownSession_keepsExisting() {
        registry.register(100L, sessionA);
        registry.unregister(100L, sessionC); // not registered

        assertThat(registry.getSessions(100L)).containsExactly(sessionA);
        assertThat(registry.isOnline(100L)).isTrue();
    }

    @Test
    void getSessions_unknownUser_returnsEmptyList() {
        assertThat(registry.getSessions(404L)).isEmpty();
    }

    @Test
    void getSessions_returnsImmutableCopy() {
        registry.register(100L, sessionA);
        List<WebSocketSession> snapshot = registry.getSessions(100L);

        // List.copyOf returns an unmodifiable list
        assertThat(snapshot).isUnmodifiable();
    }

    @Test
    void isOnline_neverRegistered_false() {
        assertThat(registry.isOnline(123L)).isFalse();
    }

    @Test
    void getOnlineUserIds_initiallyEmpty() {
        Collection<Long> ids = registry.getOnlineUserIds();
        assertThat(ids).isEmpty();
    }
}
