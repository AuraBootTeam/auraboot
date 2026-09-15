package com.auraboot.framework.behavior.service;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.behavior.dto.AnalyticsRetention;
import com.auraboot.framework.behavior.dto.BehaviorQueryWindow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retention maturity matrix over the real SQL: backdated server usage events (the only
 * valid retention input — clients cannot forge them) drive cohorts whose D+1/D+7/D+30
 * horizons cross the cutoff, pinning mature/immature flips, same-day cohort merging,
 * distinct-daily-presence dedup and the eligibility filters.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
class AnalyticsRetentionMaturityIT {

    private static final long TENANT = 990_361L;
    private static final long USER_FULL = 990_362L;
    private static final long USER_NONE = 990_363L;
    private static final long USER_LATE = 990_364L;
    private static final long USER_EXCLUDED = 990_365L;
    private static final long USER_ARTIFACT = 990_366L;
    private static final long USER_BOUNDARY = 990_367L;

    // D0 is 45 days in the past so real ingestion time (created_at) stays below the cutoff.
    private static final Instant D0 = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
            .minusDays(45).atTime(10, 0).toInstant(java.time.ZoneOffset.UTC);
    private static final Instant CUTOFF = D0.plus(Duration.ofDays(60));

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AnalyticsRetentionService service;

    @BeforeEach
    void setup() {
        com.auraboot.framework.application.tenant.MetaContext.setContext(TENANT, USER_FULL, "retention-it", "retention-it");
        ensureTables();
        cleanup();
    }

    @AfterEach
    void tearDown() {
        cleanup();
        com.auraboot.framework.application.tenant.MetaContext.clear();
    }

