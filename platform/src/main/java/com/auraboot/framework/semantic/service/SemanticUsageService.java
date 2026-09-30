package com.auraboot.framework.semantic.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.semantic.dto.SemanticUsageSummaryDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * BI usage rollup over the semantic query log (BI rectification R1).
 *
 * <p>Pure read-side: every number is derived from {@code ab_semantic_query_log}
 * rows the governed pipeline already writes (duration, cache hit, user), so the
 * usage surface can never drift from what actually executed.
 */
@Service
@RequiredArgsConstructor
public class SemanticUsageService {

    private static final int MAX_DAYS = 90;

    private final JdbcTemplate jdbc;

    public SemanticUsageSummaryDTO summary(int days) {
        int window = Math.min(Math.max(days, 1), MAX_DAYS);
        Long tenantId = MetaContext.get().getTenantId();

        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT count(*) AS total_queries,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) AS p95_duration,
                       avg(CASE WHEN cache_hit THEN 1 ELSE 0 END) AS cache_hit_rate,
                       count(DISTINCT user_id) AS active_users,
                       COALESCE(sum(rowcount), 0) AS total_rows
                FROM ab_semantic_query_log
                WHERE tenant_id = ? AND executed_at > NOW() - make_interval(days => ?)
                """, tenantId, window);

        List<Map<String, Object>> daily = jdbc.queryForList("""
                SELECT to_char(date_trunc('day', executed_at), 'YYYY-MM-DD') AS day,
                       count(*) AS queries,
                       percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) AS p95_duration
                FROM ab_semantic_query_log
                WHERE tenant_id = ? AND executed_at > NOW() - make_interval(days => ?)
                GROUP BY 1 ORDER BY 1
                """, tenantId, window);

        List<SemanticUsageSummaryDTO.DailyUsage> dailyUsage = daily.stream()
                .map(row -> SemanticUsageSummaryDTO.DailyUsage.builder()
                        .day(String.valueOf(row.get("day")))
                        .queries(asLong(row.get("queries")))
                        .p95DurationMs(asDouble(row.get("p95_duration")))
                        .build())
                .toList();

        return SemanticUsageSummaryDTO.builder()
                .days(window)
                .totalQueries(asLong(totals.get("total_queries")))
                .p95DurationMs(asDouble(totals.get("p95_duration")))
                .cacheHitRate(asDouble(totals.get("cache_hit_rate")))
                .activeUsers(asLong(totals.get("active_users")))
                .totalRows(asLong(totals.get("total_rows")))
                .daily(dailyUsage)
                .build();
    }

    private long asLong(Object value) {
        if (value == null) return 0;
        if (value instanceof Number n) return n.longValue();
        return Long.parseLong(String.valueOf(value));
    }

    private Double asDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.doubleValue();
        return Double.parseDouble(String.valueOf(value));
    }
}
