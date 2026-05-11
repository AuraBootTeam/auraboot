package com.auraboot.framework.agent.controller;

import com.auraboot.framework.agent.dto.replay.AgentActionItem;
import com.auraboot.framework.agent.dto.replay.AgentBifSummary;
import com.auraboot.framework.agent.dto.replay.AgentApprovalAuditItem;
import com.auraboot.framework.agent.dto.replay.AgentAuthorizationDecisionItem;
import com.auraboot.framework.agent.dto.replay.AgentConversationMessageItem;
import com.auraboot.framework.agent.dto.replay.AgentConversationTurnReplay;
import com.auraboot.framework.agent.dto.replay.AgentInterruptItem;
import com.auraboot.framework.agent.dto.replay.AgentResultContractItem;
import com.auraboot.framework.agent.dto.replay.AgentRunDetail;
import com.auraboot.framework.agent.dto.replay.AgentRunListItem;
import com.auraboot.framework.agent.dto.replay.AgentRunPage;
import com.auraboot.framework.agent.dto.replay.AgentRuntimeAuditTrail;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Replay UI MVP — admin-facing read-only listing of agent runs.
 *
 * <p>Surface for operators to inspect the existing audit trail in
 * {@code ab_agent_run} + {@code ab_agent_action} + {@code ab_agent_interrupt_log}
 * + {@code ab_agent_bif} without having to {@code psql} into the database. This
 * is the OSS counterpart of LangGraph's "Trace UI": all data is already
 * persisted, the controller is purely a projection.
 *
 * <p><b>Authorisation.</b> Lives under {@code /api/admin/**} so the platform
 * {@link com.auraboot.framework.application.security.AdminRoleInterceptor}
 * already enforces tenant-admin role before any handler executes. No per-handler
 * guard is required — see PR-85 design doc §9.2 for the rationale.
 *
 * <p><b>Tenant isolation.</b> The interceptor authenticates the role but does
 * <em>not</em> auto-attach a {@code tenant_id} predicate the way the
 * MyBatis dynamic-table interceptor does for ORM mappers. Because every query
 * here runs through {@link JdbcTemplate} we manually append
 * {@code WHERE tenant_id = ?} (sourced from {@link MetaContext}) on every
 * statement. Cross-tenant leakage is covered by the
 * {@code tenant_isolation_otherTenantRunInvisible} integration test.
 *
 * <p><b>Read-only contract.</b> No write endpoints, no time-travel, no
 * fork-from-step, no replay buttons. Those remain future product
 * enhancements. The controller never mutates {@code ab_agent_*} tables.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/agent-runs")
public class AgentRunController {

    /** Hard cap on page size to keep payload + admin SQL bounded. */
    private static final int MAX_PAGE_SIZE = 200;

    /** Hard cap on returned actions per run — interactive review only. */
    private static final int MAX_ACTIONS = 1000;

    /** Hard cap on returned interrupts per run. */
    private static final int MAX_INTERRUPTS = 200;

    /** Hard cap on returned child runs per run. */
    private static final int MAX_CHILD_RUNS = 200;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public AgentRunController(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // =========================================================================
    // GET / — paginated list
    // =========================================================================

    /**
     * {@code GET /api/admin/agent-runs}
     *
     * @param page          0-based page index, clamped to ≥ 0.
     * @param size          page size, clamped to {@code [1, MAX_PAGE_SIZE]}.
     * @param status        optional {@code run_status} filter (running/succeeded/failed/cancelled).
     * @param agentCode     optional {@code agent_id} filter (exact match).
     * @param parentRunId   optional {@code parent_run_id} filter — pass a parent's pid
     *                      to fetch only its child runs.
     * @param keyword       optional case-insensitive substring match against
     *                      {@code pid} / {@code agent_id} / {@code task_id}.
     */
    @GetMapping
    public ApiResponse<AgentRunPage> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String agentCode,
            @RequestParam(required = false) String parentRunId,
            @RequestParam(required = false) String keyword) {

        Long tenantId = MetaContext.getCurrentTenantId();
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);

        StringBuilder where = new StringBuilder("WHERE r.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);

        if (status != null && !status.isBlank()) {
            where.append(" AND r.run_status = ?");
            args.add(status.trim());
        }
        if (agentCode != null && !agentCode.isBlank()) {
            where.append(" AND r.agent_id = ?");
            args.add(agentCode.trim());
        }
        if (parentRunId != null && !parentRunId.isBlank()) {
            where.append(" AND r.parent_run_id = ?");
            args.add(parentRunId.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim().toLowerCase() + "%";
            where.append(" AND (LOWER(r.pid) LIKE ? OR LOWER(r.agent_id) LIKE ? OR LOWER(r.task_id) LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
        }

        // total count first — cheap, makes the empty-page case trivially observable.
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_run r " + where,
                Long.class, args.toArray());
        long totalCount = total == null ? 0L : total;

        if (totalCount == 0L) {
            return ApiResponse.ok(AgentRunPage.builder()
                    .items(Collections.emptyList())
                    .total(0L)
                    .page(safePage)
                    .size(safeSize)
                    .build());
        }

        // Joined SELECT — LEFT JOIN BIF so runs without grounding still show up.
        // intent_summary uses MIN(intent) to deduplicate when a run has multiple
        // BIF rows (multi-turn grounding) — first-by-min is stable for display.
        String sql = "SELECT r.pid, r.agent_id, r.run_status, r.parent_run_id, " +
                "       r.subtask_origin, r.total_cost, " +
                "       r.child_aggregate_cost, r.child_aggregate_tokens, " +
                "       r.duration_ms, " +
                "       r.created_at, r.completed_at, " +
                "       (SELECT b.intent FROM ab_agent_bif b " +
                "         WHERE b.run_id = r.pid AND b.tenant_id = r.tenant_id " +
                "         ORDER BY b.created_at ASC LIMIT 1) AS intent_summary " +
                "  FROM ab_agent_run r " +
                where +
                " ORDER BY r.created_at DESC " +
                " LIMIT ? OFFSET ?";

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(safeSize);
        pageArgs.add((long) safePage * safeSize);

        List<AgentRunListItem> items = jdbcTemplate.query(sql, RUN_ROW_MAPPER, pageArgs.toArray());

        return ApiResponse.ok(AgentRunPage.builder()
                .items(items)
                .total(totalCount)
                .page(safePage)
                .size(safeSize)
                .build());
    }

    // =========================================================================
    // GET /audit — runtime evidence bundle
    // =========================================================================

    /**
     * {@code GET /api/admin/agent-runs/audit}
     *
     * <p>Read-only incident triage projection over the existing runtime audit
     * tables. The endpoint intentionally does not mutate, retry, or repair any
     * agent state; it only collects evidence by tenant-scoped run/conversation/tool.
     */
    @GetMapping("/audit")
    public ApiResponse<AgentRuntimeAuditTrail> audit(
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) Long conversationId,
            @RequestParam(required = false) String toolName) {
        if ((runId == null || runId.isBlank()) && conversationId == null) {
            return ApiResponse.error(400, "runId_or_conversationId_required");
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        String resolvedRunId = normalizeBlank(runId);
        if (resolvedRunId == null && conversationId != null) {
            resolvedRunId = resolveRunIdByConversation(tenantId, conversationId);
        }
        if (resolvedRunId == null) {
            return ApiResponse.error(404, "agent_run_not_found");
        }

        AgentRunListItem run = loadRun(tenantId, resolvedRunId);
        if (run == null) {
            return ApiResponse.error(404, "agent_run_not_found");
        }

        String normalizedTool = normalizeBlank(toolName);
        List<AgentActionItem> actions = loadActions(tenantId, resolvedRunId).stream()
                .filter(action -> toolMatches(action, normalizedTool))
                .toList();
        List<AgentResultContractItem> resultContracts = buildResultContracts(actions);
        List<AgentAuthorizationDecisionItem> authorizationDecisions =
                loadAuthorizationDecisions(tenantId, resolvedRunId, normalizedTool);
        List<AgentApprovalAuditItem> approvals =
                loadApprovals(tenantId, resolvedRunId, normalizedTool, approvalIds(authorizationDecisions));

        return ApiResponse.ok(AgentRuntimeAuditTrail.builder()
                .runId(resolvedRunId)
                .conversationId(conversationId)
                .toolName(normalizedTool)
                .actions(actions)
                .authorizationDecisions(authorizationDecisions)
                .approvals(approvals)
                .resultContracts(resultContracts)
                .build());
    }

    // =========================================================================
    // GET /{runId} — single-run detail
    // =========================================================================

    /**
     * {@code GET /api/admin/agent-runs/{runId}}
     *
     * <p>Returns the run row plus its action timeline, interrupt log, child
     * runs, and BIF summary. Returns {@code code=404} when the run does not
     * exist in the caller's tenant — cross-tenant ids look identical to
     * not-found, deliberately.
     */
    @GetMapping("/{runId}")
    public ApiResponse<AgentRunDetail> detail(@PathVariable("runId") String runId) {
        if (runId == null || runId.isBlank()) {
            return ApiResponse.error(400, "runId required");
        }
        Long tenantId = MetaContext.getCurrentTenantId();

        AgentRunListItem run = loadRun(tenantId, runId);
        if (run == null) {
            return ApiResponse.error(404, "agent_run_not_found");
        }

        List<AgentActionItem> actions = loadActions(tenantId, runId);
        List<AgentInterruptItem> interrupts = loadInterrupts(tenantId, runId);
        List<AgentRunListItem> children = loadChildRuns(tenantId, runId);
        AgentBifSummary bif = loadBif(tenantId, runId);
        String traceId = loadTraceId(tenantId, runId);
        List<AgentResultContractItem> resultContracts = buildResultContracts(actions);
        AgentConversationTurnReplay conversationTurn =
                loadConversationTurn(tenantId, runId, resultContracts);

        AgentRunDetail detail = AgentRunDetail.builder()
                .run(run)
                .actions(actions)
                .interruptLog(interrupts)
                .childRuns(children)
                .bif(bif)
                .traceId(traceId)
                .conversationTurn(conversationTurn)
                .resultContracts(resultContracts)
                .build();
        return ApiResponse.ok(detail);
    }

    // =========================================================================
    // Private loaders
    // =========================================================================

    private String resolveRunIdByConversation(Long tenantId, Long conversationId) {
        String sql = "SELECT r.pid AS run_id, r.metadata, t.input_data " +
                "  FROM ab_agent_run r " +
                "  LEFT JOIN ab_agent_task t ON t.tenant_id = r.tenant_id AND t.pid = r.task_id " +
                " WHERE r.tenant_id = ? " +
                " ORDER BY r.created_at DESC " +
                " LIMIT 500";
        List<RunConversationSeed> rows = jdbcTemplate.query(sql, (rs, rowNum) -> new RunConversationSeed(
                rs.getString("run_id"),
                rs.getString("metadata"),
                rs.getString("input_data")), tenantId);
        for (RunConversationSeed row : rows) {
            JsonNode metadata = parseJsonObject(row.metadata());
            JsonNode input = parseJsonObject(row.inputData());
            Long rowConversationId = firstLong(input, "conversationId", metadata, "conversationId");
            if (conversationId.equals(rowConversationId)) {
                return row.runId();
            }
        }
        return null;
    }

    private AgentRunListItem loadRun(Long tenantId, String runId) {
        String sql = "SELECT r.pid, r.agent_id, r.run_status, r.parent_run_id, " +
                "       r.subtask_origin, r.total_cost, " +
                "       r.child_aggregate_cost, r.child_aggregate_tokens, " +
                "       r.duration_ms, " +
                "       r.created_at, r.completed_at, " +
                "       (SELECT b.intent FROM ab_agent_bif b " +
                "         WHERE b.run_id = r.pid AND b.tenant_id = r.tenant_id " +
                "         ORDER BY b.created_at ASC LIMIT 1) AS intent_summary " +
                "  FROM ab_agent_run r " +
                " WHERE r.tenant_id = ? AND r.pid = ? " +
                " LIMIT 1";
        List<AgentRunListItem> rows = jdbcTemplate.query(sql, RUN_ROW_MAPPER, tenantId, runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<AgentActionItem> loadActions(Long tenantId, String runId) {
        String sql = "SELECT pid, step_index, tool_call_index, action_code, action_type, " +
                "       intent_summary, target_model, target_record_id, " +
                "       before_snapshot::text AS before_snapshot, " +
                "       after_snapshot::text  AS after_snapshot, " +
                "       field_changes::text   AS field_changes, " +
                "       command_code, command_result, " +
                "       risk_level, estimated_risk, risk_deviation, reversal_mode, " +
                "       action_status, error_message, cost_usd, token_usage, " +
                "       fidelity, skill_code, parallel_group_id, parallel_index, executed_at " +
                "  FROM ab_agent_action " +
                " WHERE tenant_id = ? AND run_id = ? " +
                " ORDER BY executed_at ASC, step_index ASC NULLS LAST, " +
                "          parallel_index ASC NULLS LAST " +
                " LIMIT ?";
        return jdbcTemplate.query(sql, ACTION_ROW_MAPPER, tenantId, runId, MAX_ACTIONS);
    }

    private List<AgentAuthorizationDecisionItem> loadAuthorizationDecisions(
            Long tenantId, String runId, String toolName) {
        StringBuilder sql = new StringBuilder("SELECT pid, run_id, decision_kind, tool_ref, skill_code, " +
                "       blast_radius, requested_effects::text AS requested_effects, " +
                "       granted_effects::text AS granted_effects, " +
                "       rejected_effects::text AS rejected_effects, " +
                "       require_approval, approval_id, policy_id, policy_version, " +
                "       decision_reason, decision_at " +
                "  FROM ab_agent_authorization_decision " +
                " WHERE tenant_id = ? AND run_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(runId);
        if (toolName != null) {
            sql.append(" AND (tool_ref = ? OR skill_code = ?)");
            args.add(toolName);
            args.add(toolName);
        }
        sql.append(" ORDER BY decision_at ASC");
        return jdbcTemplate.query(sql.toString(), AUTHORIZATION_DECISION_ROW_MAPPER, args.toArray());
    }

    private List<AgentApprovalAuditItem> loadApprovals(
            Long tenantId, String runId, String toolName, List<String> approvalIds) {
        StringBuilder sql = new StringBuilder("SELECT pid, run_id, approval_type, approval_title, " +
                "       approval_description, request_data, approval_status, policy_id, " +
                "       approver_id, created_at, approved_at " +
                "  FROM ab_agent_approval " +
                " WHERE tenant_id = ? AND run_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(runId);
        boolean hasToolFilter = toolName != null;
        boolean hasLinkedApprovals = approvalIds != null && !approvalIds.isEmpty();
        if (hasToolFilter || hasLinkedApprovals) {
            sql.append(" AND (");
        }
        if (hasToolFilter) {
            String like = "%" + toolName.toLowerCase() + "%";
            sql.append("(LOWER(COALESCE(approval_title, '')) LIKE ? " +
                    "OR LOWER(COALESCE(approval_description, '')) LIKE ? " +
                    "OR LOWER(COALESCE(request_data, '')) LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (hasLinkedApprovals) {
            if (hasToolFilter) {
                sql.append(" OR ");
            }
            sql.append("pid IN (");
            for (int i = 0; i < approvalIds.size(); i++) {
                if (i > 0) {
                    sql.append(", ");
                }
                sql.append("?");
                args.add(approvalIds.get(i));
            }
            sql.append(")");
        }
        if (hasToolFilter || hasLinkedApprovals) {
            sql.append(")");
        }
        sql.append(" ORDER BY created_at ASC");
        return jdbcTemplate.query(sql.toString(), APPROVAL_AUDIT_ROW_MAPPER, args.toArray());
    }

    private List<AgentInterruptItem> loadInterrupts(Long tenantId, String runId) {
        // Surface both directions: interrupts that hit this run AND interrupts
        // whose subtask spawn produced this run (so child runs see why they
        // exist when an operator drills in from the spawn tree).
        String sql = "SELECT pid, session_id, active_run_id, new_message_excerpt, " +
                "       sub_policy, classifier_tier, confidence, reason, action_taken, " +
                "       subtask_run_id, created_at " +
                "  FROM ab_agent_interrupt_log " +
                " WHERE tenant_id = ? " +
                "   AND (active_run_id = ? OR subtask_run_id = ?) " +
                " ORDER BY created_at ASC " +
                " LIMIT ?";
        return jdbcTemplate.query(sql, INTERRUPT_ROW_MAPPER,
                tenantId, runId, runId, MAX_INTERRUPTS);
    }

    private List<AgentRunListItem> loadChildRuns(Long tenantId, String runId) {
        String sql = "SELECT r.pid, r.agent_id, r.run_status, r.parent_run_id, " +
                "       r.subtask_origin, r.total_cost, " +
                "       r.child_aggregate_cost, r.child_aggregate_tokens, " +
                "       r.duration_ms, " +
                "       r.created_at, r.completed_at, " +
                "       (SELECT b.intent FROM ab_agent_bif b " +
                "         WHERE b.run_id = r.pid AND b.tenant_id = r.tenant_id " +
                "         ORDER BY b.created_at ASC LIMIT 1) AS intent_summary " +
                "  FROM ab_agent_run r " +
                " WHERE r.tenant_id = ? AND r.parent_run_id = ? " +
                " ORDER BY r.created_at ASC " +
                " LIMIT ?";
        return jdbcTemplate.query(sql, RUN_ROW_MAPPER, tenantId, runId, MAX_CHILD_RUNS);
    }

    private AgentBifSummary loadBif(Long tenantId, String runId) {
        String sql = "SELECT pid, intent, primary_object, " +
                "       confidence::text AS confidence, dispatched_skill, channel " +
                "  FROM ab_agent_bif " +
                " WHERE tenant_id = ? AND run_id = ? " +
                " ORDER BY created_at ASC " +
                " LIMIT 1";
        List<AgentBifSummary> rows = jdbcTemplate.query(sql, BIF_ROW_MAPPER, tenantId, runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String loadTraceId(Long tenantId, String runId) {
        String metadataTraceId = loadTraceIdFromRunMetadata(tenantId, runId);
        if (metadataTraceId != null && traceExists(tenantId, metadataTraceId)) {
            return metadataTraceId;
        }

        String sql = "SELECT trace_id " +
                "  FROM ab_ai_trace " +
                " WHERE tenant_id = ? AND session_id = ? " +
                " ORDER BY start_time DESC " +
                " LIMIT 1";
        List<String> rows = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("trace_id"), tenantId, runId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String loadTraceIdFromRunMetadata(Long tenantId, String runId) {
        String sql = "SELECT metadata FROM ab_agent_run WHERE tenant_id = ? AND pid = ? LIMIT 1";
        List<String> rows = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("metadata"), tenantId, runId);
        if (rows.isEmpty()) {
            return null;
        }
        String metadata = rows.get(0);
        if (metadata == null || metadata.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(metadata);
            JsonNode traceNode = node.get("traceId");
            if (traceNode == null || !traceNode.isTextual()) {
                return null;
            }
            String traceId = traceNode.asText();
            return traceId == null || traceId.isBlank() ? null : traceId.trim();
        } catch (Exception e) {
            log.debug("Ignoring malformed agent run metadata for run {}: {}", runId, e.getMessage());
            return null;
        }
    }

    private boolean traceExists(Long tenantId, String traceId) {
        String sql = "SELECT COUNT(*) FROM ab_ai_trace WHERE tenant_id = ? AND trace_id = ?";
        Long count = jdbcTemplate.queryForObject(sql, Long.class, tenantId, traceId);
        return count != null && count > 0;
    }

    private AgentConversationTurnReplay loadConversationTurn(
            Long tenantId, String runId, List<AgentResultContractItem> resultContracts) {
        String sql = "SELECT r.pid AS run_id, r.task_id, r.run_status, r.started_at, r.completed_at, " +
                "       r.metadata, t.input_data, t.output_data, t.description " +
                "  FROM ab_agent_run r " +
                "  LEFT JOIN ab_agent_task t ON t.tenant_id = r.tenant_id AND t.pid = r.task_id " +
                " WHERE r.tenant_id = ? AND r.pid = ? " +
                " LIMIT 1";
        List<TurnSeed> rows = jdbcTemplate.query(sql, (rs, rowNum) -> new TurnSeed(
                rs.getString("run_id"),
                rs.getString("task_id"),
                rs.getString("run_status"),
                rs.getTimestamp("started_at"),
                rs.getTimestamp("completed_at"),
                rs.getString("metadata"),
                rs.getString("input_data"),
                rs.getString("output_data"),
                rs.getString("description")), tenantId, runId);
        if (rows.isEmpty()) {
            return null;
        }

        TurnSeed seed = rows.get(0);
        JsonNode metadata = parseJsonObject(seed.metadata());
        JsonNode input = parseJsonObject(seed.inputData());
        JsonNode output = parseJsonObject(seed.outputData());

        String turnId = firstText(input, "turnId", metadata, "turnId");
        Long conversationId = firstLong(input, "conversationId", metadata, "conversationId");
        Long inboundMessageId = firstLong(input, "inboundMessageId", metadata, "inboundMessageId");
        String triageBucket = firstText(input, "triageBucket", metadata, "triageBucket");
        String userMessage = firstText(input, "userMessage", metadata, "userMessage");
        boolean hasTurnIdentity = (turnId != null && !turnId.isBlank())
                || conversationId != null
                || inboundMessageId != null;
        if (!hasTurnIdentity) {
            return null;
        }
        if ((userMessage == null || userMessage.isBlank()) && seed.description() != null) {
            userMessage = seed.description();
        }

        List<AgentConversationMessageItem> messages =
                loadConversationMessages(tenantId, conversationId, inboundMessageId, turnId);
        AgentConversationMessageItem inbound = messages.stream()
                .filter(m -> inboundMessageId != null && inboundMessageId.equals(m.getMessageId()))
                .findFirst()
                .orElse(null);
        AgentConversationMessageItem outbound = messages.stream()
                .filter(m -> turnId != null && ("out-" + turnId).equals(m.getClientMsgId()))
                .findFirst()
                .orElse(null);

        if (triageBucket == null && inbound != null) {
            triageBucket = inbound.getTriageBucket();
        }
        String triageConfidence = inbound != null ? inbound.getTriageConfidence() : null;
        String triageReasonCodes = inbound != null ? inbound.getTriageReasonCodes() : null;
        String finalResponse = outbound != null ? outbound.getContent() : firstText(output, "finalResponse", metadata, "finalResponse");

        List<String> resultContractIds = resultContracts == null
                ? List.of()
                : resultContracts.stream().map(AgentResultContractItem::getContractId).toList();

        return AgentConversationTurnReplay.builder()
                .runId(seed.runId())
                .taskPid(seed.taskId())
                .turnId(turnId)
                .conversationId(conversationId)
                .inboundMessageId(inboundMessageId)
                .outboundMessageId(outbound != null ? outbound.getMessageId() : null)
                .triageBucket(triageBucket)
                .triageConfidence(triageConfidence)
                .triageReasonCodes(triageReasonCodes)
                .userMessage(userMessage)
                .finalResponse(finalResponse)
                .outcomeStatus(seed.runStatus())
                .startedAt(seed.startedAt() == null ? null : seed.startedAt().toInstant())
                .completedAt(seed.completedAt() == null ? null : seed.completedAt().toInstant())
                .messages(messages)
                .resultContractIds(resultContractIds)
                .build();
    }

    private List<AgentConversationMessageItem> loadConversationMessages(
            Long tenantId, Long conversationId, Long inboundMessageId, String turnId) {
        if (conversationId == null) {
            return List.of();
        }

        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(conversationId);
        StringBuilder predicates = new StringBuilder();
        if (inboundMessageId != null) {
            predicates.append("m.id = ?");
            args.add(inboundMessageId);
        }
        if (turnId != null && !turnId.isBlank()) {
            if (!predicates.isEmpty()) {
                predicates.append(" OR ");
            }
            predicates.append("m.client_msg_id = ?");
            args.add("out-" + turnId);
        }
        if (predicates.isEmpty()) {
            return List.of();
        }

        String sql = "SELECT m.id, m.conversation_id, m.sender_type, m.sender_id, m.seq, " +
                "       m.message_type, m.content, m.card_payload::text AS card_payload, " +
                "       m.client_msg_id, m.triage_bucket, m.triage_confidence::text AS triage_confidence, " +
                "       m.triage_reason_codes::text AS triage_reason_codes, " +
                "       m.thinking_content, m.thinking_signature, m.created_at " +
                "  FROM ab_im_message m " +
                " WHERE m.tenant_id = ? AND m.conversation_id = ? " +
                "   AND (" + predicates + ") " +
                " ORDER BY m.seq ASC, m.id ASC";

        return jdbcTemplate.query(sql, CONVERSATION_MESSAGE_ROW_MAPPER, args.toArray());
    }

    private List<AgentResultContractItem> buildResultContracts(List<AgentActionItem> actions) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        return actions.stream()
                .map(this::buildResultContract)
                .toList();
    }

    private AgentResultContractItem buildResultContract(AgentActionItem action) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("actionPid", action.getPid());
        data.put("actionCode", action.getActionCode());
        data.put("actionType", action.getActionType());
        data.put("targetModel", action.getTargetModel());
        data.put("targetRecordId", action.getTargetRecordId());
        data.put("commandCode", action.getCommandCode());
        data.put("commandResult", action.getCommandResult());
        data.put("riskLevel", action.getRiskLevel());
        data.put("estimatedRisk", action.getEstimatedRisk());
        data.put("fidelity", action.getFidelity());
        if (action.getBeforeSnapshot() != null) {
            data.put("beforeSnapshot", parseJsonValueOrRaw(action.getBeforeSnapshot()));
        }
        if (action.getAfterSnapshot() != null) {
            data.put("afterSnapshot", parseJsonValueOrRaw(action.getAfterSnapshot()));
        }
        if (action.getFieldChanges() != null) {
            data.put("fieldChanges", parseJsonValueOrRaw(action.getFieldChanges()));
        }

        boolean readOnly = "read".equalsIgnoreCase(action.getActionType());
        String status = normalizeResultContractStatus(action.getActionStatus(), action.getCommandResult());
        String renderHint = (action.getBeforeSnapshot() != null || action.getAfterSnapshot() != null
                || action.getFieldChanges() != null) ? "card" : "summary";
        String outputType = readOnly ? "structured_result"
                : ("failed".equals(status) ? "text" : "action_proposal");
        String skillCode = firstNonBlank(action.getSkillCode(), action.getCommandCode(), action.getActionCode());

        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("outputType", outputType);
        contract.put("renderHint", renderHint);
        contract.put("actionability", readOnly ? "read_only" : "execute");
        contract.put("data", data);
        contract.put("textSummary", buildContractSummary(action, status));
        contract.put("skillCode", skillCode);
        contract.put("durationMs", 0L);
        contract.put("status", status);

        return AgentResultContractItem.builder()
                .contractId(contractIdForAction(action.getPid()))
                .actionPid(action.getPid())
                .source("ab_agent_action")
                .contract(contract)
                .emittedAt(action.getExecutedAt())
                .build();
    }

    private String buildContractSummary(AgentActionItem action, String status) {
        if (action.getIntentSummary() != null && !action.getIntentSummary().isBlank()) {
            return action.getIntentSummary();
        }
        String code = firstNonBlank(action.getCommandCode(), action.getActionCode(), "action");
        return code + " " + status;
    }

    private String normalizeResultContractStatus(String actionStatus, String commandResult) {
        String raw = firstNonBlank(commandResult, actionStatus, "unknown").toLowerCase();
        if (raw.equals("success") || raw.equals("succeeded")) {
            return "success";
        }
        if (raw.equals("failed") || raw.equals("error")) {
            return "failed";
        }
        if (raw.equals("partial_success")) {
            return "partial_success";
        }
        return "unknown";
    }

    private JsonNode parseJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            log.debug("Ignoring malformed replay JSON object: {}", e.getMessage());
            return null;
        }
    }

    private Object parseJsonValueOrRaw(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        try {
            return objectMapper.convertValue(objectMapper.readTree(raw), Object.class);
        } catch (Exception e) {
            return raw;
        }
    }

    private static String firstText(JsonNode first, String firstField, JsonNode second, String secondField) {
        String v = text(first, firstField);
        return v != null ? v : text(second, secondField);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || field == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            String s = value.asText();
            return s == null || s.isBlank() ? null : s.trim();
        }
        if (value.isNumber() || value.isBoolean()) {
            return value.asText();
        }
        return null;
    }

    private static Long firstLong(JsonNode first, String firstField, JsonNode second, String secondField) {
        Long v = longValue(first, firstField);
        return v != null ? v : longValue(second, secondField);
    }

    private static Long longValue(JsonNode node, String field) {
        if (node == null || field == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isIntegralNumber()) {
            return value.asLong();
        }
        if (value.isTextual()) {
            try {
                return Long.parseLong(value.asText());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String normalizeBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean toolMatches(AgentActionItem action, String toolName) {
        if (toolName == null) {
            return true;
        }
        return equalsTrimmed(toolName, action.getActionCode())
                || equalsTrimmed(toolName, action.getCommandCode())
                || equalsTrimmed(toolName, action.getSkillCode())
                || equalsTrimmed(toolName, action.getResultContractId());
    }

    private static boolean equalsTrimmed(String expected, String actual) {
        return expected != null && actual != null && expected.equals(actual.trim());
    }

    private static List<String> approvalIds(List<AgentAuthorizationDecisionItem> decisions) {
        if (decisions == null || decisions.isEmpty()) {
            return List.of();
        }
        return decisions.stream()
                .map(AgentAuthorizationDecisionItem::getApprovalId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
    }

    private static String contractIdForAction(String actionPid) {
        return actionPid == null ? null : "rc-" + actionPid;
    }

    // =========================================================================
    // RowMappers
    // =========================================================================

    private static final RowMapper<AgentRunListItem> RUN_ROW_MAPPER = (rs, rowNum) -> {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp completedAt = rs.getTimestamp("completed_at");

        // duration_ms preferred (set explicitly when run terminates); when it's
        // NULL but completed_at is set, derive from the timestamp delta.
        // Cast to Number (not Long) so the same code works for both BIGINT and
        // INTEGER columns — historic dev DBs have schema drift where
        // duration_ms was created as INTEGER and PG JDBC returns Integer there,
        // which would otherwise CCE on a hard (Long) cast.
        Number storedDurationNum = (Number) rs.getObject("duration_ms");
        long durationMs;
        if (storedDurationNum != null) {
            durationMs = storedDurationNum.longValue();
        } else if (completedAt != null && createdAt != null) {
            durationMs = Duration.between(createdAt.toInstant(), completedAt.toInstant()).toMillis();
        } else {
            durationMs = 0L;
        }

        return AgentRunListItem.builder()
                .runId(rs.getString("pid"))
                .agentCode(rs.getString("agent_id"))
                .runStatus(rs.getString("run_status"))
                .parentRunId(rs.getString("parent_run_id"))
                .subtaskOrigin(rs.getString("subtask_origin"))
                .costUsd(getBigDecimal(rs, "total_cost"))
                .childAggregateCostUsd(getBigDecimal(rs, "child_aggregate_cost"))
                .childAggregateTokens(rs.getObject("child_aggregate_tokens") == null
                        ? 0L : ((Number) rs.getObject("child_aggregate_tokens")).longValue())
                .durationMs(durationMs)
                .createdAt(createdAt == null ? null : createdAt.toInstant())
                .completedAt(completedAt == null ? null : completedAt.toInstant())
                .intentSummary(rs.getString("intent_summary"))
                .build();
    };

    private static final RowMapper<AgentActionItem> ACTION_ROW_MAPPER = (rs, rowNum) -> {
        Timestamp executedAt = rs.getTimestamp("executed_at");
        Boolean riskDeviation = (Boolean) rs.getObject("risk_deviation");
        String pid = rs.getString("pid");
        return AgentActionItem.builder()
                .pid(pid)
                .resultContractId(contractIdForAction(pid))
                .stepIndex(getInteger(rs, "step_index"))
                .toolCallIndex(getInteger(rs, "tool_call_index"))
                .actionCode(rs.getString("action_code"))
                .actionType(rs.getString("action_type"))
                .intentSummary(rs.getString("intent_summary"))
                .targetModel(rs.getString("target_model"))
                .targetRecordId(rs.getString("target_record_id"))
                .beforeSnapshot(rs.getString("before_snapshot"))
                .afterSnapshot(rs.getString("after_snapshot"))
                .fieldChanges(rs.getString("field_changes"))
                .commandCode(rs.getString("command_code"))
                .commandResult(rs.getString("command_result"))
                .riskLevel(rs.getString("risk_level"))
                .estimatedRisk(rs.getString("estimated_risk"))
                .riskDeviation(riskDeviation)
                .reversalMode(rs.getString("reversal_mode"))
                .actionStatus(rs.getString("action_status"))
                .errorMessage(rs.getString("error_message"))
                .costUsd(getBigDecimal(rs, "cost_usd"))
                .tokenUsage(getInteger(rs, "token_usage"))
                .fidelity(rs.getString("fidelity"))
                .skillCode(rs.getString("skill_code"))
                .parallelGroupId(rs.getString("parallel_group_id"))
                .parallelIndex(getInteger(rs, "parallel_index"))
                .executedAt(executedAt == null ? null : executedAt.toInstant())
                .build();
    };

    private static final RowMapper<AgentConversationMessageItem> CONVERSATION_MESSAGE_ROW_MAPPER = (rs, rowNum) -> {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return AgentConversationMessageItem.builder()
                .messageId(rs.getLong("id"))
                .conversationId(rs.getLong("conversation_id"))
                .senderType(rs.getString("sender_type"))
                .senderId(rs.getLong("sender_id"))
                .seq(rs.getLong("seq"))
                .messageType(rs.getString("message_type"))
                .content(rs.getString("content"))
                .cardPayload(rs.getString("card_payload"))
                .clientMsgId(rs.getString("client_msg_id"))
                .triageBucket(rs.getString("triage_bucket"))
                .triageConfidence(rs.getString("triage_confidence"))
                .triageReasonCodes(rs.getString("triage_reason_codes"))
                .thinkingContent(rs.getString("thinking_content"))
                .thinkingSignature(rs.getString("thinking_signature"))
                .createdAt(createdAt == null ? null : createdAt.toInstant())
                .build();
    };

    private static final RowMapper<AgentAuthorizationDecisionItem> AUTHORIZATION_DECISION_ROW_MAPPER = (rs, rowNum) -> {
        Timestamp decisionAt = rs.getTimestamp("decision_at");
        return AgentAuthorizationDecisionItem.builder()
                .pid(rs.getString("pid"))
                .runId(rs.getString("run_id"))
                .decisionKind(rs.getString("decision_kind"))
                .toolRef(rs.getString("tool_ref"))
                .skillCode(rs.getString("skill_code"))
                .blastRadius(rs.getString("blast_radius"))
                .requestedEffects(rs.getString("requested_effects"))
                .grantedEffects(rs.getString("granted_effects"))
                .rejectedEffects(rs.getString("rejected_effects"))
                .requireApproval(rs.getBoolean("require_approval"))
                .approvalId(rs.getString("approval_id"))
                .policyId(rs.getString("policy_id"))
                .policyVersion(getInteger(rs, "policy_version"))
                .decisionReason(rs.getString("decision_reason"))
                .decisionAt(decisionAt == null ? null : decisionAt.toInstant())
                .build();
    };

    private static final RowMapper<AgentApprovalAuditItem> APPROVAL_AUDIT_ROW_MAPPER = (rs, rowNum) -> {
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp approvedAt = rs.getTimestamp("approved_at");
        return AgentApprovalAuditItem.builder()
                .pid(rs.getString("pid"))
                .runId(rs.getString("run_id"))
                .approvalType(rs.getString("approval_type"))
                .approvalTitle(rs.getString("approval_title"))
                .approvalDescription(rs.getString("approval_description"))
                .requestData(rs.getString("request_data"))
                .approvalStatus(rs.getString("approval_status"))
                .policyId(rs.getString("policy_id"))
                .approverId(getLong(rs, "approver_id"))
                .createdAt(createdAt == null ? null : createdAt.toInstant())
                .approvedAt(approvedAt == null ? null : approvedAt.toInstant())
                .build();
    };

    private static final RowMapper<AgentInterruptItem> INTERRUPT_ROW_MAPPER = (rs, rowNum) -> {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return AgentInterruptItem.builder()
                .pid(rs.getString("pid"))
                .sessionId(rs.getString("session_id"))
                .activeRunId(rs.getString("active_run_id"))
                .newMessageExcerpt(rs.getString("new_message_excerpt"))
                .subPolicy(rs.getString("sub_policy"))
                .classifierTier(rs.getString("classifier_tier"))
                .confidence(getBigDecimal(rs, "confidence"))
                .reason(rs.getString("reason"))
                .actionTaken(rs.getString("action_taken"))
                .subtaskRunId(rs.getString("subtask_run_id"))
                .createdAt(createdAt == null ? null : createdAt.toInstant())
                .build();
    };

    private static final RowMapper<AgentBifSummary> BIF_ROW_MAPPER = (rs, rowNum) ->
            AgentBifSummary.builder()
                    .pid(rs.getString("pid"))
                    .intent(rs.getString("intent"))
                    .primaryObject(rs.getString("primary_object"))
                    .confidence(rs.getString("confidence"))
                    .dispatchedSkill(rs.getString("dispatched_skill"))
                    .channel(rs.getString("channel"))
                    .build();

    private static Integer getInteger(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static BigDecimal getBigDecimal(ResultSet rs, String col) throws SQLException {
        BigDecimal v = rs.getBigDecimal(col);
        return rs.wasNull() ? null : v;
    }

    private static Long getLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private record RunConversationSeed(String runId, String metadata, String inputData) {}

    private record TurnSeed(String runId, String taskId, String runStatus,
                            Timestamp startedAt, Timestamp completedAt,
                            String metadata, String inputData,
                            String outputData, String description) {}
}
