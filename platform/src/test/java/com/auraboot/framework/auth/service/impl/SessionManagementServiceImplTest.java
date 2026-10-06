package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.auth.entity.UserSession;
import com.auraboot.framework.audit.entity.AdminEventLog;
import com.auraboot.framework.audit.service.AdminEventLogService;
import com.auraboot.framework.auth.mapper.UserSessionMapper;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SessionManagementServiceImpl")
class SessionManagementServiceImplTest {

    @Mock
    private UserSessionMapper userSessionMapper;
    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private AdminEventLogService adminEventLogService;

    private SessionManagementServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SessionManagementServiceImpl(userSessionMapper);
        lenient().when(userSessionMapper.insertIfAbsent(any(UserSession.class))).thenReturn(1);
    }

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    void contextlessExpiryScopesScanRevocationAndAuditAcrossTenants() {
        MetaContext.clear();
        UserSession first = expiredSession(8L, 12L, 77L);
        UserSession second = expiredSession(9L, 13L, 78L);
        UserSession alreadyRevoked = expiredSession(10L, 14L, 79L);
        when(userSessionMapper.findExpiredImpersonationSessions()).thenAnswer(invocation -> {
            assertTrue(MetaContext.isTenantFilterBypassed(), "scheduler scan must declare auth-plane scope");
            return List.of(first, second, alreadyRevoked);
        });
        when(userSessionMapper.revokeExpiredSession(any())).thenAnswer(invocation -> {
            assertTrue(MetaContext.isTenantFilterBypassed(), "revocation must remain inside auth-plane scope");
            return invocation.<Long>getArgument(0).equals(10L) ? 0 : 1;
        });
        ReflectionTestUtils.setField(service, "adminEventLogService", adminEventLogService);
        doAnswer(invocation -> {
            assertTrue(MetaContext.isTenantFilterBypassed(), "terminal audit must share the scoped operation");
            AdminEventLog event = invocation.getArgument(0);
            long id = event.getResourcePid().equals("expired-8") ? 8L : 9L;
            assertEquals(id == 8L ? 12L : 13L, event.getTenantId());
            assertEquals(id == 8L ? 77L : 78L, event.getActorUserId());
            assertEquals("impersonation.expired", event.getActionType());
            return null;
        }).when(adminEventLogService).record(any(AdminEventLog.class));

        service.expireImpersonationSessions();

        verify(adminEventLogService, times(2)).record(any(AdminEventLog.class));
        assertFalse(MetaContext.isTenantFilterBypassed());
        assertFalse(MetaContext.exists(), "scheduler must not invent a tenant identity");
    }

    @Test
    void expiryRestoresCallerIdentityAndScopeWhenScanThrows() {
        MetaContext.restore(new MetaContext.Snapshot(42L, 43L, "caller", "caller", java.util.Set.of(),
                null, null, null, null));
        MetaContext.Snapshot before = MetaContext.snapshot();
        IllegalStateException failure = new IllegalStateException("scan failed");
        when(userSessionMapper.findExpiredImpersonationSessions()).thenAnswer(invocation -> {
            assertTrue(MetaContext.isTenantFilterBypassed());
            throw failure;
        });

        assertSame(failure, assertThrows(IllegalStateException.class, service::expireImpersonationSessions));

        assertEquals(before, MetaContext.snapshot());
        assertFalse(MetaContext.isTenantFilterBypassed());
        verify(adminEventLogService, never()).record(any(AdminEventLog.class));
    }

    @Test
    void expiryPreservesOuterBypassWhenRevocationThrows() {
        when(userSessionMapper.findExpiredImpersonationSessions()).thenReturn(List.of(expiredSession(8L, 12L, 77L)));
        IllegalStateException failure = new IllegalStateException("revocation failed");
        when(userSessionMapper.revokeExpiredSession(8L)).thenThrow(failure);
        MetaContext.runWithoutTenantFilter(() -> {
            assertSame(failure, assertThrows(IllegalStateException.class, service::expireImpersonationSessions));
            assertTrue(MetaContext.isTenantFilterBypassed());
        });
        assertFalse(MetaContext.isTenantFilterBypassed());
        verify(adminEventLogService, never()).record(any(AdminEventLog.class));
    }

    private UserSession expiredSession(Long id, Long tenantId, Long initiatorId) {
        UserSession session = new UserSession();
        session.setId(id);
        session.setPid("expired-" + id);
        session.setTenantId(tenantId);
        session.setInitiatedByUserId(initiatorId);
        return session;
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
        verify(userSessionMapper).insertIfAbsent(any(UserSession.class));
    }

    @Test
    @DisplayName("createSession persists the effective tenant and Party Actor context")
    void createSessionPersistsExecutionContext() {
        ReflectionTestUtils.setField(service, "jwtUtil", jwtUtil);
        when(jwtUtil.extractApplicationId("actor-token")).thenReturn(10L);
        when(jwtUtil.extractLoginChannelId("actor-token")).thenReturn(11L);
        when(jwtUtil.extractTenantId("actor-token")).thenReturn(12L);
        when(jwtUtil.extractMemberId("actor-token")).thenReturn(13L);
        when(jwtUtil.extractExecutionScope("actor-token")).thenReturn("party");
        when(jwtUtil.extractActorPartyId("actor-token")).thenReturn(14L);
        when(jwtUtil.extractPartyMembershipId("actor-token")).thenReturn(15L);
        when(jwtUtil.extractSessionStage("actor-token")).thenReturn("ready");
        when(jwtUtil.extractContextVersion("actor-token")).thenReturn(4L);

        UserSession session = service.createSession(1L, "actor-token", null, null);

        assertEquals(10L, session.getApplicationId());
        assertEquals(11L, session.getLoginChannelId());
        assertEquals(12L, session.getTenantId());
        assertEquals(13L, session.getTenantMemberId());
        assertEquals("party", session.getExecutionScope());
        assertEquals(14L, session.getActorPartyId());
        assertEquals(15L, session.getPartyMembershipId());
        assertEquals("ready", session.getSessionStage());
        assertEquals(4L, session.getContextVersion());
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
    @DisplayName("createSession returns the persisted row for an idempotent duplicate")
    void createSessionReturnsPersistedDuplicate() {
        UserSession existing = new UserSession();
        existing.setPid("existing-session");
        when(userSessionMapper.insertIfAbsent(any(UserSession.class))).thenReturn(0);
        when(userSessionMapper.findByTokenHash(any())).thenReturn(existing);

        UserSession result = service.createSession(1L, "tk", null, null);

        assertSame(existing, result);
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
    @DisplayName("expired impersonation session is invalid even before explicit revocation")
    void expiredImpersonationSessionIsInvalid() {
        UserSession expired = new UserSession();
        expired.setRevoked(false);
        expired.setSessionKind("impersonation");
        expired.setImpersonationExpiresAt(Instant.now().minusSeconds(1));
        when(userSessionMapper.findByTokenHash(any())).thenReturn(expired);

        assertFalse(service.isSessionValid("expired"));
    }

    @Test
    @DisplayName("createImpersonationSession persists dual identity and authorization metadata")
    void createImpersonationSessionPersistsMetadata() {
        ReflectionTestUtils.setField(service, "jwtUtil", jwtUtil);
        Instant expiry = Instant.now().plusSeconds(1800);
        when(jwtUtil.extractImpersonation("imp-token")).thenReturn(true);
        when(jwtUtil.extractOperatorUserId("imp-token")).thenReturn(77L);
        when(jwtUtil.extractExpiration("imp-token")).thenReturn(java.util.Date.from(expiry));
        when(jwtUtil.extractClientType("imp-token")).thenReturn("web");
        when(jwtUtil.extractSessionId("imp-token")).thenReturn("imp-session");

        UserSession session = service.createImpersonationSession(
                9L, "imp-token", "offline", "Customer called", "TICKET-1", null, "ua");

        assertEquals("impersonation", session.getSessionKind());
        assertEquals(77L, session.getInitiatedByUserId());
        assertEquals(java.util.Date.from(expiry).toInstant(), session.getImpersonationExpiresAt());
        assertEquals("offline", session.getImpersonationAuthorizationMethod());
        assertEquals("Customer called", session.getImpersonationReason());
        assertEquals("TICKET-1", session.getImpersonationReference());
        assertEquals("web", session.getClientType());
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
    @DisplayName("revokeSessionByToken revokes the current bearer session")
    void revokeSessionByTokenFound() {
        UserSession s = new UserSession();
        s.setId(99L);
        s.setPid("p1");
        s.setRevoked(false);
        when(userSessionMapper.findByTokenHash(any())).thenReturn(s);

        service.revokeSessionByToken("token");

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

    @Test
    @DisplayName("expired impersonation sessions are revoked and audited once")
    void expiredImpersonationSessionsAreRevokedAndAudited() {
        UserSession expired = new UserSession();
        expired.setId(8L);
        expired.setPid("expired-session");
        expired.setTenantId(12L);
        expired.setInitiatedByUserId(77L);
        when(userSessionMapper.findExpiredImpersonationSessions()).thenReturn(List.of(expired));
        when(userSessionMapper.revokeExpiredSession(8L)).thenReturn(1);
        ReflectionTestUtils.setField(service, "adminEventLogService", adminEventLogService);

        service.expireImpersonationSessions();

        verify(adminEventLogService).record(any(AdminEventLog.class));
    }
    @Test
    @DisplayName("impersonation expiry explicitly scopes auth-plane reads and restores on failure")
    void expiryScopesAuthPlaneAndRestoresOnFailure() {
        assertFalse(com.auraboot.framework.application.tenant.MetaContext.isTenantFilterBypassed());
        when(userSessionMapper.findExpiredImpersonationSessions()).thenAnswer(invocation -> {
            assertTrue(com.auraboot.framework.application.tenant.MetaContext.isTenantFilterBypassed(),
                    "Background auth-plane scan must declare its context-free scope");
            throw new IllegalStateException("controlled expiry lookup failure");
        });
        assertThrows(IllegalStateException.class, service::expireImpersonationSessions);
        assertFalse(com.auraboot.framework.application.tenant.MetaContext.isTenantFilterBypassed(),
                "Scheduler scope must be restored even after a lookup failure");
    }

}
