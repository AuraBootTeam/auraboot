package com.auraboot.framework.agent.integration.behavior;

import com.auraboot.framework.behavior.outcome.AnalyticsCommandOriginLookup;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/** Keeps the agent run schema behind the analytics provenance contract. */
@Component
@RequiredArgsConstructor
public class AgentAnalyticsCommandOriginLookup implements AnalyticsCommandOriginLookup {
    private final JdbcTemplate jdbc;
    @Override
    public List<Map<String, Object>> find(Long tenantId, String runPid) {
        return jdbc.queryForList("""
                SELECT r.actor_user_id, r.principal_type, r.run_status, a.binding,
                       started.event_id AS started_event_id, started.payload AS started_payload
                FROM ab_agent_run r
                JOIN ab_analytics_task_execution a ON a.tenant_id=r.tenant_id AND a.task_pid=r.task_id
                LEFT JOIN ab_behavior_outcome_outbox started ON started.tenant_id=r.tenant_id
                  AND started.run_id=r.pid AND started.event_name='agent_execution_started'
                WHERE r.tenant_id=? AND r.pid=?
                FOR SHARE OF r
                """, tenantId, runPid);
    }
}
