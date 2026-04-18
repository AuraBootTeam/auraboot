package com.auraboot.framework.agent.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Cross-tenant aggregate gauges for the Memory Promotion pipeline (PR-67).
 *
 * <p>Exposes three Prometheus gauges that the reviewer-backlog alert rules
 * (see {@code docs/operations/learning-loop-alerts.yaml}) evaluate:
 *
 * <ul>
 *   <li>{@code auraboot_memory_promotion_pending_count} — total rows in
 *       {@code DRAFT_PENDING_REVIEW}. Triggers {@code MemoryReviewerStalled}
 *       when high for extended windows.</li>
 *   <li>{@code auraboot_memory_promotion_shadow_count} — total rows in
 *       {@code PROMOTED_SHADOW}; lets operators eyeball in-flight
 *       observation volume.</li>
 *   <li>{@code auraboot_memory_promotion_reviewer_backlog_seconds} — wall-clock
 *       age of the oldest pending proposal. Primary SLO surface for the
 *       review queue.</li>
 * </ul>
 *
 * <p><b>Cardinality choice — tenant-aggregate, not per-tenant.</b> Micrometer
 * {@code Gauge} is single-value; multi-dimensional tenant tagging would need
 * N meters registered dynamically, which the registry does not support
 * cheaply. The plan §10 lists these gauges with {@code tenant} tags in the
 * aspiration set; for v1 we ship aggregates which are sufficient for the
 * {@code MemoryReviewerStalled} alert and defer per-tenant breakdown to a
 * dedicated endpoint queried on demand. Per-tenant counts are still
 * available via {@code GET /api/memory/promotions/stats}.
 *
 * <p>Counters (proposal / decision / retraction) live in
 * {@link MemoryPromotionMetrics} — kept separate to avoid merge conflicts
 * with Phase 2 which extends that class.
 */
@Configuration
@RequiredArgsConstructor
public class MemoryPromotionGauges {

    public static final String PENDING_COUNT = "auraboot_memory_promotion_pending_count";
    public static final String SHADOW_COUNT = "auraboot_memory_promotion_shadow_count";
    public static final String REVIEWER_BACKLOG_SECONDS = "auraboot_memory_promotion_reviewer_backlog_seconds";

    @Bean
    public MeterBinder memoryPromotionGaugesBinder(JdbcTemplate jdbc) {
        return (MeterRegistry registry) -> {
            Gauge.builder(PENDING_COUNT,
                    () -> countOrZero(jdbc,
                            "SELECT COUNT(*) FROM ab_agent_memory_promotion WHERE status = 'DRAFT_PENDING_REVIEW'"))
                    .description("Total memory promotion proposals awaiting reviewer action (cross-tenant)")
                    .register(registry);

            Gauge.builder(SHADOW_COUNT,
                    () -> countOrZero(jdbc,
                            "SELECT COUNT(*) FROM ab_agent_memory_promotion WHERE status = 'PROMOTED_SHADOW'"))
                    .description("Total memory promotions currently in shadow observation window (cross-tenant)")
                    .register(registry);

            Gauge.builder(REVIEWER_BACKLOG_SECONDS,
                    () -> oldestPendingSeconds(jdbc))
                    .description("Age of the oldest pending memory promotion in seconds (cross-tenant)")
                    .register(registry);
        };
    }

    private static double countOrZero(JdbcTemplate jdbc, String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0.0d : value.doubleValue();
    }

    private static double oldestPendingSeconds(JdbcTemplate jdbc) {
        Double value = jdbc.queryForObject(
                "SELECT COALESCE(EXTRACT(EPOCH FROM (NOW() - MIN(created_at))), 0) " +
                        "  FROM ab_agent_memory_promotion WHERE status = 'DRAFT_PENDING_REVIEW'",
                Double.class);
        return value == null ? 0.0d : value;
    }
}
