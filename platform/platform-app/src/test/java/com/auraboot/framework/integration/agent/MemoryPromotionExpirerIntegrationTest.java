package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.MemoryPromotionExpirer;
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

import static org.assertj.core.api.Assertions.assertThat;
import com.auraboot.framework.integration.TestIdGenerator;

@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("MemoryPromotionExpirer (PR-66)")
class MemoryPromotionExpirerIntegrationTest extends BaseIntegrationTest {

    @Autowired private MemoryPromotionExpirer expirer;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = TestIdGenerator.uniqueTenantId();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_memory_promotion WHERE tenant_id = ?", tenantId);
    }

    private String seedDraft(int daysOld) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_memory_promotion ("
                        + "pid, tenant_id, source_scope, target_scope, category, "
                        + "proposed_title, proposed_content, proposed_importance, "
                        + "status, created_at, updated_at) "
                        + "VALUES (?, ?, 'user', 'tenant', 'ops', 't', 'c', 6, "
                        + "'DRAFT_PENDING_REVIEW', NOW() - INTERVAL '" + daysOld + " days', NOW())",
                pid, tenantId);
        return pid;
    }

    private String seedRejected(int daysSinceReview) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_memory_promotion ("
                        + "pid, tenant_id, source_scope, target_scope, category, "
                        + "proposed_title, proposed_content, proposed_importance, "
                        + "status, reject_reason, reviewed_at, created_at, updated_at) "
                        + "VALUES (?, ?, 'user', 'tenant', 'ops', 't', 'c', 6, "
                        + "'REVIEWED_REJECTED', 'other', NOW() - INTERVAL '" + daysSinceReview
                        + " days', NOW() - INTERVAL '" + (daysSinceReview + 1) + " days', NOW())",
                pid, tenantId);
        return pid;
    }

    @Test
    @DisplayName("31-day pending draft → EXPIRED with reject_reason=other")
    void staleDraft_expires() {
        String pid = seedDraft(31);
        int touched = expirer.runOnce();
        assertThat(touched).isGreaterThanOrEqualTo(1);
        String status = jdbc.queryForObject(
                "SELECT status FROM ab_agent_memory_promotion WHERE pid = ?", String.class, pid);
        assertThat(status).isEqualTo("EXPIRED");
        String rejectReason = jdbc.queryForObject(
                "SELECT reject_reason FROM ab_agent_memory_promotion WHERE pid = ?", String.class, pid);
        assertThat(rejectReason).isEqualTo("other");
    }

    @Test
    @DisplayName("89-day rejected row remains REVIEWED_REJECTED (under retention threshold)")
    void rejectedUnderRetention_unchanged() {
        String pid = seedRejected(89);
        expirer.runOnce();
        String status = jdbc.queryForObject(
                "SELECT status FROM ab_agent_memory_promotion WHERE pid = ?", String.class, pid);
        assertThat(status).isEqualTo("REVIEWED_REJECTED");
    }

    @Test
    @DisplayName("91-day rejected row → DISCARDED")
    void rejectedOverRetention_discarded() {
        String pid = seedRejected(91);
        expirer.runOnce();
        String status = jdbc.queryForObject(
                "SELECT status FROM ab_agent_memory_promotion WHERE pid = ?", String.class, pid);
        assertThat(status).isEqualTo("DISCARDED");
    }

    @Test
    @DisplayName("draft < 30d and rejected < 90d both stay untouched")
    void freshRows_unchanged() {
        String draft = seedDraft(5);
        String rejected = seedRejected(30);
        expirer.runOnce();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM ab_agent_memory_promotion WHERE pid = ?", String.class, draft))
                .isEqualTo("DRAFT_PENDING_REVIEW");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM ab_agent_memory_promotion WHERE pid = ?", String.class, rejected))
                .isEqualTo("REVIEWED_REJECTED");
    }
}
