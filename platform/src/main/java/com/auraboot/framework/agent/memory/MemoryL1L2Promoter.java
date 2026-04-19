package com.auraboot.framework.agent.memory;

import com.auraboot.framework.agent.metrics.MemoryL1L2PromotionMetrics;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Phase 2 promoter: consumes {@link SessionEndedEvent} and promotes qualifying
 * L1 memories ({@code category='session'}) to L2 ({@code category='user'}) per
 * design {@code docs/plans/2026-04/2026-04-19-memory-l1-l2-promotion-design.md §4.1 / §4.3 / §6}.
 *
 * <p>Pipeline per candidate:
 * <ol>
 *   <li>Load L1 rows for {@code (tenant_id, source_run_id)} with
 *       {@code category='session'} and {@code importance >= 6}.</li>
 *   <li>Compute {@code maxCosineToL2} via pgvector {@code <=>} against the
 *       same {@code (tenant, scope, scope_key)} L2 partition; NULL embeddings
 *       pass {@code 0.0} (design §10 answer: skip semantic dedup, keep hash
 *       dedup). No synchronous LLM calls on the write path.</li>
 *   <li>Score via {@link MemoryTierEvaluator}; below the default threshold
 *       (0.65) -> audit-free skip + {@code skipped_low_score} counter.</li>
 *   <li>Compute {@link #contentHash(String)} SHA-256 lowercase-trimmed.</li>
 *   <li>Hash-dedup lookup: if an existing L2 row in the same partition has
 *       the same {@code content_hash}, merge (increment access_count,
 *       GREATEST(importance)); write {@code DEDUP_HIT} audit row;
 *       {@code skipped_dup} counter.</li>
 *   <li>Otherwise flip {@code category} session -> user, stamp
 *       {@code promoted_at} + {@code promoted_from_run_id} +
 *       {@code score_snapshot} + {@code content_hash}; write
 *       {@code L1_PROMOTED} audit row; {@code promoted} counter.</li>
 * </ol>
 *
 * <p>Red-line compliance:
 * <ul>
 *   <li>No fallback / ensure / retry — malformed event fields throw at
 *       {@link SessionEndedEvent} construction, not here.</li>
 *   <li>Lowercase enum DB values ({@code session}, {@code user}).</li>
 *   <li>Native SQL reads add explicit {@code deleted_flag} guard.</li>
 * </ul>
 *
 * <p>Concurrency: {@code @Transactional(REQUIRES_NEW)} isolates one event
 * from the run-completion transaction. Same-run duplicate events are
 * naturally idempotent: the second invocation re-loads the same L1 rows,
 * finds them already {@code category='user'} (and thus excluded by the
 * WHERE clause) or finds matching hash -> DEDUP_HIT path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryL1L2Promoter {

    /** Hard importance gate per design §4.1 — below this, candidate is not even loaded. */
    public static final int BASE_IMPORTANCE_GATE = 6;

    private final JdbcTemplate jdbc;
    private final MemoryTierEvaluator evaluator;
    private final MemoryL1L2PromotionMetrics metrics;
    private final ObjectMapper objectMapper;

    /**
     * Spring synchronous listener. Joins the current transaction (if any) so
     * a promotion failure rolls back with the publisher; kept sync in Phase 2
     * for deterministic tests and to defer thread-pool tuning to PR-84
     * alongside the orphan cron. No {@code REQUIRES_NEW} per project
     * red-line against bypassing rollback-only.
     */
    @EventListener(SessionEndedEvent.class)
    @Transactional
    public void onSessionEnded(SessionEndedEvent event) {
        handle(event);
    }

    /**
     * Package-visible direct entry point for tests that want to bypass the
     * Spring event bus (tests that run inside {@code @Transactional} would
     * otherwise see no effect from a nested REQUIRES_NEW transaction).
     */
    public PromotionSummary handle(SessionEndedEvent event) {
        Objects.requireNonNull(event, "event");
        Long tenantId = event.getTenantId();
        String runId = event.getRunId();

        List<Map<String, Object>> candidates = jdbc.queryForList(
                "SELECT pid, memory_agent_id, memory_type, memory_title, memory_content, "
                        + "       importance, access_count, created_at, scope, scope_key, "
                        + "       embedding "
                        + "  FROM ab_agent_memory "
                        + " WHERE tenant_id = ? "
                        + "   AND source_run_id = ? "
                        + "   AND category = 'session' "
                        + "   AND importance >= ? "
                        + "   AND (deleted_flag IS NULL OR deleted_flag = FALSE)",
                tenantId, runId, BASE_IMPORTANCE_GATE);

        int promoted = 0;
        int skippedLowScore = 0;
        int skippedDup = 0;

        Instant now = Instant.now();
        for (Map<String, Object> row : candidates) {
            try {
                Outcome o = evaluate(tenantId, runId, row, now);
                switch (o) {
                    case PROMOTED -> promoted++;
                    case DEDUP_HIT -> skippedDup++;
                    case LOW_SCORE -> skippedLowScore++;
                }
            } catch (RuntimeException e) {
                // Do not swallow — let the transaction roll back and the
                // event listener surface the failure. Increment a failed
                // counter first so alerting catches it, then re-throw.
                metrics.recordPromotionOutcome(tenantId,
                        MemoryL1L2PromotionMetrics.OUTCOME_FAILED);
                throw e;
            }
        }

        log.info("L1->L2 promotion for tenant={} run={}: candidates={} promoted={} "
                        + "dedup={} lowScore={}",
                tenantId, runId, candidates.size(), promoted, skippedDup, skippedLowScore);
        return new PromotionSummary(candidates.size(), promoted, skippedDup, skippedLowScore);
    }

    private Outcome evaluate(Long tenantId, String runId, Map<String, Object> row, Instant now) {
        String pid = (String) row.get("pid");
        int importance = ((Number) row.get("importance")).intValue();
        int accessCount = row.get("access_count") == null
                ? 0 : ((Number) row.get("access_count")).intValue();
        Instant createdAt = ((Timestamp) row.get("created_at")).toInstant();
        String scope = (String) row.get("scope");
        String scopeKey = (String) row.get("scope_key");
        String content = (String) row.get("memory_content");
        String agentCode = (String) row.get("memory_agent_id");
        Object embedding = row.get("embedding");

        double maxCosineToL2 = (embedding == null)
                ? 0.0
                : queryMaxCosineToL2(tenantId, scope, scopeKey, pid);

        MemoryTierEvaluator.ScoreResult score = evaluator.score(
                new MemoryTierEvaluator.Candidate(importance, accessCount, createdAt, maxCosineToL2),
                now);

        if (!evaluator.shouldPromote(score)) {
            metrics.recordPromotionOutcome(tenantId,
                    MemoryL1L2PromotionMetrics.OUTCOME_SKIPPED_LOW_SCORE);
            return Outcome.LOW_SCORE;
        }

        String hash = contentHash(content);

        // Hash dedup against existing L2 in the same partition.
        String existingL2Pid = findL2ByHash(tenantId, scope, scopeKey, hash);
        if (existingL2Pid != null) {
            jdbc.update(
                    "UPDATE ab_agent_memory "
                            + "   SET access_count = COALESCE(access_count, 0) + 1, "
                            + "       importance = GREATEST(COALESCE(importance, 0), ?), "
                            + "       updated_at = NOW() "
                            + " WHERE pid = ?",
                    importance, existingL2Pid);
            writeAuditRow(tenantId, pid, MemoryL1L2PromotionMetrics.EVENT_TYPE_DEDUP_HIT,
                    "hash", existingL2Pid, score, runId);
            metrics.recordPromotionOutcome(tenantId,
                    MemoryL1L2PromotionMetrics.OUTCOME_SKIPPED_DUP);
            return Outcome.DEDUP_HIT;
        }

        // Promote: flip category, stamp metadata, write content_hash.
        String scoreJson = toJson(score);
        int updated = jdbc.update(
                "UPDATE ab_agent_memory "
                        + "   SET category = 'user', "
                        + "       promoted_at = NOW(), "
                        + "       promoted_from_run_id = ?, "
                        + "       score_snapshot = ?::jsonb, "
                        + "       content_hash = ?, "
                        + "       updated_at = NOW() "
                        + " WHERE pid = ? "
                        + "   AND category = 'session' "
                        + "   AND (deleted_flag IS NULL OR deleted_flag = FALSE)",
                runId, scoreJson, hash, pid);

        if (updated == 0) {
            // Race — someone else flipped this row. Count as dup and audit.
            metrics.recordPromotionOutcome(tenantId,
                    MemoryL1L2PromotionMetrics.OUTCOME_SKIPPED_DUP);
            return Outcome.DEDUP_HIT;
        }

        writeAuditRow(tenantId, pid, MemoryL1L2PromotionMetrics.EVENT_TYPE_L1_PROMOTED,
                null, null, score, runId);
        metrics.recordPromotionOutcome(tenantId,
                MemoryL1L2PromotionMetrics.OUTCOME_PROMOTED);
        log.debug("Promoted L1->L2 pid={} agent={} tenant={} score={}",
                pid, agentCode, tenantId, score.score());
        return Outcome.PROMOTED;
    }

    private double queryMaxCosineToL2(Long tenantId, String scope, String scopeKey, String selfPid) {
        // pgvector cosine distance is (1 - cosine_similarity); clamp below.
        // Use scope + scope_key exact-match partition per design §4.2.
        Double dist;
        if (scopeKey == null) {
            dist = jdbc.query(
                    "SELECT MIN(embedding <=> (SELECT embedding FROM ab_agent_memory WHERE pid = ?)) "
                            + "  FROM ab_agent_memory "
                            + " WHERE tenant_id = ? "
                            + "   AND scope = ? "
                            + "   AND scope_key IS NULL "
                            + "   AND category IN ('user','agent') "
                            + "   AND embedding IS NOT NULL "
                            + "   AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                            + "   AND pid <> ?",
                    rs -> rs.next() ? (Double) rs.getObject(1) : null,
                    selfPid, tenantId, scope, selfPid);
        } else {
            dist = jdbc.query(
                    "SELECT MIN(embedding <=> (SELECT embedding FROM ab_agent_memory WHERE pid = ?)) "
                            + "  FROM ab_agent_memory "
                            + " WHERE tenant_id = ? "
                            + "   AND scope = ? "
                            + "   AND scope_key = ? "
                            + "   AND category IN ('user','agent') "
                            + "   AND embedding IS NOT NULL "
                            + "   AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                            + "   AND pid <> ?",
                    rs -> rs.next() ? (Double) rs.getObject(1) : null,
                    selfPid, tenantId, scope, scopeKey, selfPid);
        }
        if (dist == null) return 0.0;
        double cos = 1.0 - dist;
        if (cos < 0.0) return 0.0;
        if (cos > 1.0) return 1.0;
        return cos;
    }

    private String findL2ByHash(Long tenantId, String scope, String scopeKey, String hash) {
        if (scopeKey == null) {
            List<String> rows = jdbc.queryForList(
                    "SELECT pid FROM ab_agent_memory "
                            + " WHERE tenant_id = ? AND scope = ? AND scope_key IS NULL "
                            + "   AND content_hash = ? "
                            + "   AND category IN ('user','agent') "
                            + "   AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                            + " ORDER BY created_at ASC LIMIT 1",
                    String.class, tenantId, scope, hash);
            return rows.isEmpty() ? null : rows.get(0);
        }
        List<String> rows = jdbc.queryForList(
                "SELECT pid FROM ab_agent_memory "
                        + " WHERE tenant_id = ? AND scope = ? AND scope_key = ? "
                        + "   AND content_hash = ? "
                        + "   AND category IN ('user','agent') "
                        + "   AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                        + " ORDER BY created_at ASC LIMIT 1",
                String.class, tenantId, scope, scopeKey, hash);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void writeAuditRow(Long tenantId, String memoryPid, String eventType,
                               String dedupMode, String mergedIntoPid,
                               MemoryTierEvaluator.ScoreResult score, String runId) {
        String scoreJson = toJson(score);
        jdbc.update(
                "INSERT INTO ab_agent_memory_tier_event "
                        + "  (pid, tenant_id, memory_pid, event_type, dedup_mode, "
                        + "   merged_into_pid, score_snapshot, source_run_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, NOW())",
                UniqueIdGenerator.generate(), tenantId, memoryPid, eventType,
                dedupMode, mergedIntoPid, scoreJson, runId);
        metrics.recordTierEvent(tenantId, eventType);
    }

    private String toJson(MemoryTierEvaluator.ScoreResult score) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "score", score.score(),
                    "factors", Map.of(
                            "imp", score.importanceFactor(),
                            "acc", score.accessFactor(),
                            "rec", score.recencyFactor(),
                            "uni", score.uniquenessFactor()),
                    "weights_version", score.weightsVersion(),
                    "computed_at", score.computedAt().toString()));
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize score_snapshot", e);
        }
    }

    /**
     * SHA-256 over {@code lowercase(trim(content))} per design §4.3. Null /
     * blank content throws — the caller guarantees non-null {@code memory_content}
     * (NOT NULL column).
     */
    public static String contentHash(String content) {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        String normalized = content.trim().toLowerCase();
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // -----------------------------------------------------------------

    public enum Outcome {
        PROMOTED,
        DEDUP_HIT,
        LOW_SCORE
    }

    /**
     * Summary returned by the test-friendly {@link #handle} overload. Values
     * are per-event counts, never cumulative.
     */
    public record PromotionSummary(int candidates, int promoted, int dedupHits, int lowScore) {
    }
}
