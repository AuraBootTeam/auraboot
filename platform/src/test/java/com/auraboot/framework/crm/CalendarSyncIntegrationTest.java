package com.auraboot.framework.crm;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.crm.entity.CalendarSync;
import com.auraboot.framework.crm.mapper.CalendarSyncMapper;
import com.auraboot.framework.crm.service.CalendarSyncService;
import com.auraboot.framework.crm.service.provider.CalendarProvider;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for CalendarSyncService — OAuth state token, status, and disconnect.
 *
 * <p>Does NOT test real Google/Microsoft OAuth token exchange (requires real credentials).
 * CAL-01 verifies state token generation + URL construction via the real provider bean.
 * CAL-03 tests DB persistence by directly inserting a CalendarSync record and querying it.
 * CAL-04 tests the disconnect (soft-delete) path.
 *
 * @since 5.3.0
 */
@Slf4j
@DisplayName("Calendar Sync Integration Tests (CAL-01~CAL-05)")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CalendarSyncIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private CalendarSyncService calendarSyncService;

    @Autowired
    private CalendarSyncMapper calendarSyncMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private List<CalendarProvider> calendarProviders;

    private final String runId = "cal-" + System.currentTimeMillis();
    private final long uniqueOffset = Math.floorMod(System.currentTimeMillis(), 1_000_000L);

    // Shared state across ordered tests
    private Long testUserId;
    private Long testTenantId;

    @BeforeEach
    void setUpIds() {
        // BaseIntegrationTest.setupTenantContext() sets MetaContext per test
        testUserId = MetaContext.getCurrentUserId();
        testTenantId = MetaContext.getCurrentTenantId();
    }

    // ==================== CAL-01 ====================

    @Test
    @Order(1)
    @DisplayName("CAL-01: initiateConnect returns a URL containing a state parameter")
    void cal01_initiateConnectReturnsUrl() {
        String redirectUri = "http://localhost:6443/api/crm/calendar/callback/google";
        String authUrl = calendarSyncService.initiateConnect("google", testUserId, testTenantId, redirectUri);

        assertThat(authUrl).isNotNull().isNotBlank();
        assertThat(authUrl).contains("state=");
        assertThat(authUrl).contains("accounts.google.com");
        log.info("CAL-01: authUrl starts with: {}", authUrl.substring(0, Math.min(80, authUrl.length())) + "...");
    }

    // ==================== CAL-02 ====================

    @Test
    @Order(2)
    @DisplayName("CAL-02: getStatus returns empty list when no sync configured for a fresh user")
    void cal02_getStatusEmpty() {
        // Use a fresh userId that has no calendar syncs
        Long freshUserId = testUserId + 99997L + uniqueOffset;

        List<CalendarSync> status = calendarSyncService.getStatus(freshUserId, testTenantId);

        assertThat(status).isNotNull();
        assertThat(status).isEmpty();
        log.info("CAL-02: getStatus empty for userId={}", freshUserId);
    }

    // ==================== CAL-03 ====================

    @Test
    @Order(3)
    @DisplayName("CAL-03: manually saved CalendarSync record is returned by getStatus")
    void cal03_saveAndGetStatus() {
        // Use a unique user to avoid cross-test interference
        Long userId = testUserId + 11111L + uniqueOffset;

        // Directly insert a CalendarSync record (simulating what handleCallback does after OAuth)
        CalendarSync sync = new CalendarSync();
        sync.setTenantId(testTenantId);
        sync.setUserId(userId);
        sync.setProvider("google");
        sync.setOauthToken("{\"access_token\":\"" + runId + "\",\"refresh_token\":\"rt\"}");
        sync.setSyncDirection("both");
        sync.setEnabled(true);
        sync.setDeletedFlag(false);
        sync.setCreatedAt(Instant.now());
        sync.setUpdatedAt(Instant.now());
        calendarSyncMapper.insert(sync);
        log.info("CAL-03: inserted CalendarSync id={}", sync.getId());

        // Verify getStatus returns it (with oauth_token masked)
        List<CalendarSync> status = calendarSyncService.getStatus(userId, testTenantId);
        assertThat(status).isNotEmpty();

        CalendarSync found = status.stream()
                .filter(s -> "google".equals(s.getProvider()))
                .findFirst()
                .orElse(null);

        assertThat(found).isNotNull();
        assertThat(found.getProvider()).isEqualTo("google");
        assertThat(found.getUserId()).isEqualTo(userId);
        assertThat(found.getTenantId()).isEqualTo(testTenantId);
        assertThat(found.getEnabled()).isTrue();
        assertThat(found.getDeletedFlag()).isFalse();
        // getStatus masks the oauth_token
        assertThat(found.getOauthToken()).isEqualTo("[REDACTED]");
        log.info("CAL-03: CalendarSync retrieved with masked token, id={}", found.getId());
    }

    // ==================== CAL-04 ====================

    @Test
    @Order(4)
    @DisplayName("CAL-04: disconnect soft-deletes the CalendarSync record")
    void cal04_disconnect() {
        Long userId = testUserId + 88888L + uniqueOffset; // use distinct user to avoid interference

        // Manually insert a CalendarSync record
        CalendarSync sync = new CalendarSync();
        sync.setTenantId(testTenantId);
        sync.setUserId(userId);
        sync.setProvider("microsoft");
        sync.setOauthToken("{\"access_token\":\"test-" + runId + "\"}");
        sync.setSyncDirection("both");
        sync.setEnabled(true);
        sync.setDeletedFlag(false);
        sync.setCreatedAt(Instant.now());
        sync.setUpdatedAt(Instant.now());
        calendarSyncMapper.insert(sync);

        Long insertedId = sync.getId();
        assertThat(insertedId).isNotNull();

        // Verify it exists
        List<CalendarSync> before = calendarSyncService.getStatus(userId, testTenantId);
        assertThat(before).isNotEmpty();

        // Disconnect
        calendarSyncService.disconnect("microsoft", userId, testTenantId);

        // Verify it's gone from getStatus (deleted_flag=true)
        List<CalendarSync> after = calendarSyncService.getStatus(userId, testTenantId);
        assertThat(after).isEmpty();

        // Double-check via raw JDBC (bypassing MyBatis Plus logic-delete filter)
        Boolean deletedFlag = jdbcTemplate.queryForObject(
                "SELECT deleted_flag FROM ab_calendar_sync WHERE id = ?",
                Boolean.class, insertedId);
        Boolean enabledFlag = jdbcTemplate.queryForObject(
                "SELECT enabled FROM ab_calendar_sync WHERE id = ?",
                Boolean.class, insertedId);
        assertThat(deletedFlag).isTrue();
        assertThat(enabledFlag).isFalse();
        log.info("CAL-04: CalendarSync soft-deleted confirmed via JDBC, id={}", insertedId);
    }

    // ==================== CAL-05 (state token validation) ====================

    @Test
    @Order(5)
    @DisplayName("CAL-05: buildStateToken generates valid token; tampered token is rejected")
    void cal05_stateTokenValidation() {
        // Build a valid state token
        String state = calendarSyncService.buildStateToken(testUserId, testTenantId);
        assertThat(state).isNotBlank();

        // Valid state should decode correctly
        long[] userTenant = calendarSyncService.validateStateToken(state);
        assertThat(userTenant[0]).isEqualTo(testUserId);
        assertThat(userTenant[1]).isEqualTo(testTenantId);

        // Tampered state should be rejected
        String tamperedState = state + "tampered";
        assertThatThrownBy(() -> calendarSyncService.validateStateToken(tamperedState))
                .isInstanceOf(IllegalArgumentException.class);

        log.info("CAL-05: state token round-trip OK; tampered state correctly rejected");
    }
}
