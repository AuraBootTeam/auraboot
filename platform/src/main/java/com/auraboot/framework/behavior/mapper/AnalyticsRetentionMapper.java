package com.auraboot.framework.behavior.mapper;

import com.auraboot.framework.behavior.dto.AnalyticsRetentionPoint;
import org.apache.ibatis.annotations.*;
import java.time.Instant;
import java.util.List;

/** First use is computed over all observed history before selecting the requested cohort. */
@Mapper
public interface AnalyticsRetentionMapper {
    @Select("""
            WITH valid_events AS (
                SELECT tenant_id, occurred_at,
                       CASE WHEN #{unit} = 'user' THEN CAST(user_id AS text) ELSE (props->>'targetType') || ':' || (props->>'targetKey') END AS identity_key
                FROM ab_behavior_event
                WHERE tenant_id = #{tenantId} AND created_at <= #{cutoff} AND occurred_at < #{cutoff}
                  AND ((event_name = 'analytics_dashboard_used' AND props->>'targetType' = 'dashboard') OR (event_name = 'analytics_report_used' AND props->>'targetType' = 'report' AND props->>'usageKind' = 'export_generated')) AND source = 'server'
                  AND producer_name = 'aurabot-analytics' AND sampling_probability = 1
                  AND user_id IS NOT NULL AND NULLIF(interaction_id, '') IS NOT NULL
                  AND NULLIF(props->>'targetKey', '') IS NOT NULL
            ), first_use AS (
                SELECT tenant_id, identity_key, min(occurred_at) AS first_at
                FROM valid_events GROUP BY tenant_id, identity_key
            ), cohort AS (
                SELECT tenant_id, identity_key, date_trunc('day', first_at AT TIME ZONE 'UTC') AS cohort_day
                FROM first_use WHERE first_at >= #{from} AND first_at < #{to}
            ), horizons AS (
                SELECT CAST(#{tenantId} AS bigint) AS tenant_id, 1 AS day_offset
                UNION ALL SELECT CAST(#{tenantId} AS bigint), 7
                UNION ALL SELECT CAST(#{tenantId} AS bigint), 30
            ), daily_presence AS (
                SELECT DISTINCT tenant_id, identity_key, date_trunc('day', occurred_at AT TIME ZONE 'UTC') AS active_day
                FROM valid_events
            )
            SELECT to_char(c.cohort_day, 'YYYY-MM-DD') AS cohort_day, h.day_offset,
                   count(*) AS cohort_size, count(p.identity_key) AS returning_count,
                   CASE WHEN ((c.cohort_day + (h.day_offset + 1) * INTERVAL '1 day') AT TIME ZONE 'UTC') <= #{cutoff}
                        THEN TRUE ELSE FALSE END AS mature
            FROM cohort c CROSS JOIN horizons h
            LEFT JOIN daily_presence p ON p.identity_key = c.identity_key
                 AND p.active_day = c.cohort_day + h.day_offset * INTERVAL '1 day'
            GROUP BY c.tenant_id, c.cohort_day, h.day_offset
            ORDER BY c.cohort_day, h.day_offset
            """)
    List<AnalyticsRetentionPoint> query(@Param("tenantId") Long tenantId, @Param("unit") String unit,
            @Param("from") Instant from, @Param("to") Instant to, @Param("cutoff") Instant cutoff);
}
