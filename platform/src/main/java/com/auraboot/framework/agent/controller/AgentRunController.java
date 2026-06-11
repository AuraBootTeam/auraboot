package com.auraboot.framework.agent.controller;

import com.auraboot.framework.agent.dto.replay.AgentActionItem;
import com.auraboot.framework.agent.dto.replay.AgentBifSummary;
import com.auraboot.framework.agent.dto.replay.AgentConversationMessageItem;
import com.auraboot.framework.agent.dto.replay.AgentConversationTurnReplay;
import com.auraboot.framework.agent.dto.replay.AgentInterruptItem;
import com.auraboot.framework.agent.dto.replay.AgentResultContractItem;
import com.auraboot.framework.agent.dto.replay.AgentRunDetail;
import com.auraboot.framework.agent.dto.replay.AgentRunListItem;
import com.auraboot.framework.agent.dto.replay.AgentRunPage;
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

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;

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
 *
 * <p>This controller serves the run list + run detail endpoints; the sibling
 * {@link AgentRunAuditController} ({@code /audit}) and
 * {@link AgentRunOpsController} ({@code /runtime-ops}) controllers carry the
 * remaining endpoints of the same {@code /api/admin/agent-runs} surface, with
 * shared loaders in {@link AgentRunQuerySupport}.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/agent-runs")
@RequirePermission(MetaPermission.ACP_AGENT_RUN_ADMIN)
public class AgentRunController {

    /** Hard cap on page size to keep payload + admin SQL bounded. */
    private static final int MAX_PAGE_SIZE = 200;

    /** Hard cap on returned interrupts per run. */
    private static final int MAX_INTERRUPTS = 200;

    /** Hard cap on returned child runs per run. */
    private static final int MAX_CHILD_RUNS = 200;

    /** Hard cap on returned messages per reconstructed conversation turn. */
    private static final int MAX_CONVERSATION_MESSAGES = 50;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AgentRunQuerySupport querySupport;

    @Autowired
    public AgentRunController(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                              AgentRunQuerySupport querySupport) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.querySupport = querySupport;
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

        List<AgentRunListItem> items = jdbcTemplate.query(sql, AgentRunQuerySupport.RUN_ROW_MAPPER, pageArgs.toArray());

        return ApiResponse.ok(AgentRunPage.builder()
                .items(items)
                .total(totalCount)
                .page(safePage)
                .size(safeSize)
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

        AgentRunListItem run = querySupport.loadRun(tenantId, runId);
        if (run == null) {
            return ApiResponse.error(404, "agent_run_not_found");
        }

        List<AgentActionItem> actions = querySupport.loadActions(tenantId, runId);
        List<AgentInterruptItem> interrupts = loadInterrupts(tenantId, runId);
        List<AgentRunListItem> children = loadChildRuns(tenantId, runId);
        AgentBifSummary bif = loadBif(tenantId, runId);
        String traceId = loadTraceId(tenantId, runId);
        List<AgentResultContractItem> resultContracts = querySupport.buildResultContracts(actions);
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
        return jdbcTemplate.query(sql, AgentRunQuerySupport.RUN_ROW_MAPPER, tenantId, runId, MAX_CHILD_RUNS);
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
            log.debug("Ignoring malformed agent run metadata: errorType={}", e.getClass().getSimpleName());
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
        JsonNode metadata = querySupport.parseJsonObject(seed.metadata());
        JsonNode input = querySupport.parseJsonObject(seed.inputData());
        JsonNode output = querySupport.parseJsonObject(seed.outputData());

        String turnId = AgentRunQuerySupport.firstText(input, "turnId", metadata, "turnId");
        Long conversationId = AgentRunQuerySupport.firstLong(input, "conversationId", metadata, "conversationId");
        Long inboundMessageId = AgentRunQuerySupport.firstLong(input, "inboundMessageId", metadata, "inboundMessageId");
        String triageBucket = AgentRunQuerySupport.firstText(input, "triageBucket", metadata, "triageBucket");
        String userMessage = AgentRunQuerySupport.firstText(input, "userMessage", metadata, "userMessage");
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
        String finalResponse = outbound != null ? outbound.getContent()
                : AgentRunQuerySupport.firstText(output, "finalResponse", metadata, "finalResponse");

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

        String anchorSql = "SELECT m.id, m.conversation_id, m.sender_type, m.sender_id, m.seq, " +
                "       m.message_type, m.content, m.card_payload::text AS card_payload, " +
                "       m.client_msg_id, m.triage_bucket, m.triage_confidence::text AS triage_confidence, " +
                "       m.triage_reason_codes::text AS triage_reason_codes, " +
                "       m.thinking_content, m.thinking_signature, m.created_at " +
                "  FROM ab_im_message m " +
                " WHERE m.tenant_id = ? AND m.conversation_id = ? " +
                "   AND (" + predicates + ") " +
                " ORDER BY m.seq ASC, m.id ASC";

        List<AgentConversationMessageItem> anchors =
                jdbcTemplate.query(anchorSql, CONVERSATION_MESSAGE_ROW_MAPPER, args.toArray());
        if (anchors.isEmpty()) {
            return List.of();
        }

        Long minSeq = null;
        Long maxSeq = null;
        for (AgentConversationMessageItem message : anchors) {
            Long seq = message.getSeq();
            if (seq == null) {
                continue;
            }
            minSeq = minSeq == null ? seq : Math.min(minSeq, seq);
            maxSeq = maxSeq == null ? seq : Math.max(maxSeq, seq);
        }
        if (minSeq == null || maxSeq == null || minSeq.equals(maxSeq)) {
            return anchors;
        }

        String rangeSql = "SELECT m.id, m.conversation_id, m.sender_type, m.sender_id, m.seq, " +
                "       m.message_type, m.content, m.card_payload::text AS card_payload, " +
                "       m.client_msg_id, m.triage_bucket, m.triage_confidence::text AS triage_confidence, " +
                "       m.triage_reason_codes::text AS triage_reason_codes, " +
                "       m.thinking_content, m.thinking_signature, m.created_at " +
                "  FROM ab_im_message m " +
                " WHERE m.tenant_id = ? AND m.conversation_id = ? " +
                "   AND m.seq BETWEEN ? AND ? " +
                " ORDER BY m.seq ASC, m.id ASC " +
                " LIMIT ?";
        return jdbcTemplate.query(rangeSql, CONVERSATION_MESSAGE_ROW_MAPPER,
                tenantId, conversationId, minSeq, maxSeq, MAX_CONVERSATION_MESSAGES);
    }

    // =========================================================================
    // RowMappers
    // =========================================================================

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

    private static final RowMapper<AgentInterruptItem> INTERRUPT_ROW_MAPPER = (rs, rowNum) -> {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return AgentInterruptItem.builder()
                .pid(rs.getString("pid"))
                .sessionId(rs.getString("session_id"))
                .activeRunId(rs.getString("active_run_id"))
                .newMessageExcerpt(rs.getString("new_message_excerpt"))
                .subPolicy(rs.getString("sub_policy"))
                .classifierTier(rs.getString("classifier_tier"))
                .confidence(AgentRunQuerySupport.getBigDecimal(rs, "confidence"))
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

    private record TurnSeed(String runId, String taskId, String runStatus,
                            Timestamp startedAt, Timestamp completedAt,
                            String metadata, String inputData,
                            String outputData, String description) {}
}
