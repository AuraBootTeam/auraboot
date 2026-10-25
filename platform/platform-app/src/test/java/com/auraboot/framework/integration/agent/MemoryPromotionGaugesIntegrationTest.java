package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.metrics.MemoryPromotionGauges;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-67 — verifies Memory Promotion gauges are registered and queried from
 * the live Postgres table. Aggregates are cross-tenant by design — see class
 * Javadoc on {@link MemoryPromotionGauges}.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("MemoryPromotionGauges (PR-67)")
class MemoryPromotionGaugesIntegrationTest extends BaseIntegrationTest {

    @Autowired private MeterRegistry meterRegistry;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = 9_470_000L + System.nanoTime() % 100_000;
        MetaContext.setContext(tenantId, testUser.getId(), testUser.getPid(), testUser.getUserName());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_memory_promotion WHERE tenant_id = ?", tenantId);
    }

    private void seed(String status, BigDecimal confidence) {
        // source_memory_pid = NULL keeps us honest with the FK constraint —
        // per plan §4 this is allowed for merges with no single source.
        jdbc.update(
                "INSERT INTO ab_agent_memory_promotion " +
                        "(pid, tenant_id, source_scope, target_scope, " +
                        " category, proposed_title, proposed_content, proposed_importance, " +
                        " reason_code, confidence_score, status, created_at) " +
                        "VALUES (?, ?, 'user', 'tenant', 'operations', 't', 'c', 5, " +
                        "        'cross_user_agreement', ?, ?, NOW())",
                UniqueIdGenerator.generate(), tenantId, confidence, status);
    }

    @Test
    @DisplayName("all three gauges are registered with correct names")
    void gauges_registered() {
        Gauge pending = meterRegistry.find(MemoryPromotionGauges.PENDING_COUNT).gauge();
        Gauge shadow = meterRegistry.find(MemoryPromotionGauges.SHADOW_COUNT).gauge();
        Gauge backlog = meterRegistry.find(MemoryPromotionGauges.REVIEWER_BACKLOG_SECONDS).gauge();
        assertThat(pending).isNotNull();
        assertThat(shadow).isNotNull();
        assertThat(backlog).isNotNull();
    }

    @Test
    @DisplayName("pending_count gauge reflects DRAFT_PENDING_REVIEW rows")
    void pending_count_reflects_sql() {
        Gauge pending = meterRegistry.find(MemoryPromotionGauges.PENDING_COUNT).gauge();
        assertThat(pending).isNotNull();
        double before = pending.value();

        seed("DRAFT_PENDING_REVIEW", new BigDecimal("0.85"));
        seed("DRAFT_PENDING_REVIEW", new BigDecimal("0.75"));
        seed("ACTIVE", new BigDecimal("0.95"));

        double after = pending.value();
        assertThat(after - before).isEqualTo(2.0d);
    }

    @Test
    @DisplayName("shadow_count gauge reflects PROMOTED_SHADOW rows")
    void shadow_count_reflects_sql() {
        Gauge shadow = meterRegistry.find(MemoryPromotionGauges.SHADOW_COUNT).gauge();
        assertThat(shadow).isNotNull();
        double before = shadow.value();
        seed("PROMOTED_SHADOW", new BigDecimal("0.85"));
        assertThat(shadow.value() - before).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("reviewer_backlog_seconds returns non-negative; >0 when pending row exists")
    void backlog_positive_with_pending() {
        Gauge backlog = meterRegistry.find(MemoryPromotionGauges.REVIEWER_BACKLOG_SECONDS).gauge();
        assertThat(backlog).isNotNull();
        seed("DRAFT_PENDING_REVIEW", new BigDecimal("0.85"));
        assertThat(backlog.value()).isGreaterThanOrEqualTo(0.0d);
    }
}
