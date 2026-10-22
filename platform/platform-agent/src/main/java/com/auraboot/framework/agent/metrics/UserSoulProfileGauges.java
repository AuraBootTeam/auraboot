package com.auraboot.framework.agent.metrics;

import com.auraboot.framework.agent.profile.UserSoulProfileStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Cross-tenant aggregate gauges for the User Soul Profile pipeline (PR-78).
 *
 * <p>Three Prometheus gauges that back the plan §8 operational dashboard:
 * <ul>
 *   <li>{@code auraboot_user_soul_profile_active_count} — total ACTIVE rows
 *       across all tenants.</li>
 *   <li>{@code auraboot_user_soul_profile_stale_count} — subset where
 *       {@code stale_flagged_at IS NOT NULL AND status = 'ACTIVE'}.</li>
 *   <li>{@code auraboot_user_soul_profile_avg_confidence} — mean
 *       {@code derivation_confidence} over ACTIVE rows; {@code 0.0} when
 *       no ACTIVE rows exist.</li>
 * </ul>
 *
 * <p><b>Cardinality choice.</b> Same as
 * {@link MemoryPromotionGauges}: cross-tenant aggregates only. Per-tenant
 * values are available via {@code /api/admin/user-soul-profiles/stats}. A
 * dynamic per-tenant gauge set would need N registrations at runtime, which
 * Micrometer's Gauge API does not handle cheaply. Plan §8 lists the gauges
 * with a {@code tenant} tag as aspiration; aggregates are sufficient for the
 * staleness alert.
 *
 * <p>Counters (derivation / activation / stale / edit / manual-derive) live in
 * {@link UserSoulProfileMetrics} — kept separate to mirror Memory Promotion's
 * structure and avoid merge conflicts.
 */
@Configuration
@RequiredArgsConstructor
public class UserSoulProfileGauges {

    public static final String ACTIVE_COUNT = "auraboot_user_soul_profile_active_count";
    public static final String STALE_COUNT = "auraboot_user_soul_profile_stale_count";
    public static final String AVG_CONFIDENCE = "auraboot_user_soul_profile_avg_confidence";

    @Bean
    public MeterBinder userSoulProfileGaugesBinder(JdbcTemplate jdbc) {
        return (MeterRegistry registry) -> {
            Gauge.builder(ACTIVE_COUNT,
                    () -> countOrZero(jdbc,
                            "SELECT COUNT(*) FROM ab_agent_user_soul_profile WHERE status = ?",
                            UserSoulProfileStatus.ACTIVE.code()))
                    .description("Total User Soul Profile rows in ACTIVE status (cross-tenant)")
                    .register(registry);

            Gauge.builder(STALE_COUNT,
                    () -> countOrZero(jdbc,
                            "SELECT COUNT(*) FROM ab_agent_user_soul_profile " +
                                    "WHERE status = ? AND stale_flagged_at IS NOT NULL",
                            UserSoulProfileStatus.ACTIVE.code()))
                    .description("Total ACTIVE User Soul Profile rows flagged as stale (cross-tenant)")
                    .register(registry);

            Gauge.builder(AVG_CONFIDENCE,
                    () -> avgConfidence(jdbc))
                    .description("Population mean derivation_confidence across ACTIVE profiles (cross-tenant)")
                    .register(registry);
        };
    }

    private static double countOrZero(JdbcTemplate jdbc, String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0.0d : value.doubleValue();
    }

    private static double avgConfidence(JdbcTemplate jdbc) {
        Double value = jdbc.queryForObject(
                "SELECT COALESCE(AVG(derivation_confidence), 0) " +
                        "FROM ab_agent_user_soul_profile WHERE status = ?",
                Double.class, UserSoulProfileStatus.ACTIVE.code());
        return value == null ? 0.0d : value;
    }
}
