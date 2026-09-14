package com.auraboot.framework.behavior.mapper;

import com.auraboot.framework.behavior.dto.AnalyticsExecutionCounts;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.time.Instant;

@Mapper
public interface AnalyticsExecutionMapper {
    @Select("""
            WITH observed AS (
                SELECT * FROM ab_behavior_event
                WHERE tenant_id = #{tenantId} AND created_at <= #{cutoff} AND occurred_at <= #{cutoff}
                  AND source = 'server' AND producer_name = 'server-outcome-outbox'
                  AND sampling_probability = 1 AND NULLIF(run_id, '') IS NOT NULL
                  AND props->>'targetType' = 'agent_run' AND props->>'targetKey' = run_id
                  AND event_name IN ('agent_execution_started', 'agent_execution_completed')
            ), first_starts AS (
                SELECT DISTINCT ON (run_id) * FROM observed
                WHERE event_name = 'agent_execution_started'
                ORDER BY run_id, occurred_at, id
            ), cohort AS (
                SELECT * FROM first_starts WHERE occurred_at >= #{from} AND occurred_at < #{to}
            ), outcomes AS (
                SELECT s.*, c.props->>'status' AS terminal_status
                FROM cohort s LEFT JOIN LATERAL (
                    SELECT e.* FROM observed e
                    WHERE e.run_id = s.run_id AND e.event_name = 'agent_execution_completed'
                      AND e.caused_by_event_id = s.event_id AND e.occurred_at >= s.occurred_at
                      AND e.props->>'taskPid' = s.props->>'taskPid'
                      AND e.props->>'status' IN ('success','failed','cancelled')
                    ORDER BY e.occurred_at, e.id LIMIT 1
                ) c ON TRUE
            )
            SELECT count(*) FILTER (WHERE COALESCE(props->>'principalType', 'unknown') != 'sandbox') AS started,
                   count(*) FILTER (WHERE COALESCE(props->>'principalType', 'unknown') != 'sandbox' AND terminal_status='success') AS succeeded,
                   count(*) FILTER (WHERE COALESCE(props->>'principalType', 'unknown') != 'sandbox' AND terminal_status='failed') AS failed,
                   count(*) FILTER (WHERE COALESCE(props->>'principalType', 'unknown') != 'sandbox' AND terminal_status='cancelled') AS cancelled,
                   count(*) FILTER (WHERE COALESCE(props->>'principalType', 'unknown') != 'sandbox' AND terminal_status IS NULL) AS unresolved,
                   count(*) FILTER (WHERE props->>'principalType' = 'sandbox') AS excluded_sandbox,
                   count(*) FILTER (WHERE COALESCE(props->>'principalType', 'unknown') NOT IN
                     ('sandbox','human_delegated','digital_employee','system')) AS unknown_principal
            FROM outcomes
            """)
    AnalyticsExecutionCounts count(@Param("tenantId") Long tenantId, @Param("from") Instant from,
                                   @Param("to") Instant to, @Param("cutoff") Instant cutoff);
}
