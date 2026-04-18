package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.metrics.UserSoulProfileGauges;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
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

import static org.assertj.core.api.Assertions.assertThat;

/** PR-78 — gauges reflect row counts and confidence mean. */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("UserSoulProfileGauges (PR-78)")
class UserSoulProfileGaugesIntegrationTest extends BaseIntegrationTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private MeterRegistry meterRegistry;

    private Long tenantId;
    private String userId;

    @BeforeEach
    void setup() {
        tenantId = 9_820_000L + System.nanoTime() % 10_000;
        userId = "gx_" + Long.toString(System.nanoTime() & 0xffff, 36);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_user_soul_profile WHERE tenant_id = ?", tenantId);
    }

    private String seedActive(double confidence, boolean stale) {
        String pid = UniqueIdGenerator.generate();
        // Each ACTIVE row must have a distinct user_id because of
        // uq_user_soul_profile_active (tenant_id, user_id WHERE status = ACTIVE).
        String uniqueUser = userId + "_" + pid;
        jdbc.update("INSERT INTO ab_agent_user_soul_profile "
                        + "(pid, tenant_id, user_id, version, status, profile, profile_hash, "
                        + " derivation_confidence, stale_flagged_at, activated_at, created_at) "
                        + "VALUES (?, ?, ?, 1, 'ACTIVE', '{}'::jsonb, ?, ?, "
                        + "        CASE WHEN ? THEN NOW() ELSE NULL END, NOW(), NOW())",
                pid, tenantId, uniqueUser, "h:" + pid, confidence, stale);
        return pid;
    }

    @Test
    @DisplayName("active_count gauge reflects DB ACTIVE count")
    void activeCountGauge() {
        double before = meterRegistry.get(UserSoulProfileGauges.ACTIVE_COUNT).gauge().value();
        seedActive(0.90, false);
        seedActive(0.80, false);
        double after = meterRegistry.get(UserSoulProfileGauges.ACTIVE_COUNT).gauge().value();
        assertThat(after - before).isEqualTo(2.0d);
    }

    @Test
    @DisplayName("stale_count gauge reflects ACTIVE rows with stale_flagged_at set")
    void staleCountGauge() {
        double before = meterRegistry.get(UserSoulProfileGauges.STALE_COUNT).gauge().value();
        seedActive(0.90, true);
        seedActive(0.80, false);
        double after = meterRegistry.get(UserSoulProfileGauges.STALE_COUNT).gauge().value();
        assertThat(after - before).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("avg_confidence gauge returns non-negative finite value")
    void avgConfidenceGauge() {
        seedActive(0.80, false);
        seedActive(0.90, false);
        double avg = meterRegistry.get(UserSoulProfileGauges.AVG_CONFIDENCE).gauge().value();
        assertThat(avg).isBetween(0.0d, 1.0d);
    }
}
