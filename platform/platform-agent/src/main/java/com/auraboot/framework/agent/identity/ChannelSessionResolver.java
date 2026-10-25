package com.auraboot.framework.agent.identity;

import java.time.Instant;
import java.util.Optional;

/**
 * Resolves or creates the {@link ChannelSession} for a given
 * (tenantId, channel, channelUserId, profileId) identity tuple.
 *
 * <p>Backed by {@code ab_agent_channel_session_state} (the OSS table also
 * acts as the session identity table per Q10=Y; the {@code _state} suffix
 * means "contains lease/heartbeat state fields", not "is a state view of
 * another table").
 *
 * <p>Contract: {@code auraboot-enterprise/docs/agent/contracts/channel-session.md}
 */
public interface ChannelSessionResolver {

    /**
     * Resolves the existing session by 4-tuple identity, or creates a new one
     * when {@code createIfAbsent=true}. Concurrent callers see the same row
     * via the {@code uq_channel_session_state_identity} unique index.
     */
    ChannelSession resolve(ResolveRequest request);

    /** Pure lookup; returns empty when no row matches the 4-tuple. */
    Optional<ChannelSession> findByPid(String channelSessionPid, long tenantId);

    record ResolveRequest(
            long tenantId,
            String channel,
            String channelUserId,
            String profileId,
            Long acpUserId,
            boolean createIfAbsent
    ) {}

    record ChannelSession(
            String pid,
            long tenantId,
            String channel,
            String channelUserId,
            String profileId,
            Long acpUserId,
            Instant createdAt,
            Instant lastActiveAt
    ) {}
}
