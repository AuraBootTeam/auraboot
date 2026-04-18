package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.metrics.MemoryPromotionMetrics;
import com.auraboot.framework.agent.util.ConfidenceScorer;
import com.auraboot.framework.agent.util.EmbeddingSimilarity;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Memory Promotion extractor (PR-65, plan §6.1).
 *
 * <p>Scans user-scope memories nightly and emits DRAFT proposals in
 * {@code ab_agent_memory_promotion} for three strategies:
 *
 * <ul>
 *   <li><b>cross_user_agreement</b>: ≥{@value ConfidenceScorer#CROSS_USER_MIN_AGREEMENT}
 *       distinct users in the same tenant hold embedding-similar memories
 *       (cosine ≥ threshold, default 0.85).</li>
 *   <li><b>implicit_co_sign</b>: a shareable, high-importance user memory
 *       accessed (via {@code last_accessed}) by ≥3 other users in the last 90d.
 *       <i>Phase 1 limitation:</i> we currently cannot attribute
 *       {@code last_accessed} to a different user than the owner (no access
 *       log by user). A follow-up will add a memory access log; for now
 *       this strategy is wired but effectively no-op unless the access log
 *       surfaces. This is documented as a known gap for Phase 2.</li>
 *   <li><b>importance_spike</b>: a single shareable memory with importance
 *       ≥ threshold (default 9). Disabled by default per plan §6.1.</li>
 * </ul>
 *
 * <p><b>Concurrency:</b> protected by Postgres advisory lock key
 * {@value #LOCK_KEY}. Pinned connection via {@link TransactionTemplate},
 * mirroring {@code PromotionEvaluationRunner}.
 *
 * <p><b>Dedup:</b> never re-propose for a {@code source_memory_pid} whose
 * prior proposal is still non-terminal (see {@link #NON_TERMINAL_STATUSES}).
 */
@Slf4j
@Service
public class MemoryPromotionExtractor {

    /** Advisory-lock key; distinct from PromotionEvaluationRunner (7302). */
    private static final long LOCK_KEY = 7303L;

    private static final Set<String> NON_TERMINAL_STATUSES =
            Set.of("DRAFT_PENDING_REVIEW", "PROMOTED_SHADOW", "ACTIVE");

    private final JdbcTemplate jdbcTemplate;
    private final MemoryEmbeddingService memoryEmbeddingService;
    private final MemoryPromotionMetrics metrics;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public MemoryPromotionExtractor(JdbcTemplate jdbcTemplate,
                                    MemoryEmbeddingService memoryEmbeddingService,
                                    MemoryPromotionMetrics metrics,
                                    ObjectMapper objectMapper,
                                    PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.memoryEmbeddingService = memoryEmbeddingService;
        this.metrics = metrics;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Value("${acp.memory.promotion.scheduler.enabled:false}")
    private boolean enabled;

    @Value("${acp.memory.promotion.min-users-per-tenant:3}")
    private int minUsersPerTenant;

    @Value("${acp.memory.promotion.min-similarity:0.85}")
    private double minSimilarity;

    @Value("${acp.memory.promotion.min-importance-for-spike:9}")
    private int minImportanceForSpike;

    @Value("${acp.memory.promotion.importance_spike.enabled:false}")
    private boolean importanceSpikeEnabled;

    @Value("${acp.memory.promotion.rationale.enabled:true}")
    private boolean rationaleEnabled;

    @Scheduled(cron = "${acp.memory.promotion.scheduler.cron:0 30 3 * * *}")
    public void runScheduled() {
        if (!enabled) return;
        int proposals = runOnce();
        if (proposals > 0) {
            log.info("MemoryPromotionExtractor: emitted {} proposal(s)", proposals);
        }
    }

    /** Returns the number of proposals created in this tick. */
    public int runOnce() {
        Integer result = transactionTemplate.execute(status -> {
            Boolean acquired = jdbcTemplate.queryForObject(
                    "SELECT pg_try_advisory_lock(?)", Boolean.class, LOCK_KEY);
            if (!Boolean.TRUE.equals(acquired)) {
                log.debug("MemoryPromotionExtractor: another instance holds advisory lock {}, skipping tick", LOCK_KEY);
                return 0;
            }
            try {
                return runOnceLocked();
            } finally {
                Boolean released = jdbcTemplate.queryForObject(
                        "SELECT pg_advisory_unlock(?)", Boolean.class, LOCK_KEY);
                if (!Boolean.TRUE.equals(released)) {
                    log.warn("MemoryPromotionExtractor: pg_advisory_unlock({}) returned {} — possible connection mismatch",
                            LOCK_KEY, released);
                }
            }
        });
        return result == null ? 0 : result;
    }

    private int runOnceLocked() {
        // Determine candidate tenants: any tenant with user-scope memories.
        List<Long> tenantIds = jdbcTemplate.queryForList(
                "SELECT DISTINCT tenant_id FROM ab_agent_memory "
                        + "WHERE scope = 'user' "
                        + "AND (deleted_flag = FALSE OR deleted_flag IS NULL) "
                        + "AND updated_at > NOW() - INTERVAL '90 days'",
                Long.class);
        int total = 0;
        for (Long tenantId : tenantIds) {
            total += runForTenant(tenantId);
        }
        return total;
    }

    /** Scans a single tenant's memories; used by tests and the locked runner. */
    public int runForTenant(Long tenantId) {
        int count = 0;
        count += extractCrossUserAgreement(tenantId);
        count += extractImplicitCoSign(tenantId);
        if (importanceSpikeEnabled) {
            count += extractImportanceSpike(tenantId);
        }
        return count;
    }

    // ------------------------------------------------------------------
    // Strategy A — cross_user_agreement
    // ------------------------------------------------------------------

    int extractCrossUserAgreement(Long tenantId) {
        List<MemoryRow> rows = loadUserMemories(tenantId);
        // Ensure embeddings (lazy compute when missing).
        List<MemoryRow> embedded = new ArrayList<>(rows.size());
        for (MemoryRow r : rows) {
            double[] v = r.embedding;
            if (v == null || v.length == 0) {
                v = memoryEmbeddingService.resolveEmbedding(r.pid);
            }
            if (v != null && v.length > 0) {
                r.embedding = v;
                embedded.add(r);
            } else {
                log.debug("extractCrossUserAgreement: skip memory {} (no embedding)", r.pid);
            }
        }
        if (embedded.size() < minUsersPerTenant) {
            return 0;
        }

        boolean[] used = new boolean[embedded.size()];
        int proposals = 0;

        for (int i = 0; i < embedded.size(); i++) {
            if (used[i]) continue;
            MemoryRow seed = embedded.get(i);
            List<Integer> clusterIdx = new ArrayList<>();
            clusterIdx.add(i);
            double minPairwise = 1.0d;

            for (int j = i + 1; j < embedded.size(); j++) {
                if (used[j]) continue;
                MemoryRow other = embedded.get(j);
                double sim = EmbeddingSimilarity.cosine(seed.embedding, other.embedding);
                if (sim >= minSimilarity) {
                    clusterIdx.add(j);
                    if (sim < minPairwise) minPairwise = sim;
                }
            }

            // Count distinct users in cluster
            Set<Long> distinctUsers = new LinkedHashSet<>();
            for (int idx : clusterIdx) {
                Long uid = embedded.get(idx).scopeKeyAsLong();
                if (uid != null) distinctUsers.add(uid);
            }
            if (distinctUsers.size() < minUsersPerTenant) {
                continue;
            }

            List<String> sourcePids = new ArrayList<>();
            for (int idx : clusterIdx) {
                sourcePids.add(embedded.get(idx).pid);
            }

            if (anyNonTerminal(sourcePids)) {
                log.debug("cross_user_agreement: cluster around {} already has pending/active proposal, skipping",
                        seed.pid);
                for (int idx : clusterIdx) used[idx] = true;
                continue;
            }

            double confidence = ConfidenceScorer.forCrossUserAgreement(distinctUsers.size(), minPairwise);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("user_ids", new ArrayList<>(distinctUsers));
            detail.put("agreement_count", distinctUsers.size());
            detail.put("min_similarity", round2(minPairwise));

            insertProposal(
                    tenantId,
                    /*sourceScope*/"user",
                    /*sourceMemoryPid*/seed.pid,
                    /*sourceMemoryPids*/sourcePids,
                    /*targetScope*/"tenant",
                    seed.category == null ? "general" : seed.category,
                    seed.title,
                    seed.content,
                    seed.importance,
                    MemoryPromotionMetrics.REASON_CROSS_USER_AGREEMENT,
                    detail,
                    confidence,
                    round2(minPairwise));

            for (int idx : clusterIdx) used[idx] = true;
            proposals++;
        }
        return proposals;
    }

    // ------------------------------------------------------------------
    // Strategy B — implicit_co_sign
    // ------------------------------------------------------------------

    int extractImplicitCoSign(Long tenantId) {
        // Phase 1 gap: ab_agent_memory has a single last_accessed timestamp
        // and no per-user access log. Without attribution we cannot count
        // co-signers. The extractor still runs the query so integration
        // tests can exercise the wiring once an access log is added.
        // Implemented as: candidate selection only; proposal emitted only
        // if reason_detail.co_signer_user_ids is populated by a future
        // access-log source.
        List<MemoryRow> candidates = jdbcTemplate.query(
                "SELECT pid, tenant_id, scope_key, category, memory_title, memory_content, "
                        + "       importance, embedding::text AS embedding_text "
                        + "FROM ab_agent_memory "
                        + "WHERE tenant_id = ? "
                        + "AND scope = 'user' "
                        + "AND shareable = TRUE "
                        + "AND importance >= 8 "
                        + "AND (deleted_flag = FALSE OR deleted_flag IS NULL)",
                (rs, rn) -> MemoryRow.fromResultSet(rs),
                tenantId);

        int proposals = 0;
        for (MemoryRow r : candidates) {
            int coSigners = countCoSigners(r);
            if (coSigners < ConfidenceScorer.CO_SIGN_MIN_COUNT) continue;
            if (anyNonTerminal(List.of(r.pid))) continue;

            double confidence = ConfidenceScorer.forImplicitCoSign(coSigners);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("author_user_id", r.scopeKey);
            detail.put("co_signer_count", coSigners);

            insertProposal(tenantId, "user", r.pid, List.of(r.pid), "tenant",
                    r.category == null ? "general" : r.category,
                    r.title, r.content, r.importance,
                    MemoryPromotionMetrics.REASON_IMPLICIT_CO_SIGN,
                    detail, confidence, null);
            proposals++;
        }
        return proposals;
    }

    /**
     * Count co-signers for a memory. Phase 1 returns 0 — requires per-user
     * access log. Package-private for test override (see integration test
     * spy pattern).
     */
    protected int countCoSigners(MemoryRow row) {
        // TODO(phase-2): implement against a future memory_access_log table.
        return 0;
    }

    // ------------------------------------------------------------------
    // Strategy C — importance_spike
    // ------------------------------------------------------------------

    int extractImportanceSpike(Long tenantId) {
        List<MemoryRow> candidates = jdbcTemplate.query(
                "SELECT pid, tenant_id, scope_key, category, memory_title, memory_content, "
                        + "       importance, embedding::text AS embedding_text "
                        + "FROM ab_agent_memory "
                        + "WHERE tenant_id = ? "
                        + "AND scope = 'user' "
                        + "AND shareable = TRUE "
                        + "AND importance >= ? "
                        + "AND (deleted_flag = FALSE OR deleted_flag IS NULL)",
                (rs, rn) -> MemoryRow.fromResultSet(rs),
                tenantId, minImportanceForSpike);

        int proposals = 0;
        for (MemoryRow r : candidates) {
            if (anyNonTerminal(List.of(r.pid))) continue;
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("author_user_id", r.scopeKey);
            detail.put("importance", r.importance);
            insertProposal(tenantId, "user", r.pid, List.of(r.pid), "tenant",
                    r.category == null ? "general" : r.category,
                    r.title, r.content, r.importance,
                    MemoryPromotionMetrics.REASON_IMPORTANCE_SPIKE,
                    detail, ConfidenceScorer.forImportanceSpike(), null);
            proposals++;
        }
        return proposals;
    }

    // ------------------------------------------------------------------
    // Persistence + helpers
    // ------------------------------------------------------------------

    private List<MemoryRow> loadUserMemories(Long tenantId) {
        return jdbcTemplate.query(
                "SELECT pid, tenant_id, scope_key, category, memory_title, memory_content, "
                        + "       importance, embedding::text AS embedding_text "
                        + "FROM ab_agent_memory "
                        + "WHERE tenant_id = ? "
                        + "AND scope = 'user' "
                        + "AND (deleted_flag = FALSE OR deleted_flag IS NULL) "
                        + "AND updated_at > NOW() - INTERVAL '90 days'",
                (rs, rn) -> MemoryRow.fromResultSet(rs),
                tenantId);
    }

    private boolean anyNonTerminal(List<String> sourcePids) {
        if (sourcePids == null || sourcePids.isEmpty()) return false;
        Set<String> unique = new HashSet<>(sourcePids);
        String inList = String.join(",", Collections.nCopies(unique.size(), "?"));
        Object[] args = new Object[unique.size()];
        int i = 0;
        for (String pid : unique) {
            args[i++] = pid;
        }
        List<String> hits = jdbcTemplate.queryForList(
                "SELECT pid FROM ab_agent_memory_promotion "
                        + "WHERE status IN ('DRAFT_PENDING_REVIEW','PROMOTED_SHADOW','ACTIVE') "
                        + "AND source_memory_pid IN (" + inList + ")",
                String.class, args);
        return !hits.isEmpty();
    }

    private void insertProposal(Long tenantId,
                                String sourceScope,
                                String sourceMemoryPid,
                                List<String> sourceMemoryPids,
                                String targetScope,
                                String category,
                                String title,
                                String content,
                                int importance,
                                String reasonCode,
                                Map<String, Object> detail,
                                double confidence,
                                Double similarity) {
        String pid = UniqueIdGenerator.generate();
        String detailJson = toJson(detail);
        String sourcePidsJson = toJson(sourceMemoryPids);
        String aiRationale = rationaleEnabled ? generateRationale(reasonCode, title, content, detail) : null;

        jdbcTemplate.update(
                "INSERT INTO ab_agent_memory_promotion ("
                        + "pid, tenant_id, source_scope, source_memory_pid, source_memory_pids, "
                        + "target_scope, category, proposed_title, proposed_content, proposed_importance, "
                        + "reason_code, reason_detail, confidence_score, similarity_score, ai_rationale, "
                        + "status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, "
                        + "'DRAFT_PENDING_REVIEW', NOW(), NOW())",
                pid, tenantId, sourceScope, sourceMemoryPid, sourcePidsJson,
                targetScope, category, title, content, importance,
                reasonCode, detailJson, confidence, similarity, aiRationale);

        metrics.recordProposal(tenantId, reasonCode);
        log.debug("MemoryPromotionExtractor: proposal pid={} tenant={} reason={} confidence={}",
                pid, tenantId, reasonCode, confidence);
    }

    /**
     * Best-effort LLM-generated rationale. Returns null on failure; source
     * memories remain the primary evidence.
     *
     * TODO(phase-2): wire to {@code LlmProviderFactory.getProvider(...).chat(...)}
     * with a short prompt. Left as a hook in Phase 1 so the column is
     * present and the extractor does not block on LLM availability.
     */
    protected String generateRationale(String reasonCode,
                                       String title,
                                       String content,
                                       Map<String, Object> detail) {
        return null;
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise promotion JSON field: {}", e.getMessage());
            return null;
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0d) / 100.0d;
    }

    // ------------------------------------------------------------------
    // Row container
    // ------------------------------------------------------------------

    /** Lightweight DTO used internally by extraction strategies. */
    public static class MemoryRow {
        public String pid;
        public Long tenantId;
        public String scopeKey;
        public String category;
        public String title;
        public String content;
        public int importance;
        public double[] embedding;

        static MemoryRow fromResultSet(java.sql.ResultSet rs) throws java.sql.SQLException {
            MemoryRow r = new MemoryRow();
            r.pid = rs.getString("pid");
            r.tenantId = rs.getObject("tenant_id") == null ? null : rs.getLong("tenant_id");
            r.scopeKey = rs.getString("scope_key");
            r.category = rs.getString("category");
            r.title = rs.getString("memory_title");
            r.content = rs.getString("memory_content");
            r.importance = rs.getInt("importance");
            r.embedding = MemoryEmbeddingService.parseVectorLiteral(rs.getString("embedding_text"));
            return r;
        }

        Long scopeKeyAsLong() {
            if (scopeKey == null || scopeKey.isBlank()) return null;
            try {
                return Long.parseLong(scopeKey);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
