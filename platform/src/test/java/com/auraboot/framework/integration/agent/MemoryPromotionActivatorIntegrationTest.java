package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.MemoryPromotionActivator;
import com.auraboot.framework.common.util.UniqueIdGenerator;
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

@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("MemoryPromotionActivator (PR-66)")
class MemoryPromotionActivatorIntegrationTest extends BaseIntegrationTest {

    @Autowired private MemoryPromotionActivator activator;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = 9_792_000L + System.nanoTime() % 10_000;
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_memory_promotion WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_memory WHERE tenant_id = ?", tenantId);
    }

    private String[] seedShadow(String endsInterval) {
        String memoryPid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_memory "
                        + "(pid, tenant_id, memory_agent_id, memory_type, category, "
                        + " memory_title, memory_content, importance, shareable, scope, scope_key, "
                        + " shadow_mode, created_at, updated_at, deleted_flag) "
                        + "VALUES (?, ?, 'default', 'fact', 'ops', 't', 'c', 7, TRUE, 'tenant', NULL, "
                        + "TRUE, NOW(), NOW(), FALSE)",
                memoryPid, tenantId);
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_memory_promotion ("
                        + "pid, tenant_id, source_scope, target_scope, category, "
                        + "proposed_title, proposed_content, proposed_importance, "
                        + "status, promoted_memory_pid, shadow_started_at, shadow_ends_at, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, 'user', 'tenant', 'ops', 't', 'c', 7, "
                        + "'PROMOTED_SHADOW', ?, NOW() - INTERVAL '8 days', NOW() " + endsInterval
                        + ", NOW() - INTERVAL '8 days', NOW())",
                pid, tenantId, memoryPid);
        return new String[]{pid, memoryPid};
    }

    @Test
    @DisplayName("shadow past window → ACTIVE and memory shadow_mode=FALSE")
    void shadowPast_activates() {
        String[] ids = seedShadow("- INTERVAL '1 day'");
        int activated = activator.runOnce();
        assertThat(activated).isEqualTo(1);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, activated_at FROM ab_agent_memory_promotion WHERE pid = ?", ids[0]);
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("activated_at")).isNotNull();

        Boolean shadowMode = jdbc.queryForObject(
                "SELECT shadow_mode FROM ab_agent_memory WHERE pid = ?", Boolean.class, ids[1]);
        assertThat(shadowMode).isFalse();
    }

    @Test
    @DisplayName("shadow not yet past → no change")
    void shadowNotPast_unchanged() {
        String[] ids = seedShadow("+ INTERVAL '1 day'");
        int activated = activator.runOnce();
        assertThat(activated).isZero();
        String status = jdbc.queryForObject(
                "SELECT status FROM ab_agent_memory_promotion WHERE pid = ?", String.class, ids[0]);
        assertThat(status).isEqualTo("PROMOTED_SHADOW");
    }

    @Test
    @DisplayName("advisory lock prevents reentry: concurrent runs produce at most one activation per row")
    void concurrent_runOnce() throws Exception {
        String[] ids = seedShadow("- INTERVAL '1 day'");
        java.util.concurrent.CompletableFuture<Integer> a =
                java.util.concurrent.CompletableFuture.supplyAsync(activator::runOnce);
        java.util.concurrent.CompletableFuture<Integer> b =
                java.util.concurrent.CompletableFuture.supplyAsync(activator::runOnce);
        int total = a.get() + b.get();
        assertThat(total).isLessThanOrEqualTo(1);

        String status = jdbc.queryForObject(
                "SELECT status FROM ab_agent_memory_promotion WHERE pid = ?", String.class, ids[0]);
        assertThat(status).isEqualTo("ACTIVE");
    }
}
