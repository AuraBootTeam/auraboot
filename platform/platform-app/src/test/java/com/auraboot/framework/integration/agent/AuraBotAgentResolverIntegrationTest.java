package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.identity.AgentNotResolvedException;
import com.auraboot.framework.agent.identity.AuraBotAgentResolver;
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
 * GAP-296: AuraBotAgentResolver — per-tenant aurabot agentId lookup with
 * lazy seed-on-resolve fallback for tenants created after bootstrap.
 */
@Commit
@DisplayName("AuraBotAgentResolver — lazy seed + tryResolve + custom agent reject")
class AuraBotAgentResolverIntegrationTest extends BaseIntegrationTest {

    @Autowired private AuraBotAgentResolver resolver;
    @Autowired private JdbcTemplate jdbc;

    private long freshTenantId;

    @BeforeEach
    void setup() {
        // Pick a tenant id that bootstrap seed didn't touch.
        freshTenantId = 8_8001L + System.nanoTime() % 1000;
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_definition WHERE tenant_id = ?", freshTenantId);
    }

    @Test
    @DisplayName("tryResolve returns empty when row does not exist")
    void tryResolve_missing_returnsEmpty() {
        Optional<Long> result = resolver.tryResolve(freshTenantId, AuraBotAgentResolver.DEFAULT_AGENT_CODE);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("resolve lazy-seeds aurabot row when missing and returns the new id")
    void resolve_aurabot_lazySeeds() {
        Long agentId = resolver.resolve(freshTenantId, AuraBotAgentResolver.DEFAULT_AGENT_CODE);

        assertThat(agentId).isNotNull().isPositive();

        // Verify the row landed with expected canonical values
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_definition WHERE tenant_id = ? AND agent_code = 'aurabot' " +
                        "AND status = 'active' AND (deleted_flag = FALSE OR deleted_flag IS NULL)",
                Integer.class, freshTenantId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("resolve is idempotent — second call returns same id, no duplicate row")
    void resolve_aurabot_idempotent() {
        Long first = resolver.resolve(freshTenantId, AuraBotAgentResolver.DEFAULT_AGENT_CODE);
        Long second = resolver.resolve(freshTenantId, AuraBotAgentResolver.DEFAULT_AGENT_CODE);

        assertThat(second).isEqualTo(first);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_definition WHERE tenant_id = ? AND agent_code = 'aurabot'",
                Integer.class, freshTenantId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("resolve throws AgentNotResolvedException for non-aurabot agentCode when missing")
    void resolve_customAgent_missingThrows() {
        assertThatThrownBy(() -> resolver.resolve(freshTenantId, "custom_unknown_agent"))
                .isInstanceOf(AgentNotResolvedException.class)
                .hasMessageContaining("custom_unknown_agent")
                .hasMessageContaining(String.valueOf(freshTenantId));
    }

    @Test
    @DisplayName("tryResolve returns empty for soft-deleted rows")
    void tryResolve_softDeleted_returnsEmpty() {
        // First seed
        resolver.resolve(freshTenantId, AuraBotAgentResolver.DEFAULT_AGENT_CODE);

        // Soft delete
        jdbc.update("UPDATE ab_agent_definition SET deleted_flag = TRUE WHERE tenant_id = ? AND agent_code = 'aurabot'",
                freshTenantId);

        assertThat(resolver.tryResolve(freshTenantId, AuraBotAgentResolver.DEFAULT_AGENT_CODE)).isEmpty();
    }
}
