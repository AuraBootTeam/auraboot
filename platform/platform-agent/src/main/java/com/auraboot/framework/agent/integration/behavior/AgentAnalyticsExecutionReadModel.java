package com.auraboot.framework.agent.integration.behavior;

import com.auraboot.framework.behavior.service.AnalyticsExecutionReadModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.util.*;

/** Existing tenant/actor-scoped agent execution joins, isolated from analytics core. */
@Component
@RequiredArgsConstructor
public class AgentAnalyticsExecutionReadModel implements AnalyticsExecutionReadModel {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    @Override
    public List<Map<String, Object>> statuses(Long tenant, Long actor, Collection<String> adoptionPids) {
        return namedJdbc.queryForList("""
                SELECT a.adoption_pid, t.deleted_flag, latest.run_status,
                       (SELECT count(*) FROM ab_agent_run r WHERE r.tenant_id=a.tenant_id AND r.task_id=a.task_pid) AS attempts
                FROM ab_analytics_task_execution a
                JOIN ab_agent_task t ON t.tenant_id=a.tenant_id AND t.pid=a.task_pid
                LEFT JOIN LATERAL (
                    SELECT run_status FROM ab_agent_run r WHERE r.tenant_id=a.tenant_id AND r.task_id=a.task_pid
                    ORDER BY r.created_at DESC, r.pid DESC LIMIT 1
                ) latest ON TRUE
                WHERE a.tenant_id=:tenant AND a.actor_user_id=:actor AND a.adoption_pid IN (:adoptions)
                """, Map.of("tenant", tenant, "actor", actor, "adoptions", adoptionPids));
    }
    @Override
    public List<Map<String, Object>> businessResults(Long tenant, Long actor, String adoptionPid, int limit, int offset) {
        return jdbc.queryForList("""
                SELECT o.event_id, o.target_type, o.target_key, o.occurred_at, o.payload
                FROM ab_behavior_outcome_outbox o
                JOIN ab_agent_run r ON r.tenant_id=o.tenant_id AND r.pid=o.run_id
                JOIN ab_analytics_task_execution a ON a.tenant_id=r.tenant_id AND a.task_pid=r.task_id
                WHERE a.tenant_id=? AND a.actor_user_id=? AND a.adoption_pid=?
                  AND o.user_id=? AND o.event_name='analytics_business_command_committed'
                ORDER BY o.id ASC LIMIT ? OFFSET ?
                """, tenant, actor, adoptionPid, actor, limit, offset);
    }
}
