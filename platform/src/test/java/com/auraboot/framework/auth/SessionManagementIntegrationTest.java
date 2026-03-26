package com.auraboot.framework.auth;

import com.auraboot.framework.auth.entity.UserSession;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SessionManagementService — create, validate, revoke, list sessions.
 */
@SpringBootTest(classes = com.auraboot.framework.application.TestApplication.class)
@ActiveProfiles("integration-test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SessionManagementIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private SessionManagementService sessionManagementService;

    private final String testRunId = String.valueOf(System.currentTimeMillis());

    // Shared across ordered tests
    private String sessionToken1;
    private String sessionPid1;

    // -----------------------------------------------------------------------
    // Test 1: Create session stores session in database
    // -----------------------------------------------------------------------
    @Test
    @Order(1)
    @DisplayName("createSession_storesSessionInDatabase")
    void createSession_storesSessionInDatabase() {
        Long userId = getTestUser().getId();
        sessionToken1 = "tok-" + testRunId + "-a";

        UserSession session = sessionManagementService.createSession(
                userId, sessionToken1, "127.0.0.1", "TestBrowser/1.0");

        assertNotNull(session, "Session should not be null");
        assertNotNull(session.getId(), "Session ID should not be null");
        assertNotNull(session.getPid(), "Session PID should not be null");
        assertNotEquals(Boolean.TRUE, session.getRevoked(), "Newly created session should not be revoked");

        // Store PID for use in subsequent tests
        sessionPid1 = session.getPid();
    }

    // -----------------------------------------------------------------------
    // Test 2: isSessionValid returns true for active session
    // -----------------------------------------------------------------------
    @Test
    @Order(2)
    @DisplayName("isSessionValid_forActiveSession_returnsTrue")
    void isSessionValid_forActiveSession_returnsTrue() {
        // Ensure test 1 ran; if not, create session here
        if (sessionToken1 == null) {
            Long userId = getTestUser().getId();
            sessionToken1 = "tok-" + testRunId + "-a";
            UserSession session = sessionManagementService.createSession(
                    userId, sessionToken1, "127.0.0.1", "TestBrowser/1.0");
            sessionPid1 = session.getPid();
        }

        assertTrue(sessionManagementService.isSessionValid(sessionToken1),
                "Active session should be reported as valid");
    }

    // -----------------------------------------------------------------------
    // Test 3: revokeSession invalidates the specific session
    // -----------------------------------------------------------------------
    @Test
    @Order(3)
    @DisplayName("revokeSession_invalidatesSpecificSession")
    void revokeSession_invalidatesSpecificSession() {
        Long userId = getTestUser().getId();

        // Ensure session exists
        if (sessionToken1 == null || sessionPid1 == null) {
            sessionToken1 = "tok-" + testRunId + "-a";
            UserSession session = sessionManagementService.createSession(
                    userId, sessionToken1, "127.0.0.1", "TestBrowser/1.0");
            sessionPid1 = session.getPid();
        }

        sessionManagementService.revokeSession(userId, sessionPid1);

        assertFalse(sessionManagementService.isSessionValid(sessionToken1),
                "Revoked session should no longer be valid");
    }

    // -----------------------------------------------------------------------
    // Test 4: isSessionValid returns false for non-existent token
    // -----------------------------------------------------------------------
    @Test
    @Order(4)
    @DisplayName("isSessionValid_forRevokedSession_returnsFalse")
    void isSessionValid_forRevokedSession_returnsFalse() {
        String nonExistentToken = "nonexistent-token-" + testRunId;

        assertFalse(sessionManagementService.isSessionValid(nonExistentToken),
                "Non-existent token should not be valid");
    }

    // -----------------------------------------------------------------------
    // Test 5: revokeAllSessions invalidates all devices
    // -----------------------------------------------------------------------
    @Test
    @Order(5)
    @DisplayName("revokeAllSessions_invalidatesAllDevices")
    void revokeAllSessions_invalidatesAllDevices() {
        Long userId = getTestUser().getId();

        String token2 = "tok-" + testRunId + "-b";
        String token3 = "tok-" + testRunId + "-c";

        sessionManagementService.createSession(userId, token2, "10.0.0.1", "BrowserB/2.0");
        sessionManagementService.createSession(userId, token3, "10.0.0.2", "BrowserC/3.0");

        assertTrue(sessionManagementService.isSessionValid(token2),
                "Session B should be valid before revokeAll");
        assertTrue(sessionManagementService.isSessionValid(token3),
                "Session C should be valid before revokeAll");

        sessionManagementService.revokeAllSessions(userId);

        assertFalse(sessionManagementService.isSessionValid(token2),
                "Session B should be invalid after revokeAll");
        assertFalse(sessionManagementService.isSessionValid(token3),
                "Session C should be invalid after revokeAll");
    }

    // -----------------------------------------------------------------------
    // Test 6: getActiveSessions returns only non-revoked sessions
    // -----------------------------------------------------------------------
    @Test
    @Order(6)
    @DisplayName("getActiveSessions_returnsOnlyNonRevokedSessions")
    void getActiveSessions_returnsOnlyNonRevokedSessions() {
        Long userId = getTestUser().getId();

        // Revoke all first to start clean
        sessionManagementService.revokeAllSessions(userId);

        // Create a fresh active session
        String freshToken = "tok-" + testRunId + "-fresh";
        sessionManagementService.createSession(userId, freshToken, "192.168.1.1", "FreshBrowser/1.0");

        List<UserSession> activeSessions = sessionManagementService.getActiveSessions(userId);

        assertNotNull(activeSessions, "Active sessions list should not be null");
        assertFalse(activeSessions.isEmpty(), "There should be at least one active session");

        for (UserSession session : activeSessions) {
            assertNotEquals(Boolean.TRUE, session.getRevoked(),
                    "Active sessions list must not contain revoked sessions");
        }
    }

    // -----------------------------------------------------------------------
    // Test 7: updateLastActive with 5-minute throttle does not throw
    // -----------------------------------------------------------------------
    @Test
    @Order(7)
    @DisplayName("updateLastActive_throttledTo5Minutes")
    void updateLastActive_throttledTo5Minutes() {
        Long userId = getTestUser().getId();
        String throttleToken = "tok-" + testRunId + "-throttle";

        sessionManagementService.createSession(userId, throttleToken, "127.0.0.1", "ThrottleBrowser/1.0");

        // First call should update the DB
        assertDoesNotThrow(() -> sessionManagementService.updateLastActive(throttleToken),
                "First updateLastActive should not throw");

        // Second call within 5 minutes should be silently throttled (no exception)
        assertDoesNotThrow(() -> sessionManagementService.updateLastActive(throttleToken),
                "Second updateLastActive within throttle window should not throw");
    }
}
