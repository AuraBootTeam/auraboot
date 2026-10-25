package com.auraboot.framework.agent.identity;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class ChannelSessionResolverImpl implements ChannelSessionResolver {

    private static final String SELECT_BY_IDENTITY = """
            SELECT pid, tenant_id, channel, channel_user_id, profile_id,
                   acp_user_id, created_at, last_active_at
            FROM ab_agent_channel_session_state
            WHERE tenant_id = ?
              AND channel = ?
              AND COALESCE(channel_user_id, session_id) = ?
              AND COALESCE(profile_id, '') = COALESCE(?, '')
            LIMIT 1
            """;

    private static final String SELECT_BY_PID = """
            SELECT pid, tenant_id, channel, channel_user_id, profile_id,
                   acp_user_id, created_at, last_active_at
            FROM ab_agent_channel_session_state
            WHERE pid = ? AND tenant_id = ?
            LIMIT 1
            """;

    /**
     * Insert with ON CONFLICT DO NOTHING on the 4-tuple identity index. The legacy
     * {@code session_id} column has a {@code UNIQUE (tenant_id, session_id)} constraint
     * from the original lease schema; we set {@code session_id = pid} so each new
     * identity-resolved session has a unique session_id and does not collide with the
     * legacy lease key. The 4-tuple identity uniqueness comes from the new
     * {@code uq_channel_session_state_identity} partial index.
     */
    private static final String INSERT_SESSION = """
            INSERT INTO ab_agent_channel_session_state (
                pid, tenant_id, session_id, channel,
                channel_user_id, profile_id, acp_user_id,
                created_at, updated_at, last_active_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT (tenant_id, channel, (COALESCE(channel_user_id, session_id)),
                         (COALESCE(profile_id, ''))) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;
    private final RowMapper<ChannelSession> mapper = (rs, rowNum) -> new ChannelSession(
            rs.getString("pid"),
            rs.getLong("tenant_id"),
            rs.getString("channel"),
            rs.getString("channel_user_id"),
            rs.getString("profile_id"),
            getNullableLong(rs, "acp_user_id"),
            getInstant(rs, "created_at"),
            getInstant(rs, "last_active_at")
    );

    public ChannelSessionResolverImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<ChannelSession> findByPid(String channelSessionPid, long tenantId) {
        if (channelSessionPid == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(
                    jdbcTemplate.queryForObject(SELECT_BY_PID, mapper, channelSessionPid, tenantId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public ChannelSession resolve(ResolveRequest request) {
        Optional<ChannelSession> existing = lookupByIdentity(request);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (!request.createIfAbsent()) {
            throw new IllegalStateException(
                    "ChannelSession not found and createIfAbsent=false: tenant=" + request.tenantId()
                            + ", channel=" + request.channel() + ", user=" + request.channelUserId());
        }

        String pid = UniqueIdGenerator.generate();
        // session_id = pid so each new identity row has a unique session_id and does
        // not collide with the legacy (tenant_id, session_id) UNIQUE constraint.
        jdbcTemplate.update(INSERT_SESSION,
                pid,
                request.tenantId(),
                pid,                                    // session_id = pid (unique by construction)
                request.channel(),
                request.channelUserId(),
                request.profileId(),
                request.acpUserId());

        return lookupByIdentity(request)
                .orElseThrow(() -> new IllegalStateException(
                        "ChannelSession insert race not resolvable: " + request));
    }

    private Optional<ChannelSession> lookupByIdentity(ResolveRequest request) {
        List<ChannelSession> rows = jdbcTemplate.query(SELECT_BY_IDENTITY, mapper,
                request.tenantId(),
                request.channel(),
                request.channelUserId(),
                request.profileId());
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static Long getNullableLong(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Instant getInstant(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        Timestamp ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toInstant();
    }
}
