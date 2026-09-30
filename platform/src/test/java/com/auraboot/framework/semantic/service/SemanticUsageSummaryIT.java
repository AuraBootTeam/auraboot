package com.auraboot.framework.semantic.service;

import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden IT for the BI usage rollup (R1): insert known query-log rows and
 * assert the summary math (count, P95, cache rate, distinct users, tenant
 * isolation) against them. Runs on a fresh-seed migration-only database —
 * no fixture imports.
 */
@SpringBootTest(classes = com.auraboot.framework.application.TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Semantic usage summary golden IT — rollup math over known log rows")
class SemanticUsageSummaryIT {

    private static final long TENANT_ID = 991_900_001L;
    private static final long OTHER_TENANT_ID = 991_900_009L;
    private static final long USER_A = 991_900_002L;
    private static final long USER_B = 991_900_003L;

    @Autowired
    private SemanticUsageService usageService;
    @Autowired
    private JdbcTemplate jdbc;

    private static final java.util.concurrent.atomic.AtomicLong ID_COUNTER =
            new java.util.concurrent.atomic.AtomicLong(991_900_100);

    private String pidA1;
    private String pidA2;
    private String pidA3;
    private String pidA4;
    private String pidOther;

    @BeforeAll
    void seedLogRows() {
        MetaContext.setContext(TENANT_ID, USER_A, "usage-golden-pid", "usage-golden-user");
        // Four queries for tenant A: durations 100/200/300/400 → P95 = 400 (cont, nearest-rank
        // on interpolation gives 380.0? percentile_cont(0.95) of [100,200,300,400] = 380).
        pidA1 = insertLog(TENANT_ID, USER_A, 100, false, daysAgo(0));
        pidA2 = insertLog(TENANT_ID, USER_A, 200, true, daysAgo(0));
        pidA3 = insertLog(TENANT_ID, USER_B, 300, false, daysAgo(1));
        pidA4 = insertLog(TENANT_ID, USER_B, 400, true, daysAgo(2));
        // Other tenant's traffic must not leak into tenant A's rollup.
        pidOther = insertLog(OTHER_TENANT_ID, USER_A, 99_999, false, daysAgo(0));
    }

    @AfterAll
    void cleanup() {
        jdbc.update("DELETE FROM ab_semantic_query_log WHERE tenant_id IN (?, ?)", TENANT_ID, OTHER_TENANT_ID);
        MetaContext.clear();
    }

    private String insertLog(long tenantId, long userId, int durationMs, boolean cacheHit, Instant executedAt) {
        String pid = "ug-" + System.nanoTime();
        jdbc.update("INSERT INTO ab_semantic_query_log (id, pid, query_id, tenant_id, user_id, metric_pids, "
                        + "rowcount, duration_ms, cache_hit, executed_at) "
                        + "VALUES (?, ?, ?, ?, ?, '[]', 5, ?, ?, ?)",
                ID_COUNTER.incrementAndGet(), pid, "q-" + pid, tenantId, userId,
                durationMs, cacheHit, Timestamp.from(executedAt));
        return pid;
    }

    private Instant daysAgo(int days) {
        return Instant.now().minusSeconds((long) days * 86_400);
    }

    @Test
    @DisplayName("summary computes count/P95/cache-rate/users with tenant isolation")
    void goldenUsageSummary() {
        MetaContext.setContext(TENANT_ID, USER_A, "usage-golden-pid", "usage-golden-user");
        var summary = usageService.summary(7);

        assertThat(summary.getTotalQueries()).isEqualTo(4);
        assertThat(summary.getActiveUsers()).isEqualTo(2);
        // percentile_cont(0.95) over [100,200,300,400] = 100 + 0.95*(400-100)... linear
        // interpolation: position = 0.95*(4-1) = 2.85 → 300 + 0.85*100 = 385.
        assertThat(summary.getP95DurationMs()).isEqualTo(385.0);
        assertThat(summary.getCacheHitRate()).isEqualTo(0.5);
        assertThat(summary.getTotalRows()).isEqualTo(20);
        assertThat(summary.getDaily()).isNotEmpty();

        // Tenant isolation: the other tenant sees exactly its own one row.
        MetaContext.setContext(OTHER_TENANT_ID, USER_A, "usage-golden-pid", "usage-golden-user");
        var other = usageService.summary(7);
        assertThat(other.getTotalQueries()).isEqualTo(1);
        assertThat(other.getP95DurationMs()).isEqualTo(99_999.0);
        assertThat(other.getActiveUsers()).isEqualTo(1);
    }

    @Test
    @DisplayName("days window is clamped to 1..90")
    void windowClamped() {
        MetaContext.setContext(TENANT_ID, USER_A, "usage-golden-pid", "usage-golden-user");
        assertThat(usageService.summary(0).getDays()).isEqualTo(1);
        assertThat(usageService.summary(500).getDays()).isEqualTo(90);
    }
}
