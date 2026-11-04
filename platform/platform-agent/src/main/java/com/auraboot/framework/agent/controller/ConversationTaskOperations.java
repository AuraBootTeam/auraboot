package com.auraboot.framework.agent.controller;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

/** Read projection of existing conversation tasks, traces, decisions and the usage ledger. */
final class ConversationTaskOperations {
    private final JdbcTemplate jdbc;
    ConversationTaskOperations(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static final String SCOPE = " tenant_id = ? AND agent_task_metadata_v1(input_data)->>'channel' = 'web' ";
    private static final String RELATED = " r.tenant_id = t.tenant_id AND (r.metadata->>'taskPid' = t.pid OR r.trace_id = m.trace_id) ";
    private static final String PROJECTION = """
        SELECT t.pid AS "taskPid", t.title, t.task_status AS status, t.assignee_id AS "agentCode",
               a.pid AS "agentPid", a.name AS "agentName", t.created_at AS "createdAt", t.completed_at AS "completedAt",
               agent_task_metadata_v1(t.input_data)->>'conversationId' AS "conversationId", agent_task_metadata_v1(t.input_data)->>'turnId' AS "turnId",
               tr.trace_id AS "traceId", tr.status AS "traceStatus", tr.error_message AS "traceError",
               u.calls AS "usageCalls", u.input_tokens AS "inputTokens", u.output_tokens AS "outputTokens",
               u.cost_status AS "costStatus", CASE WHEN u.cost_status = 'known' THEN u.amount ELSE NULL END AS "knownAmount", u.currency,
               (SELECT COUNT(*) FROM ab_agent_approval ap WHERE ap.tenant_id=t.tenant_id
                 AND (ap.task_id=t.pid OR ap.run_id=agent_task_metadata_v1(t.input_data)->>'turnId')) AS "approvalCount"
          FROM tasks t
          LEFT JOIN ab_agent_definition a ON a.tenant_id=t.tenant_id AND a.agent_code=t.assignee_id AND a.deleted_flag=FALSE
          LEFT JOIN LATERAL (
             SELECT im.card_payload->>'traceId' AS trace_id FROM ab_im_message im
              WHERE im.tenant_id=t.tenant_id AND im.conversation_id::text=agent_task_metadata_v1(t.input_data)->>'conversationId'
                AND im.client_msg_id='out-' || (agent_task_metadata_v1(t.input_data)->>'turnId') LIMIT 1
          ) m ON TRUE
          LEFT JOIN LATERAL (SELECT r.trace_id, r.status, r.error_message FROM ab_ai_trace r WHERE %s
              ORDER BY r.start_time DESC LIMIT 1) tr ON TRUE
          LEFT JOIN LATERAL (
             SELECT COUNT(*) AS calls, SUM(g.input_tokens) AS input_tokens, SUM(g.output_tokens) AS output_tokens,
                    SUM(g.amount) AS amount, MIN(g.currency) AS currency,
                    CASE WHEN COUNT(*)=0 THEN 'not-recorded'
                         WHEN COUNT(DISTINCT g.currency)<>1 THEN 'mixed-currency'
                         WHEN BOOL_OR(g.amount IS NULL OR g.pricing_version IS NULL OR g.pricing_version='' OR g.pricing_version='unpriced'
                              OR g.pricing_version LIKE '%%cache-incomplete%%' OR COALESCE(g.cache_read_tokens,0)>0
                              OR COALESCE(g.cache_write_tokens,0)>0) THEN 'unknown'
                         ELSE 'known' END AS cost_status
               FROM ab_gen_ai_usage g JOIN ab_ai_trace r ON r.tenant_id=g.tenant_id AND r.trace_id=g.trace_id
              WHERE %s
          ) u ON TRUE
          ORDER BY t.created_at DESC, t.pid DESC
        """.formatted(RELATED, RELATED);

    Map<String,Object> list(Long tenantId, String query, int limit, int offset) {
        String search = query == null ? "" : query.trim();
        if (search.length()>200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Search is too long");
        int take=Math.min(100,Math.max(1,limit)); int start=Math.max(0,offset);
        String predicate=SCOPE+(search.isEmpty()?"":" AND (title ILIKE ? OR assignee_id ILIKE ?) ");
        List<Object> args=new ArrayList<>();args.add(tenantId);
        if(!search.isEmpty()){args.add("%"+search+"%");args.add("%"+search+"%");}
        Long total=jdbc.queryForObject("SELECT COUNT(*) FROM ab_agent_task WHERE"+predicate,Long.class,args.toArray());
        args.add(take);args.add(start);
        var records=jdbc.queryForList("WITH tasks AS (SELECT * FROM ab_agent_task WHERE"+predicate
                +" ORDER BY created_at DESC,pid DESC LIMIT ? OFFSET ?) "+PROJECTION,args.toArray());
        Long unparseable=jdbc.queryForObject("SELECT COUNT(*) FROM ab_agent_task WHERE tenant_id=? AND input_data IS NOT NULL AND agent_task_metadata_v1(input_data) IS NULL",Long.class,tenantId);
        return Map.of("records",records,"total",total==null?0:total,"limit",take,"offset",start,
                "unparseableMetadataCount",unparseable==null?0:unparseable);
    }

    Map<String,Object> detail(Long tenantId,String taskPid) {
        List<Map<String,Object>> rows=jdbc.queryForList("WITH tasks AS (SELECT * FROM ab_agent_task WHERE"+SCOPE+" AND pid=?) "+PROJECTION,tenantId,taskPid);
        if(rows.size()!=1) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Conversation task not found");
        Map<String,Object> task=rows.get(0);
        String turnId=Objects.toString(task.get("turnId"),"");
        String traceId=Objects.toString(task.get("traceId"),"");
        var traces=jdbc.queryForList("SELECT trace_id AS \"traceId\", status, start_time AS \"startedAt\", duration_ms AS \"durationMs\" FROM ab_ai_trace "
                +"WHERE tenant_id=? AND (metadata->>'taskPid'=? OR trace_id=?) ORDER BY start_time DESC LIMIT 101",tenantId,taskPid,traceId);
        var approvals=jdbc.queryForList("SELECT pid, approval_type AS type, approval_status AS status, created_at AS \"createdAt\", approved_at AS \"decidedAt\" "
                +"FROM ab_agent_approval WHERE tenant_id=? AND (task_id=? OR run_id=?) ORDER BY created_at DESC LIMIT 101",tenantId,taskPid,turnId);
        var actions=jdbc.queryForList("SELECT pid, action_status AS status, command_code AS command, target_model AS model, executed_at AS \"executedAt\" "
                +"FROM ab_agent_action WHERE tenant_id=? AND run_id=? ORDER BY executed_at DESC LIMIT 101",tenantId,turnId);
        Map<String,Object> result=new LinkedHashMap<>();result.put("task",task);
        result.put("traces",traces.stream().limit(100).toList());result.put("approvals",approvals.stream().limit(100).toList());result.put("actions",actions.stream().limit(100).toList());
        result.put("truncated",traces.size()>100||approvals.size()>100||actions.size()>100);
        return result;
    }
}
