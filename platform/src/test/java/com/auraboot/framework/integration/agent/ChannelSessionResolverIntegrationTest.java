package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.identity.ChannelSessionResolver;
import com.auraboot.framework.agent.identity.ChannelSessionResolver.ChannelSession;
import com.auraboot.framework.agent.identity.ChannelSessionResolver.ResolveRequest;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GAP-295: ChannelSessionResolver — 4-tuple identity lookup + create
 * on ab_agent_channel_session_state (Q10=Y).
 */
@Commit
@DisplayName("ChannelSessionResolver — 4-tuple identity + create + idempotency + profile")
class ChannelSessionResolverIntegrationTest extends BaseIntegrationTest {

    @Autowired private ChannelSessionResolver resolver;
    @Autowired private JdbcTemplate jdbc;

    private long tenantId;
    private String channelUserId;

    @BeforeEach
    void setup() {
        tenantId = 7_7001L + System.nanoTime() % 10000;
        channelUserId = "user_" + System.nanoTime();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_channel_session_state WHERE tenant_id = ?", tenantId);
    }

    @Test
    @DisplayName("resolve creates new session when missing and createIfAbsent=true")
    void resolve_missing_creates() {
        ChannelSession session = resolver.resolve(new ResolveRequest(
                tenantId, "web", channelUserId, null, 12345L, true));

        assertThat(session.pid()).isNotNull().hasSize(26);
        assertThat(session.tenantId()).isEqualTo(tenantId);
        assertThat(session.channel()).isEqualTo("web");
        assertThat(session.channelUserId()).isEqualTo(channelUserId);
        assertThat(session.acpUserId()).isEqualTo(12345L);
        assertThat(session.profileId()).isNull();
        assertThat(session.createdAt()).isNotNull();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_channel_session_state WHERE tenant_id = ? AND channel_user_id = ?",
                Integer.class, tenantId, channelUserId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("resolve is idempotent — second call returns same pid, no duplicate row")
    void resolve_idempotent() {
        ChannelSession first = resolver.resolve(new ResolveRequest(
                tenantId, "web", channelUserId, null, 12345L, true));
        ChannelSession second = resolver.resolve(new ResolveRequest(
                tenantId, "web", channelUserId, null, 12345L, true));

        assertThat(second.pid()).isEqualTo(first.pid());

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_channel_session_state WHERE tenant_id = ? AND channel_user_id = ?",
                Integer.class, tenantId, channelUserId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("different profileId yields different sessions for same channel_user_id")
    void resolve_differentProfile_differentSession() {
        ChannelSession a = resolver.resolve(new ResolveRequest(
                tenantId, "web", channelUserId, "profile_a", null, true));
        ChannelSession b = resolver.resolve(new ResolveRequest(
                tenantId, "web", channelUserId, "profile_b", null, true));

        assertThat(b.pid()).isNotEqualTo(a.pid());
        assertThat(a.profileId()).isEqualTo("profile_a");
        assertThat(b.profileId()).isEqualTo("profile_b");
    }

    @Test
    @DisplayName("resolve with createIfAbsent=false throws when row missing")
    void resolve_missing_noCreate_throws() {
        assertThatThrownBy(() -> resolver.resolve(new ResolveRequest(
                tenantId, "web", channelUserId, null, null, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not found")
                .hasMessageContaining(channelUserId);
    }

    @Test
    @DisplayName("findByPid returns the session when it exists, empty otherwise")
    void findByPid_lifecycle() {
        ChannelSession created = resolver.resolve(new ResolveRequest(
                tenantId, "web", channelUserId, null, null, true));

        Optional<ChannelSession> found = resolver.findByPid(created.pid(), tenantId);
        assertThat(found).isPresent();
        assertThat(found.get().pid()).isEqualTo(created.pid());

        Optional<ChannelSession> notFound = resolver.findByPid("nonexistent_pid_zzz", tenantId);
        assertThat(notFound).isEmpty();
    }
}
