package com.auraboot.framework.behavior.mapper;

import com.auraboot.framework.behavior.dto.AnalyticsFunnelCounts;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.Instant;

/** One SQL snapshot of the first ordered path for each analysis initiator. */
@Mapper
public interface AnalyticsFunnelMapper {
    String ELIGIBLE_EVENTS = """
                SELECT * FROM ab_behavior_event
                WHERE tenant_id = #{tenantId} AND created_at <= #{cutoff}
                  AND occurred_at >= #{from} AND occurred_at < #{to}
                  AND source = 'server' AND ((producer_name = 'aurabot-analytics' AND event_name IN
                    ('analytics_requested','analytics_query_succeeded','analytics_result_viewed','analytics_dashboard_used','analytics_report_used'))
                    OR (producer_name = 'server-outcome-outbox' AND event_name IN ('analytics_dashboard_saved','analytics_report_saved')))

                  AND sampling_probability = 1 AND user_id IS NOT NULL AND NULLIF(interaction_id, '') IS NOT NULL
            """;

    @Select("""
            WITH observed AS (
                SELECT * FROM ab_behavior_event
                WHERE tenant_id = #{tenantId} AND created_at <= #{cutoff}
                  AND occurred_at >= #{from} AND occurred_at < #{to}
                  AND source = 'server' AND ((producer_name = 'aurabot-analytics' AND event_name IN
                    ('analytics_requested','analytics_query_succeeded','analytics_result_viewed','analytics_dashboard_used','analytics_report_used'))
                    OR (producer_name = 'server-outcome-outbox' AND event_name IN ('analytics_dashboard_saved','analytics_report_saved')))
            ), eligible AS (
                SELECT * FROM observed WHERE sampling_probability = 1
                  AND user_id IS NOT NULL AND NULLIF(interaction_id, '') IS NOT NULL
            ), cohort AS (
                SELECT DISTINCT ON (e.user_id, e.interaction_id) e.*
                FROM eligible e
                WHERE e.event_name = 'analytics_requested'
                  AND NOT (EXISTS (SELECT 1 FROM ab_behavior_event earlier
                    WHERE earlier.tenant_id = #{tenantId} AND earlier.user_id = e.user_id
                      AND earlier.interaction_id = e.interaction_id
                      AND earlier.event_name = 'analytics_requested' AND earlier.source = 'server'
                      AND earlier.producer_name = 'aurabot-analytics' AND earlier.created_at <= #{cutoff}
                      AND earlier.occurred_at < #{from}))
                ORDER BY e.user_id, e.interaction_id, e.occurred_at, e.id
            ), chain AS (
                SELECT c.tenant_id, c.user_id, c.interaction_id, c.occurred_at AS requested_at,
                       s.occurred_at AS succeeded_at, s.props->>'queryHash' AS query_hash,
                       v.occurred_at AS viewed_at, d.occurred_at AS saved_at,
                       d.props->>'targetKey' AS artifact_pid, d.props->>'targetType' AS artifact_type, u.occurred_at AS used_at
                FROM cohort c
                LEFT JOIN LATERAL (
                    SELECT e.* FROM (""" + ELIGIBLE_EVENTS + """
                    ) e WHERE e.user_id = c.user_id AND e.interaction_id = c.interaction_id
                      AND e.event_name = 'analytics_query_succeeded' AND e.occurred_at > c.occurred_at
                      AND NULLIF(e.props->>'queryHash', '') IS NOT NULL
                    ORDER BY e.occurred_at, e.id LIMIT 1
                ) s ON TRUE
                LEFT JOIN LATERAL (
                    SELECT e.* FROM (""" + ELIGIBLE_EVENTS + """
                    ) e WHERE e.user_id = c.user_id AND e.interaction_id = c.interaction_id
                      AND e.event_name = 'analytics_result_viewed' AND e.occurred_at > s.occurred_at
                      AND e.props->>'queryHash' = s.props->>'queryHash'
                      AND e.props->>'signalSource' = 'client_visible'
                    ORDER BY e.occurred_at, e.id LIMIT 1
                ) v ON TRUE
                LEFT JOIN LATERAL (
                    SELECT e.* FROM (""" + ELIGIBLE_EVENTS + """
                    ) e WHERE e.user_id = c.user_id AND e.interaction_id = c.interaction_id
                      AND ((e.event_name = 'analytics_dashboard_saved' AND e.props->>'targetType' = 'dashboard')
                        OR (e.event_name = 'analytics_report_saved' AND e.props->>'targetType' = 'report')) AND e.occurred_at > v.occurred_at
                      AND e.props->>'queryHash' = s.props->>'queryHash'
                      AND NULLIF(e.props->>'targetKey', '') IS NOT NULL
                    ORDER BY e.occurred_at, e.id LIMIT 1
                ) d ON TRUE
                LEFT JOIN LATERAL (
                    SELECT e.* FROM (""" + ELIGIBLE_EVENTS + """
                    ) e WHERE e.user_id = c.user_id AND e.interaction_id = c.interaction_id
                      AND ((e.event_name = 'analytics_dashboard_used' AND e.props->>'targetType' = 'dashboard') OR (e.event_name = 'analytics_report_used' AND e.props->>'targetType' = 'report' AND e.props->>'usageKind' = 'export_generated')) AND e.occurred_at > d.occurred_at
                      AND e.props->>'queryHash' = s.props->>'queryHash'
                      AND e.props->>'targetType' = d.props->>'targetType'
                      AND e.props->>'targetKey' = d.props->>'targetKey' AND e.props->>'originalQuery' = 'true'
                    ORDER BY e.occurred_at, e.id LIMIT 1
                ) u ON TRUE
            )
            SELECT count(*) AS requested, count(succeeded_at) AS succeeded, count(viewed_at) AS viewed,
                   count(saved_at) AS saved, count(used_at) AS used,
                   (SELECT count(*) FROM observed WHERE user_id IS NULL OR NULLIF(interaction_id, '') IS NULL) AS missing_correlation_events,
                   (SELECT count(*) FROM (SELECT DISTINCT e.user_id, e.interaction_id FROM eligible e
                      WHERE NOT (EXISTS (SELECT 1 FROM cohort c WHERE c.user_id = e.user_id AND c.interaction_id = e.interaction_id))) outside_cohort) AS without_window_entry_tasks,
                   (SELECT count(*) FROM eligible e JOIN chain c ON c.user_id = e.user_id AND c.interaction_id = e.interaction_id
                      WHERE e.event_name <> 'analytics_requested' AND CASE
                        WHEN e.event_name = 'analytics_query_succeeded' AND e.occurred_at > c.requested_at AND NULLIF(e.props->>'queryHash', '') IS NOT NULL THEN 1
                        WHEN e.event_name = 'analytics_result_viewed' AND e.occurred_at > c.succeeded_at AND e.props->>'queryHash' = c.query_hash AND e.props->>'signalSource' = 'client_visible' THEN 1
                        WHEN ((e.event_name = 'analytics_dashboard_saved' AND e.props->>'targetType' = 'dashboard') OR (e.event_name = 'analytics_report_saved' AND e.props->>'targetType' = 'report')) AND e.occurred_at > c.viewed_at AND e.props->>'queryHash' = c.query_hash AND NULLIF(e.props->>'targetKey', '') IS NOT NULL THEN 1
                        WHEN ((e.event_name = 'analytics_dashboard_used' AND e.props->>'targetType' = 'dashboard') OR (e.event_name = 'analytics_report_used' AND e.props->>'targetType' = 'report' AND e.props->>'usageKind' = 'export_generated')) AND e.occurred_at > c.saved_at AND e.props->>'queryHash' = c.query_hash AND e.props->>'targetType' = c.artifact_type AND e.props->>'targetKey' = c.artifact_pid AND e.props->>'originalQuery' = 'true' THEN 1
                        ELSE 0 END = 0) AS unmatched_stage_events,
                   (SELECT count(*) FROM observed WHERE sampling_probability IS DISTINCT FROM 1) AS sampled_events
            FROM chain
            """)
    AnalyticsFunnelCounts count(@Param("tenantId") Long tenantId, @Param("from") Instant from,
                               @Param("to") Instant to, @Param("cutoff") Instant cutoff);
}
