package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.auth.entity.UserSession;
import com.auraboot.framework.auth.mapper.UserSessionMapper;
import com.auraboot.framework.exception.RootUnCheckedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SessionManagementServiceImpl")
class SessionManagementServiceImplTest {

    @Mock
    private UserSessionMapper userSessionMapper;

    private SessionManagementServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SessionManagementServiceImpl(userSessionMapper);
    }

    @Test
    @DisplayName("createSession persists session, parses Mobile UA, truncates long UA")
    void createSessionMobileUa() {
        String longUa = "Mozilla/5.0 (iPhone) Mobile/" + "x".repeat(600);
        UserSession s = service.createSession(1L, "token", "1.2.3.4", longUa);
        assertNotNull(s.getPid());
        assertEquals(1L, s.getUserId());
        assertEquals("Mobile", s.getDeviceInfo());
        assertEquals(512, s.getUserAgent().length());
        assertFalse(Boolean.TRUE.equals(s.getRevoked()));
        verify(userSessionMapper).insert(any(UserSession.class));
    }

    @Test
    @DisplayName("createSession parses tablet UA")
    void createSessionTabletUa() {
        UserSession s = service.createSession(1L, "t", null, "iPad; Tablet");
        assertEquals("Tablet", s.getDeviceInfo());
    }

    @Test
    @DisplayName("createSession parses desktop UA when no mobile/tablet markers")
    void createSessionDesktopUa() {
        UserSession s = service.createSession(1L, "t", null, "Mozilla Linux X11");
        assertEquals("Desktop", s.getDeviceInfo());
    }

    @Test
    @DisplayName("createSession parses Android non-tablet as Mobile")
    void createSessionAndroidMobileUa() {
        UserSession s = service.createSession(1L, "t", null, "Linux; Android 11; Mobile");
        assertEquals("Mobile", s.getDeviceInfo());
    }

    @Test
    @DisplayName("createSession parses Unknown when UA blank")
    void createSessionBlankUa() {
        UserSession s = service.createSession(1L, "t", null, "");
        assertEquals("Unknown", s.getDeviceInfo());
    }

    @Test
    @DisplayName("createSession swallows DuplicateKeyException")
    void createSessionDuplicateKeySwallowed() {
        doThrow(new DuplicateKeyException("dup")).when(userSessionMapper).insert(any(UserSession.class));
        UserSession s = service.createSession(1L, "tk", null, null);
        assertNotNull(s);
    }

    @Test
    @DisplayName("isSessionValid returns true for non-revoked session")
    void isSessionValidTrue() {
        UserSession s = new UserSession();
        s.setRevoked(false);
        when(userSessionMapper.findByTokenHash(any())).thenReturn(s);
        assertTrue(service.isSessionValid("any"));
    }

    @Test
    @DisplayName("isSessionValid returns false when missing or revoked")
    void isSessionValidFalse() {
        when(userSessionMapper.findByTokenHash(any())).thenReturn(null);
        assertFalse(service.isSessionValid("any"));

        UserSession revoked = new UserSession();
        revoked.setRevoked(true);
        when(userSessionMapper.findByTokenHash(any())).thenReturn(revoked);
        assertFalse(service.isSessionValid("any"));
    }

    @Test
    @DisplayName("revokeSession throws when no matching session")
    void revokeSessionNotFound() {
        when(userSessionMapper.findActiveByUserId(1L)).thenReturn(List.of());
        assertThrows(RootUnCheckedException.class, () -> service.revokeSession(1L, "missing"));
    }

    @Test
    @DisplayName("revokeSession revokes by id when matching pid found")
    void revokeSessionFound() {
        UserSession s = new UserSession();
        s.setId(99L);
        s.setPid("p1");
        when(userSessionMapper.findActiveByUserId(1L)).thenReturn(List.of(s));
        service.revokeSession(1L, "p1");
        verify(userSessionMapper).revokeSession(99L);
    }

    @Test
    @DisplayName("revokeAllSessions delegates to mapper")
    void revokeAllSessionsDelegates() {
        when(userSessionMapper.revokeAllSessions(1L)).thenReturn(3);
        service.revokeAllSessions(1L);
        verify(userSessionMapper).revokeAllSessions(1L);
    }

    @Test
    @DisplayName("getActiveSessions delegates to mapper")
    void getActiveSessionsDelegates() {
        when(userSessionMapper.findActiveByUserId(1L)).thenReturn(List.of());
        assertEquals(List.of(), service.getActiveSessions(1L));
    }

    @Test
    @DisplayName("updateLastActive updates DB once and throttles subsequent updates")
    void updateLastActiveThrottles() {
        UserSession s = new UserSession();
        s.setId(7L);
        when(userSessionMapper.findByTokenHash(any())).thenReturn(s);
        service.updateLastActive("token");
        service.updateLastActive("token"); // should be throttled
        verify(userSessionMapper, times(1)).updateLastActive(7L);
    }

    @Test
    @DisplayName("updateLastActive does nothing when session not found")
    void updateLastActiveSessionNotFound() {
        when(userSessionMapper.findByTokenHash(any())).thenReturn(null);
        service.updateLastActive("token-2");
        verify(userSessionMapper, never()).updateLastActive(any());
    }

    @Test
    @DisplayName("cleanUpThrottleMap removes expired entries")
    @SuppressWarnings("unchecked")
    void cleanUpThrottleRemovesExpired() throws Exception {
        Field f = SessionManagementServiceImpl.class.getDeclaredField("lastActiveThrottle");
        f.setAccessible(true);
        ConcurrentHashMap<String, Instant> throttle = (ConcurrentHashMap<String, Instant>) f.get(service);
        throttle.put("old", Instant.now().minus(Duration.ofMinutes(30)));
        throttle.put("fresh", Instant.now());
        service.cleanUpThrottleMap();
        assertFalse(throttle.containsKey("old"));
        assertTrue(throttle.containsKey("fresh"));
    }
}
