package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.metrics.MemoryPromotionMetrics;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Session → user consolidation audit wrapper (PR-66 Phase 2, plan §6.5).
 *
 * <p>Wraps {@link AgentMemoryConsolidationService#promoteSessionMemories} so
 * every silent session-to-user transition leaves an audit row in
 * {@code ab_agent_memory_promotion} with {@code status=ACTIVE} and
 * {@code reason_code=session_upgrade}. No review, no shadow window — this
 * crossing stays inside the user scope so the plan (§6.5) explicitly
 * bypasses both. The audit row is informational: it lets the
 * Mission Control "Audit History" tab render a unified provenance list.
 *
 * <p>This class is a <i>retrofit</i> — the underlying
 * {@code AgentMemoryConsolidationService.promoteSessionMemories} semantics
 * are unchanged. We simply capture a before/after snapshot of candidate
 * memory pids and insert audit rows for those whose {@code category}
 * flipped to {@code user} during the call.
 *
 * <p>Idempotency: the audit insert is skipped for any memory whose pid is
 * already present as {@code source_memory_pid} on a row with a
 * non-terminal or already-promoted status — matches the dedup check in
 * {@code MemoryPromotionExtractor}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionMemoryConsolidationAuditRunner {

    private static final String REASON_CODE = MemoryPromotionMetrics.REASON_SESSION_UPGRADE;
    private static final String SOURCE_SCOPE = "session";
    private static final String TARGET_SCOPE = "user";

    private final JdbcTemplate jdbcTemplate;
    private final AgentMemoryConsolidationService consolidationService;
    private final ObjectMapper objectMapper;

    /**
     * Run the underlying consolidation and emit audit rows for each newly
     * promoted memory. Returns the count of audit rows inserted (may be
     * less than the underlying promoted count when dedup skips some).
     */
    @Transactional
    public int consolidateWithAudit(Long tenantId, String agentCode, int importanceThreshold) {
        // 1. Snapshot session-scope candidate pids that will be eligible.
        List<Map<String, Object>> candidates = jdbcTemplate.queryForList(
                "SELECT pid, memory_title, memory_content, importance, category "
                        + "FROM ab_agent_memory "
                        + "WHERE tenant_id = ? AND memory_agent_id = ? "
                        + "  AND category = ? "
                        + "  AND importance >= ? "
                        + "  AND (deleted_flag IS NULL OR deleted_flag = FALSE)",
                tenantId, agentCode,
                AgentMemoryConsolidationService.CATEGORY_SESSION,
                importanceThreshold);
        if (candidates.isEmpty()) {
            return 0;
        }

        // 2. Delegate to the existing consolidation service.
        int promoted = consolidationService.promoteSessionMemories(tenantId, agentCode, importanceThreshold);
        if (promoted <= 0) {
            return 0;
        }

        // 3. Verify: re-read the candidates and collect those that flipped.
        Set<String> pids = new HashSet<>();
        for (Map<String, Object> c : candidates) {
            pids.add((String) c.get("pid"));
        }
        // Guard against empty IN list (already handled by early-return above).
        String inMarkers = String.join(",", java.util.Collections.nCopies(pids.size(), "?"));
        Object[] flippedArgs = new Object[pids.size() + 1];
        flippedArgs[0] = AgentMemoryConsolidationService.CATEGORY_USER;
        int idx = 1;
        for (String pid : pids) {
            flippedArgs[idx++] = pid;
        }
        List<Map<String, Object>> flipped = jdbcTemplate.queryForList(
                "SELECT pid, memory_title, memory_content, importance "
                        + "FROM ab_agent_memory "
                        + "WHERE category = ? "
                        + "  AND pid IN (" + inMarkers + ")",
                flippedArgs);

        int inserted = 0;
        for (Map<String, Object> row : flipped) {
            String pid = (String) row.get("pid");
            if (isAlreadyAudited(pid)) {
                continue;
            }
            insertAuditRow(tenantId, pid,
                    (String) row.get("memory_title"),
                    (String) row.get("memory_content"),
                    row.get("importance") == null
                            ? importanceThreshold : ((Number) row.get("importance")).intValue(),
                    importanceThreshold);
            inserted++;
        }
        if (inserted > 0) {
            log.info("SessionMemoryConsolidationAuditRunner: tenant={} agent={} audit rows={} (promoted={})",
                    tenantId, agentCode, inserted, promoted);
        }
        return inserted;
    }

    private boolean isAlreadyAudited(String memoryPid) {
        Integer hit = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_memory_promotion "
                        + "WHERE source_memory_pid = ? AND reason_code = ?",
                Integer.class, memoryPid, REASON_CODE);
        return hit != null && hit > 0;
    }

    private void insertAuditRow(Long tenantId, String sourceMemoryPid,
                                String title, String content,
                                int importance, int threshold) {
        String pid = UniqueIdGenerator.generate();
        String detailJson;
        String sourcePidsJson;
        try {
            detailJson = objectMapper.writeValueAsString(Map.of(
                    "threshold_exceeded", threshold,
                    "source_memory_pid", sourceMemoryPid));
            // PR-73: use Jackson to build the JSON array instead of raw string
            // concatenation. Safe against future pids containing characters
            // that would need JSON escaping.
            sourcePidsJson = objectMapper.writeValueAsString(List.of(sourceMemoryPid));
        } catch (JsonProcessingException e) {
            // Explicit validation: the JSON build is over an in-memory Map we
            // just constructed — any serialisation failure is a programmer
            // error, not a runtime condition we should hide.
            throw new IllegalStateException("Failed to serialise audit reason_detail", e);
        }

        jdbcTemplate.update(
                "INSERT INTO ab_agent_memory_promotion ("
                        + "pid, tenant_id, source_scope, source_memory_pid, source_memory_pids, "
                        + "target_scope, category, proposed_title, proposed_content, proposed_importance, "
                        + "reason_code, reason_detail, confidence_score, ai_rationale, "
                        + "status, promoted_memory_pid, "
                        + "reviewed_at, shadow_started_at, shadow_ends_at, activated_at, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, "
                        + "?, ?, "
                        + "NOW(), NOW(), NOW(), NOW(), NOW(), NOW())",
                pid, tenantId, SOURCE_SCOPE, sourceMemoryPid,
                sourcePidsJson,
                TARGET_SCOPE, "session_upgrade",
                title, content, importance,
                REASON_CODE, detailJson, 1.00d, null,
                MemoryPromotionApplierImpl.STATUS_ACTIVE, sourceMemoryPid);
    }
}
