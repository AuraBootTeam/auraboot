package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.ChannelSessionStateService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-24: Channel session lease — pod-level ownership for crash-safe
 * multi-pod channel resume. Pins acquire / heartbeat / steal / release
 * + state round-trip.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("ChannelSessionStateService (PR-24)")
class ChannelSessionLeaseIntegrationTest extends BaseIntegrationTest {

    @Autowired private ChannelSessionStateService svc;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;
    private String sessionId;

    @BeforeEach
    void setup() {
        tenantId = 9_450_000L + System.nanoTime() % 100_000;
        sessionId = "sess_" + System.nanoTime();
        svc.setLeaseSeconds(60);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_channel_session_state WHERE tenant_id = ?", tenantId);
    }

    // -----------------------------------------------------------------------

    @Test
    @DisplayName("first pod to acquire owns the session + lease_expires_at is set")
    void first_acquire_owns() {
        assertThat(svc.acquireLease(tenantId, sessionId, "web", "pod-A")).isTrue();
        Map<String, Object> state = svc.loadState(tenantId, sessionId);
        assertThat(state.get("owner_pod_id")).isEqualTo("pod-A");
        assertThat(state.get("lease_expires_at")).isNotNull();
        assertThat(state.get("last_heartbeat_at")).isNotNull();
    }

    @Test
    @DisplayName("second pod can't steal while lease still valid")
    void second_pod_cant_steal() {
        svc.acquireLease(tenantId, sessionId, "web", "pod-A");
        boolean stolen = svc.acquireLease(tenantId, sessionId, "web", "pod-B");
        assertThat(stolen).isFalse();

        String owner = (String) svc.loadState(tenantId, sessionId).get("owner_pod_id");
        assertThat(owner).isEqualTo("pod-A");
    }

    @Test
    @DisplayName("same owner re-acquiring is idempotent (extends lease)")
    void same_owner_reacquire_ok() {
        svc.acquireLease(tenantId, sessionId, "web", "pod-A");
        assertThat(svc.acquireLease(tenantId, sessionId, "web", "pod-A")).isTrue();
    }

    @Test
    @DisplayName("after lease expires, a different pod can claim")
    void expired_lease_can_be_stolen() {
        svc.acquireLease(tenantId, sessionId, "web", "pod-A");
        // Simulate pod-A crashed: expire the lease by moving lease_expires_at into the past.
        jdbc.update("UPDATE ab_agent_channel_session_state " +
                        "SET lease_expires_at = NOW() - INTERVAL '5 minutes' " +
                        "WHERE tenant_id = ? AND session_id = ?",
                tenantId, sessionId);

        boolean claimed = svc.acquireLease(tenantId, sessionId, "web", "pod-B");
        assertThat(claimed).isTrue();

        String owner = (String) svc.loadState(tenantId, sessionId).get("owner_pod_id");
        assertThat(owner).isEqualTo("pod-B");
    }

    @Test
    @DisplayName("heartbeat extends the lease when the calling pod still owns it")
    void heartbeat_extends_for_owner() {
        svc.acquireLease(tenantId, sessionId, "web", "pod-A");
        Object originalExpires = svc.loadState(tenantId, sessionId).get("lease_expires_at");

        // Sleep-free: bump lease shorter first to ensure the bump-forward is observable.
        jdbc.update("UPDATE ab_agent_channel_session_state " +
                        "SET lease_expires_at = NOW() + INTERVAL '5 seconds' " +
                        "WHERE tenant_id = ? AND session_id = ?",
                tenantId, sessionId);

        assertThat(svc.heartbeat(tenantId, sessionId, "pod-A")).isTrue();
        Object newExpires = svc.loadState(tenantId, sessionId).get("lease_expires_at");
        assertThat(newExpires).isNotEqualTo(originalExpires);
    }

    @Test
    @DisplayName("heartbeat from a non-owner pod does nothing (returns false)")
    void heartbeat_non_owner_fails() {
        svc.acquireLease(tenantId, sessionId, "web", "pod-A");
        assertThat(svc.heartbeat(tenantId, sessionId, "pod-B")).isFalse();

        String owner = (String) svc.loadState(tenantId, sessionId).get("owner_pod_id");
        assertThat(owner).isEqualTo("pod-A");
    }

    @Test
    @DisplayName("saveState writes session_state JSONB + only the owner may write")
    void save_state_owner_only() {
        svc.acquireLease(tenantId, sessionId, "web", "pod-A");

        assertThat(svc.saveState(tenantId, sessionId, "pod-A",
                Map.of("lastMessageIndex", 5, "partialTool", "in-progress"))).isTrue();

        // Non-owner fails.
        assertThat(svc.saveState(tenantId, sessionId, "pod-B", Map.of("x", 1))).isFalse();

        String stored = jdbc.queryForObject(
                "SELECT session_state::text FROM ab_agent_channel_session_state " +
                        "WHERE tenant_id = ? AND session_id = ?",
                String.class, tenantId, sessionId);
        assertThat(stored).contains("lastMessageIndex").contains("5");
        assertThat(stored).doesNotContain("\"x\"");
    }

    @Test
    @DisplayName("releaseLease clears owner so another pod can acquire")
    void release_clears_owner() {
        svc.acquireLease(tenantId, sessionId, "web", "pod-A");
        svc.releaseLease(tenantId, sessionId, "pod-A");

        Map<String, Object> state = svc.loadState(tenantId, sessionId);
        assertThat(state.get("owner_pod_id")).isNull();

        // pod-B can now acquire cleanly.
        assertThat(svc.acquireLease(tenantId, sessionId, "web", "pod-B")).isTrue();
    }

    @Test
    @DisplayName("session_state survives lease transfer — new owner sees what the old owner saved")
    void state_survives_lease_transfer() {
        svc.acquireLease(tenantId, sessionId, "web", "pod-A");
        svc.saveState(tenantId, sessionId, "pod-A",
                Map.of("conversation_index", 7, "pending_tool_id", "tool-42"));

        // pod-A crashes → lease expires
        jdbc.update("UPDATE ab_agent_channel_session_state " +
                        "SET lease_expires_at = NOW() - INTERVAL '1 minute' " +
                        "WHERE tenant_id = ? AND session_id = ?",
                tenantId, sessionId);
        svc.acquireLease(tenantId, sessionId, "web", "pod-B");

        Map<String, Object> state = svc.loadState(tenantId, sessionId);
        String stateJson = (String) state.get("state_json");
        assertThat(state.get("owner_pod_id")).isEqualTo("pod-B");
        assertThat(stateJson).contains("conversation_index").contains("7")
                .contains("pending_tool_id").contains("tool-42");
    }
}
