package com.auraboot.framework.behavior.controller;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Real-HTTP coverage for authenticated {@code POST /api/collect}: JWT filter populates
 * {@code MetaContext}, the collect service enqueues through the memory MQ provider, and the
 * consumer persists/quarantines against Postgres.
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
@TestPropertySource(properties = "aura.mq.type=memory")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BehaviorCollectControllerIT {

    private static final long TENANT_ID = 990_301L;
    private static final long USER_ID = 880_301L;
    private static final String USER_PID = "collect-it-user-pid";
    private static final String USERNAME = "collect-it-user";

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtUtil jwtUtil;

    @MockitoBean
    private UserDetailsService userDetailsService;
    @MockitoBean
    private UserService userService;
    @MockitoBean
    private SessionManagementService sessionManagementService;

    private CustomUserDetails userDetails;

    @BeforeAll
    void seedSchema() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ab_behavior_event (
                id BIGSERIAL PRIMARY KEY,
                event_id VARCHAR(40) NOT NULL,
                schema_version VARCHAR(16),
                event_name VARCHAR(120) NOT NULL,
                event_category VARCHAR(32),
                source VARCHAR(24),
                identity_quality VARCHAR(16),
                occurred_at TIMESTAMPTZ,
                received_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                tenant_id BIGINT NOT NULL,
                user_id BIGINT,
                anon_id VARCHAR(64),
                client_session_id VARCHAR(64),
                interaction_id VARCHAR(64),
                caused_by_event_id VARCHAR(40),
                trace_id VARCHAR(36),
                source_span_id VARCHAR(36),
                run_id VARCHAR(64),
                ui_element_id VARCHAR(80),
                app_id VARCHAR(64),
                page_id VARCHAR(64),
                block_id VARCHAR(64),
                element_code VARCHAR(64),
                props JSONB,
                consent_state VARCHAR(24),
                consent_version VARCHAR(16),
                sampling_unit VARCHAR(16),
                sampling_probability NUMERIC(6,5),
                producer_name VARCHAR(48),
                producer_version VARCHAR(24),
                created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
            )""");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_ab_behavior_event_tenant_eventid "
                + "ON ab_behavior_event (tenant_id, event_id)");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ab_behavior_quarantine (
                id BIGSERIAL PRIMARY KEY,
                tenant_id BIGINT NOT NULL,
                user_id BIGINT,
                anon_id TEXT,
                event_id TEXT,
                event_name TEXT,
                reason VARCHAR(64) NOT NULL,
                detail TEXT,
                raw_event JSONB,
                quarantined_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
            )""");
        jdbc.execute("ALTER TABLE ab_behavior_quarantine ALTER COLUMN anon_id TYPE TEXT");
        jdbc.execute("ALTER TABLE ab_behavior_quarantine ALTER COLUMN event_id TYPE TEXT");
        jdbc.execute("ALTER TABLE ab_behavior_quarantine ALTER COLUMN event_name TYPE TEXT");
        cleanup();
    }

    @BeforeEach
    void setUpIdentity() {
        cleanup();
        userDetails = new CustomUserDetails(USERNAME, "irrelevant", USER_ID, USER_PID,
                Collections.emptyList(), true, true, true, true);
        User user = new User();
        user.setId(USER_ID);
        user.setPid(USER_PID);
        user.setUserName(USERNAME);
        user.setSecurityVersion(0);
        when(userDetailsService.loadUserByUsername(USER_PID)).thenReturn(userDetails);
        when(userService.findByPid(USER_PID)).thenReturn(user);
        when(sessionManagementService.isSessionValid(anyString())).thenReturn(true);
    }

    @AfterAll
    void tearDown() {
        cleanup();
    }

    @Test
    @DisplayName("authenticated collect → tenant/user resolved by JWT and event persisted")
    void authenticatedCollect_persistsResolvedTenantAndUser() {
        ResponseEntity<String> resp = postCollect(body("auth-e-1", "page_view", "auth-anon-1"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"accepted\":1");
        assertThat(jdbc.queryForObject(
                "SELECT tenant_id FROM ab_behavior_event WHERE event_id='auth-e-1'",
                Long.class)).isEqualTo(TENANT_ID);
        assertThat(jdbc.queryForObject(
                "SELECT user_id FROM ab_behavior_event WHERE event_id='auth-e-1'",
                Long.class)).isEqualTo(USER_ID);
        assertThat(jdbc.queryForObject(
                "SELECT anon_id FROM ab_behavior_event WHERE event_id='auth-e-1'",
                String.class)).isEqualTo("auth-anon-1");
    }

    @Test
    @DisplayName("authenticated duplicate event_id → both accepted, one durable row")
    void authenticatedCollect_duplicateAcceptedSingleRow() {
        ResponseEntity<String> first = postCollect(body("auth-dup-1", "page_view", "auth-anon-dup"));
        ResponseEntity<String> second = postCollect(body("auth-dup-1", "page_view", "auth-anon-dup"));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).contains("\"accepted\":1");
        assertThat(second.getBody()).contains("\"accepted\":1");
        assertThat(eventCount("auth-dup-1")).isEqualTo(1);
    }

    @Test
    @DisplayName("authenticated malformed event → accepted, user-scoped quarantine, no behavior row")
    void authenticatedCollect_malformedQuarantinedWithUser() {
        ResponseEntity<String> resp = postCollect("{\"events\":[{\"eventName\":\"page_view\",\"anonId\":\"auth-bad\"}]}");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"accepted\":1");
        assertThat(jdbc.queryForObject(
                "SELECT count(1) FROM ab_behavior_event WHERE tenant_id=? AND anon_id='auth-bad'",
                Integer.class, TENANT_ID)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT reason FROM ab_behavior_quarantine WHERE tenant_id=? AND anon_id='auth-bad'",
                String.class, TENANT_ID)).isEqualTo("malformed_missing_event_id");
        assertThat(jdbc.queryForObject(
                "SELECT user_id FROM ab_behavior_quarantine WHERE tenant_id=? AND anon_id='auth-bad'",
                Long.class, TENANT_ID)).isEqualTo(USER_ID);
    }

    private ResponseEntity<String> postCollect(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(jwt());
        return restTemplate.exchange("/api/collect", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private String jwt() {
        return jwtUtil.generateTokenWithTenantId(userDetails, USER_PID, TENANT_ID);
    }

    private String body(String eventId, String eventName, String anonId) {
        return "{\"events\":[{\"eventId\":\"" + eventId + "\",\"eventName\":\"" + eventName
                + "\",\"anonId\":\"" + anonId + "\"}]}";
    }

    private int eventCount(String eventId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(1) FROM ab_behavior_event WHERE tenant_id=? AND event_id=?",
                Integer.class, TENANT_ID, eventId);
        return n == null ? 0 : n;
    }

    private void cleanup() {
        jdbc.update("DELETE FROM ab_behavior_event WHERE tenant_id = ?", TENANT_ID);
        jdbc.update("DELETE FROM ab_behavior_quarantine WHERE tenant_id = ?", TENANT_ID);
    }
}
