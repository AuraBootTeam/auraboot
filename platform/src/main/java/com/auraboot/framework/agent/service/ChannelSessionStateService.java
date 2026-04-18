package com.auraboot.framework.agent.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Channel session state with pod-level lease (ACP-Target-vs-Hermes §4.7).
 *
 * When a pod starts serving a channel session, it acquires a lease and
 * periodically heartbeats. If the pod crashes without releasing, the
 * lease expires and any other pod can claim it — the new owner reads the
 * saved {@code session_state} JSONB to resume without full replay.
 *
 * Lease model:
 *   - lease_expires_at = last_heartbeat_at + leaseDuration
 *   - another pod may acquire when lease_expires_at &lt; NOW()
 *   - saveState() extends the lease implicitly via heartbeat bump
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChannelSessionStateService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /** How long a lease is valid after the last heartbeat. */
    @Value("${acp.channel.session.lease-seconds:60}")
    private int leaseSeconds = 60;

    /**
     * Try to acquire the lease for (tenant, sessionId) on behalf of podId.
     * Returns true on success, false if another pod holds an un-expired lease.
     * Creates the session row on first acquire.
     */
    public boolean acquireLease(Long tenantId, String sessionId, String channel, String podId) {
        // Upsert: if row doesn't exist, create it owned by podId. If exists and
        // the lease is stale OR owned by us, overwrite owner.
        int affected = jdbcTemplate.update(
                "INSERT INTO ab_agent_channel_session_state " +
                        "(pid, tenant_id, session_id, channel, owner_pod_id, " +
                        " lease_expires_at, last_heartbeat_at, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, NOW() + (? || ' seconds')::interval, NOW(), NOW(), NOW()) " +
                        "ON CONFLICT (tenant_id, session_id) DO UPDATE SET " +
                        "  owner_pod_id      = EXCLUDED.owner_pod_id, " +
                        "  channel           = EXCLUDED.channel, " +
                        "  lease_expires_at  = EXCLUDED.lease_expires_at, " +
                        "  last_heartbeat_at = NOW(), " +
                        "  updated_at        = NOW() " +
                        "WHERE ab_agent_channel_session_state.owner_pod_id IS NULL " +
                        "   OR ab_agent_channel_session_state.owner_pod_id = EXCLUDED.owner_pod_id " +
                        "   OR ab_agent_channel_session_state.lease_expires_at < NOW()",
                UniqueIdGenerator.generate(), tenantId, sessionId, channel, podId,
                String.valueOf(leaseSeconds));
        return affected == 1;
    }

    /**
     * Heartbeat: extend the lease if we still own it. Returns true on extend,
     * false if the lease was stolen (another pod's owner_pod_id now).
     */
    public boolean heartbeat(Long tenantId, String sessionId, String podId) {
        int updated = jdbcTemplate.update(
                "UPDATE ab_agent_channel_session_state " +
                        "SET last_heartbeat_at = NOW(), " +
                        "    lease_expires_at = NOW() + (? || ' seconds')::interval, " +
                        "    updated_at = NOW() " +
                        "WHERE tenant_id = ? AND session_id = ? AND owner_pod_id = ?",
                String.valueOf(leaseSeconds), tenantId, sessionId, podId);
        return updated == 1;
    }

    /**
     * Save resume state (bounded JSONB). Same locking as heartbeat — only the
     * current owner can write. Returns true on write.
     */
    public boolean saveState(Long tenantId, String sessionId, String podId,
                              Map<String, Object> state) {
        String json;
        try {
            json = objectMapper.writeValueAsString(state != null ? state : Map.of());
        } catch (Exception e) {
            log.warn("saveState serialize failed for session {}: {}", sessionId, e.getMessage());
            return false;
        }
        int updated = jdbcTemplate.update(
                "UPDATE ab_agent_channel_session_state " +
                        "SET session_state = ?::jsonb, last_heartbeat_at = NOW(), " +
                        "    lease_expires_at = NOW() + (? || ' seconds')::interval, " +
                        "    updated_at = NOW() " +
                        "WHERE tenant_id = ? AND session_id = ? AND owner_pod_id = ?",
                json, String.valueOf(leaseSeconds), tenantId, sessionId, podId);
        return updated == 1;
    }

    /** Release the lease (graceful shutdown). */
    public void releaseLease(Long tenantId, String sessionId, String podId) {
        jdbcTemplate.update(
                "UPDATE ab_agent_channel_session_state " +
                        "SET owner_pod_id = NULL, lease_expires_at = NULL, updated_at = NOW() " +
                        "WHERE tenant_id = ? AND session_id = ? AND owner_pod_id = ?",
                tenantId, sessionId, podId);
    }

    /** Read session state for the current owner or a reclaimer. */
    public Map<String, Object> loadState(Long tenantId, String sessionId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT pid, session_id, channel, owner_pod_id, lease_expires_at, " +
                        "       last_heartbeat_at, session_state::text AS state_json " +
                        "FROM ab_agent_channel_session_state " +
                        "WHERE tenant_id = ? AND session_id = ?",
                tenantId, sessionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Test setter. */
    public void setLeaseSeconds(int v) { this.leaseSeconds = v; }
}