    @Test
    void userCohortsMergePerDayAndMaturityFollowsCutoff() {
        usage(USER_FULL, "dashboard", "dash-full", D0);
        usage(USER_FULL, "dashboard", "dash-full", D0.plus(Duration.ofDays(1)).plus(Duration.ofHours(1)));
        usage(USER_FULL, "dashboard", "dash-full", D0.plus(Duration.ofDays(1)).plus(Duration.ofHours(2)));
        usage(USER_FULL, "dashboard", "dash-full", D0.plus(Duration.ofDays(7)));
        usage(USER_FULL, "dashboard", "dash-full", D0.plus(Duration.ofDays(30)));
        usage(USER_NONE, "dashboard", "dash-none", D0.plus(Duration.ofHours(1)));
        usage(USER_LATE, "dashboard", "dash-late", D0.plus(Duration.ofDays(30)));
        usage(USER_LATE, "dashboard", "dash-late", D0.plus(Duration.ofDays(31)));
        usage(USER_BOUNDARY, "dashboard", "dash-bound", D0.plus(Duration.ofDays(58)));
        usage(USER_BOUNDARY, "dashboard", "dash-bound", D0.plus(Duration.ofDays(59)));
        excludedVariants();

        AnalyticsRetention result = service.query(TENANT, "user", window(), CUTOFF);

        assertThat(result.definitionVersion()).isEqualTo("analytics-first-use-retention-v1");
        assertThat(result.cohortRule()).isEqualTo("first_successful_dashboard_use");
        assertThat(result.timezone()).isEqualTo("UTC");
        assertThat(result.completeness()).isEqualTo("observed_events_not_delivery_guaranteed");
        assertThat(result.dataCutoff()).isEqualTo(CUTOFF);

        assertThat(point(result, day(0), 1)).satisfies(p -> {
            assertThat(p.cohortSize()).isEqualTo(2);
            assertThat(p.retained()).isEqualTo(1L);
            assertThat(p.retentionRate()).isEqualByComparingTo(new BigDecimal("0.500000"));
            assertThat(p.status()).isEqualTo("observed");
        });
        assertThat(point(result, day(0), 7)).satisfies(p -> {
            assertThat(p.retained()).isEqualTo(1L);
            assertThat(p.status()).isEqualTo("observed");
        });
        assertThat(point(result, day(0), 30)).satisfies(p -> {
            assertThat(p.retained()).isEqualTo(1L);
            assertThat(p.status()).isEqualTo("observed");
        });
        // Late cohort: D+1 and D+7 are already mature, D+30 is still immature at the cutoff.
        assertThat(point(result, day(30), 1)).satisfies(p -> {
            assertThat(p.cohortSize()).isEqualTo(1);
            assertThat(p.retained()).isEqualTo(1L);
            assertThat(p.status()).isEqualTo("observed");
        });
        assertThat(point(result, day(30), 7)).satisfies(p -> {
            assertThat(p.retained()).isEqualTo(0L);
            assertThat(p.retentionRate()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(p.status()).isEqualTo("observed");
        });
        assertThat(point(result, day(30), 30)).satisfies(p -> {
            assertThat(p.retained()).isNull();
            assertThat(p.retentionRate()).isNull();
            assertThat(p.status()).isEqualTo("immature");
        });
        // Boundary cohort D0+58: only the D+1 horizon (cohort+2d == cutoff) is mature.
        assertThat(point(result, day(58), 1)).satisfies(p -> {
            assertThat(p.cohortSize()).isEqualTo(1);
            assertThat(p.retained()).isEqualTo(1L);
            assertThat(p.status()).isEqualTo("observed");
        });
        assertThat(point(result, day(58), 7)).satisfies(p -> {
            assertThat(p.retained()).isNull();
            assertThat(p.status()).isEqualTo("immature");
        });
        assertThat(point(result, day(58), 30)).satisfies(p -> {
            assertThat(p.retained()).isNull();
            assertThat(p.status()).isEqualTo("immature");
        });
        // The excluded-eligibility user never forms a cohort: cohort sizes above prove it
        // (each asserted day bucket contains exactly the listed valid identities).
    }

    @Test
    void artifactCohortsKeyOnTargetAndAcceptOnlyExportGeneratedReports() {
        usage(USER_ARTIFACT, "report", "art-a", D0);
        usage(USER_ARTIFACT, "report", "art-a", D0.plus(Duration.ofDays(1)));
        usage(USER_ARTIFACT, "report", "art-a", D0.plus(Duration.ofDays(7)));
        usage(USER_ARTIFACT, "report", "art-a", D0.plus(Duration.ofDays(30)));
        usage(USER_ARTIFACT, "dashboard", "art-b", D0.plus(Duration.ofHours(2)));

        AnalyticsRetention result = service.query(TENANT, "artifact", window(), CUTOFF);

        assertThat(result.unit()).isEqualTo("artifact");
        assertThat(point(result, day(0), 1)).satisfies(p -> {
            assertThat(p.cohortSize()).isEqualTo(2);
            assertThat(p.retained()).isEqualTo(1L);
            assertThat(p.retentionRate()).isEqualByComparingTo(new BigDecimal("0.500000"));
        });
        assertThat(point(result, day(0), 30)).satisfies(p -> {
            assertThat(p.cohortSize()).isEqualTo(2);
            assertThat(p.retained()).isEqualTo(1L);
            assertThat(p.status()).isEqualTo("observed");
        });
    }

    private static String uuid() {
        return "ret-it-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private AnalyticsRetention.Point point(AnalyticsRetention result, String cohortDay, int dayOffset) {
        return result.records().stream()
                .filter(p -> p.cohortDay().equals(cohortDay) && p.dayOffset() == dayOffset)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing point " + cohortDay + "/" + dayOffset));
    }

    private static String day(int offset) {
        return java.time.LocalDate.ofInstant(D0.plus(Duration.ofDays(offset)), java.time.ZoneOffset.UTC).toString();
    }

    private BehaviorQueryWindow window() {
        return new BehaviorQueryWindow(D0, D0.plus(Duration.ofDays(59)));
    }

    private void usage(long user, String targetType, String targetKey, Instant occurredAt) {
        jdbc.update("""
                INSERT INTO ab_behavior_event
                  (event_id, event_name, source, tenant_id, user_id, interaction_id,
                   occurred_at, props, producer_name, sampling_probability)
                VALUES (?, ?, 'server', ?, ?, ?, ?::timestamptz,
                        ?::jsonb, 'aurabot-analytics', 1)
                """,
                "ret-it-" + uuid().toString().replace("-", "").substring(0, 20),
                "report".equals(targetType) ? "analytics_report_used" : "analytics_dashboard_used",
                TENANT, user, "it-" + user, Timestamp.from(occurredAt),
                "{\"targetType\":\"" + targetType + "\",\"targetKey\":\"" + targetKey + "\""
                        + ("report".equals(targetType) ? ",\"usageKind\":\"export_generated\"" : "") + "}");
    }

    private void excludedVariants() {
        long user = USER_EXCLUDED;
        jdbc.update("""
                INSERT INTO ab_behavior_event
                  (event_id, event_name, source, tenant_id, user_id, interaction_id,
                   occurred_at, props, producer_name, sampling_probability)
                VALUES (?, 'analytics_dashboard_used', 'web', ?, ?, ?, ?::timestamptz,
                        ?::jsonb, 'aurabot-analytics', 1)
                """, "ret-it-" + uuid(), TENANT, user, "it-x", Timestamp.from(D0),
                "{\"targetType\":\"dashboard\",\"targetKey\":\"dash-x\"}");
        jdbc.update("""
                INSERT INTO ab_behavior_event
                  (event_id, event_name, source, tenant_id, user_id, interaction_id,
                   occurred_at, props, producer_name, sampling_probability)
                VALUES (?, 'analytics_dashboard_used', 'server', ?, ?, ?, ?::timestamptz,
                        ?::jsonb, 'aurabot-analytics', 0.5)
                """, "ret-it-" + uuid(), TENANT, user, "it-x", Timestamp.from(D0),
                "{\"targetType\":\"dashboard\",\"targetKey\":\"dash-x\"}");
        jdbc.update("""
                INSERT INTO ab_behavior_event
                  (event_id, event_name, source, tenant_id, user_id, interaction_id,
                   occurred_at, props, producer_name, sampling_probability)
                VALUES (?, 'analytics_dashboard_used', 'server', ?, ?, NULL, ?::timestamptz,
                        ?::jsonb, 'aurabot-analytics', 1)
                """, "ret-it-" + uuid(), TENANT, user, Timestamp.from(D0),
                "{\"targetType\":\"dashboard\",\"targetKey\":\"dash-x\"}");
        jdbc.update("""
                INSERT INTO ab_behavior_event
                  (event_id, event_name, source, tenant_id, user_id, interaction_id,
                   occurred_at, props, producer_name, sampling_probability)
                VALUES (?, 'analytics_report_used', 'server', ?, ?, ?, ?::timestamptz,
                        ?::jsonb, 'aurabot-analytics', 1)
                """, "ret-it-" + uuid(), TENANT, user, "it-x", Timestamp.from(D0),
                "{\"targetType\":\"report\",\"targetKey\":\"dash-x\"}");
    }

    private void cleanup() {
        jdbc.update("DELETE FROM ab_behavior_event WHERE tenant_id = ?", TENANT);
    }

    private void ensureTables() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ab_behavior_event (
                id BIGSERIAL PRIMARY KEY,
                event_id VARCHAR(40) NOT NULL,
                schema_version VARCHAR(16),
                event_name VARCHAR(120) NOT NULL,
                event_category VARCHAR(32),
                source VARCHAR(24),
                identity_quality VARCHAR(16),
                occurred_at TIMESTAMPTZ,
                received_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
                tenant_id BIGINT NOT NULL,
                user_id BIGINT,
                anon_id VARCHAR(64),
                client_session_id VARCHAR(64),
                interaction_id VARCHAR(64),
                caused_by_event_id VARCHAR(40),
                trace_id VARCHAR(36),
                source_span_id VARCHAR(36),
                run_id VARCHAR(64),
                ui_element_id VARCHAR(80),
                app_id VARCHAR(64),
                page_id VARCHAR(64),
                block_id VARCHAR(64),
                element_code VARCHAR(64),
                props JSONB,
                consent_state VARCHAR(24),
                consent_version VARCHAR(16),
                sampling_unit VARCHAR(16),
                sampling_probability NUMERIC(6,5),
                producer_name VARCHAR(48),
                producer_version VARCHAR(24),
                created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
            )""");
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uk_ab_behavior_event_tenant_eventid "
                + "ON ab_behavior_event (tenant_id, event_id)");
    }
}
