package com.auraboot.framework.im.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tracks online users and their WebSocket sessions.
 * A user can have multiple sessions (multiple devices/tabs).
 */
@Component
public class ImSessionRegistry {

    // userId -> list of active sessions
    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    public void register(Long userId, WebSocketSession session) {
        sessions.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(session);
    }

    public void unregister(Long userId, WebSocketSession session) {
        CopyOnWriteArrayList<WebSocketSession> userSessions = sessions.get(userId);
        if (userSessions != null) {
            userSessions.remove(session);
            if (userSessions.isEmpty()) {
                sessions.remove(userId);
            }
        }
    }

    public List<WebSocketSession> getSessions(Long userId) {
        CopyOnWriteArrayList<WebSocketSession> userSessions = sessions.get(userId);
        return userSessions != null ? List.copyOf(userSessions) : List.of();
    }

    public boolean isOnline(Long userId) {
        CopyOnWriteArrayList<WebSocketSession> userSessions = sessions.get(userId);
        return userSessions != null && !userSessions.isEmpty();
    }

    public Collection<Long> getOnlineUserIds() {
        return sessions.keySet();
    }
}
