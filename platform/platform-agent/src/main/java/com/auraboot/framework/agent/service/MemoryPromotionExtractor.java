package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.metrics.MemoryPromotionMetrics;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.util.ConfidenceScorer;
import com.auraboot.framework.agent.util.EmbeddingSimilarity;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
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

    /** Optional — some test profiles skip the LLM provider wiring. */
    @Autowired(required = false)
    private LlmProviderFactory llmProviderFactory;

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

    /**
     * Feature flag for the pgvector-indexed ANN shortlist path (PR-74 / N3).
     * When true (default), each seed memory pulls its 20 nearest neighbours
     * using the HNSW index; when false, the legacy O(n²) Java-side pairwise
     * cosine path runs instead. Kept as a config rollback switch in case
     * production surfaces index-specific issues.
     */
    @Value("${acp.memory.promotion.extractor.use-pgvector-shortlist:true}")
    private boolean usePgvectorShortlist;

    /** ANN candidate pool size per seed. Kept small — enough to cover the
     *  min-cluster + a few spurious nearby neighbours, no more. */
    private static final int ANN_CANDIDATE_LIMIT = 20;

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
        // PR-74 / N4: load every already-proposed source_memory_pid (non-terminal)
        // in one shot so downstream dedup is O(1) per cluster instead of N+1 query.
        Set<String> alreadyProposed = loadAlreadyProposedSourcePids(tenantId);
        int count = 0;
        count += extractCrossUserAgreement(tenantId, alreadyProposed);
        count += extractImplicitCoSign(tenantId, alreadyProposed);
        if (importanceSpikeEnabled) {
            count += extractImportanceSpike(tenantId, alreadyProposed);
        }
        return count;
    }

    /**
     * One-shot query returning the set of memory pids already referenced
     * (as {@code source_memory_pid} or expanded from {@code source_memory_pids})
     * by a non-terminal promotion in this tenant. The caller checks
     * containment in O(1).
     */
    private Set<String> loadAlreadyProposedSourcePids(Long tenantId) {
        Set<String> out = new HashSet<>();
        out.addAll(jdbcTemplate.queryForList(
                "SELECT DISTINCT source_memory_pid "
                        + "FROM ab_agent_memory_promotion "
                        + "WHERE tenant_id = ? "
                        + "AND status IN ('DRAFT_PENDING_REVIEW','PROMOTED_SHADOW','ACTIVE') "
                        + "AND source_memory_pid IS NOT NULL",
                String.class, tenantId));
        out.addAll(jdbcTemplate.queryForList(
                "SELECT DISTINCT jsonb_array_elements_text(source_memory_pids) "
                        + "FROM ab_agent_memory_promotion "
                        + "WHERE tenant_id = ? "
                        + "AND status IN ('DRAFT_PENDING_REVIEW','PROMOTED_SHADOW','ACTIVE') "
                        + "AND source_memory_pids IS NOT NULL",
                String.class, tenantId));
        out.remove(null);
        return out;
    }

    // ------------------------------------------------------------------
    // Strategy A — cross_user_agreement
    // ------------------------------------------------------------------

    /** Test-facing overload: load the dedup set inline. */
    int extractCrossUserAgreement(Long tenantId) {
        return extractCrossUserAgreement(tenantId, loadAlreadyProposedSourcePids(tenantId));
    }

    /**
     * Identify cross-user clusters. Two paths:
     *
     * <ul>
     *   <li><b>ANN shortlist (default):</b> for each seed, ask pgvector for
     *       its 20 nearest neighbours via the HNSW index, filter to those
     *       above {@code minSimilarity}, then cluster. O(n · log n) instead
     *       of O(n²).</li>
     *   <li><b>Fallback:</b> legacy Java-side pairwise cosine — kept so a
     *       bad rollout can be switched off via config, and so tenants on
     *       a pgvector-less database (dev/test) still get results.</li>
     * </ul>
     *
     * <p>Parity guarantee: the ANN path must return a superset of the
     * fallback result for the same data at the same {@code minSimilarity},
     * because:
     * <ul>
     *   <li>Every memory the fallback would match against seed S has cosine
     *       ≥ minSimilarity → it ranks in the top-K nearest neighbours for
     *       K ≫ typical cluster size. We use K=20 which comfortably exceeds
     *       {@code minUsersPerTenant} (default 3).</li>
     *   <li>We then apply the same cosine ≥ minSimilarity filter and the
     *       same distinct-user + cluster-size rules.</li>
     * </ul>
     * If a tenant has > 20 near-identical memories around a seed, only the
     * closest 20 are considered — but the proposal is still emitted, which
     * is the correctness property we care about (no false negatives).
     */
    int extractCrossUserAgreement(Long tenantId, Set<String> alreadyProposed) {
        if (usePgvectorShortlist) {
            return extractCrossUserAgreementAnn(tenantId, alreadyProposed);
        }
        return extractCrossUserAgreementFallback(tenantId, alreadyProposed);
    }

    /** pgvector HNSW-indexed shortlist path (PR-74 / N3). */
    private int extractCrossUserAgreementAnn(Long tenantId, Set<String> alreadyProposed) {
        // Candidate seeds: every user-scope memory with an embedding in the
        // last 90d. We iterate recent-first so active topics get proposed
        // before old ones during large backlogs.
        List<MemoryRow> seeds = jdbcTemplate.query(
                "SELECT pid, tenant_id, scope_key, category, memory_title, memory_content, "
                        + "       importance, embedding::text AS embedding_text "
                        + "FROM ab_agent_memory "
                        + "WHERE tenant_id = ? "
                        + "AND scope = 'user' "
                        + "AND embedding IS NOT NULL "
                        + "AND (deleted_flag = FALSE OR deleted_flag IS NULL) "
                        + "AND updated_at > NOW() - INTERVAL '90 days' "
                        + "ORDER BY updated_at DESC",
                (rs, rn) -> MemoryRow.fromResultSet(rs),
                tenantId);
        if (seeds.size() < minUsersPerTenant) {
            return 0;
        }

        Set<String> processed = new HashSet<>();
        int proposals = 0;

        for (MemoryRow seed : seeds) {
            if (processed.contains(seed.pid)) continue;
            if (alreadyProposed.contains(seed.pid)) {
                processed.add(seed.pid);
                continue;
            }
            if (seed.embedding == null || seed.embedding.length == 0) {
                continue;
            }

            // Ask pgvector for K nearest neighbours (excluding seed + same user).
            List<MemoryRow> neighbours = jdbcTemplate.query(
                    "SELECT pid, tenant_id, scope_key, category, memory_title, memory_content, "
                            + "       importance, embedding::text AS embedding_text "
                            + "FROM ab_agent_memory "
                            + "WHERE tenant_id = ? "
                            + "AND scope = 'user' "
                            + "AND (deleted_flag = FALSE OR deleted_flag IS NULL) "
                            + "AND embedding IS NOT NULL "
                            + "AND pid <> ? "
                            + "AND (scope_key IS NULL OR scope_key <> ?) "
                            + "ORDER BY embedding <=> (SELECT embedding FROM ab_agent_memory WHERE pid = ?) "
                            + "LIMIT ?",
                    (rs, rn) -> MemoryRow.fromResultSet(rs),
                    tenantId, seed.pid, seed.scopeKey == null ? "" : seed.scopeKey,
                    seed.pid, ANN_CANDIDATE_LIMIT);

            List<MemoryRow> cluster = new ArrayList<>();
            cluster.add(seed);
            double minPairwise = 1.0d;
            for (MemoryRow n : neighbours) {
                if (processed.contains(n.pid)) continue;
                if (alreadyProposed.contains(n.pid)) continue;
                if (n.embedding == null) continue;
                double sim = EmbeddingSimilarity.cosine(seed.embedding, n.embedding);
                if (sim >= minSimilarity) {
                    cluster.add(n);
                    if (sim < minPairwise) minPairwise = sim;
                }
            }

            if (!emitClusterIfQualifying(tenantId, seed, cluster, minPairwise, alreadyProposed, processed)) {
                processed.add(seed.pid);
                continue;
            }
            proposals++;
        }
        return proposals;
    }

    /** Pre-N3 fallback: load all, O(n²) pairwise. Retained behind config switch. */
    private int extractCrossUserAgreementFallback(Long tenantId, Set<String> alreadyProposed) {
        List<MemoryRow> rows = loadUserMemories(tenantId);
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
            if (alreadyProposed.contains(seed.pid)) {
                used[i] = true;
                continue;
            }
            List<Integer> clusterIdx = new ArrayList<>();
            clusterIdx.add(i);
            double minPairwise = 1.0d;

            for (int j = i + 1; j < embedded.size(); j++) {
                if (used[j]) continue;
                MemoryRow other = embedded.get(j);
                if (alreadyProposed.contains(other.pid)) continue;
                double sim = EmbeddingSimilarity.cosine(seed.embedding, other.embedding);
                if (sim >= minSimilarity) {
                    clusterIdx.add(j);
                    if (sim < minPairwise) minPairwise = sim;
                }
            }

            List<MemoryRow> cluster = new ArrayList<>(clusterIdx.size());
            for (int idx : clusterIdx) cluster.add(embedded.get(idx));

            // Inline-equivalent of emitClusterIfQualifying, but tracks `used`
            // for the O(n²) seed-skipping contract the fallback had before.
            Set<Long> distinctUsers = new LinkedHashSet<>();
            for (MemoryRow m : cluster) {
                Long uid = m.scopeKeyAsLong();
                if (uid != null) distinctUsers.add(uid);
            }
            if (distinctUsers.size() < minUsersPerTenant) {
                continue;
            }

            List<String> sourcePids = new ArrayList<>();
            for (MemoryRow m : cluster) sourcePids.add(m.pid);
            // alreadyProposed is a snapshot from run-start; also mark each
            // seed so the current tick doesn't re-propose the same cluster.
            boolean conflict = false;
            for (String p : sourcePids) {
                if (alreadyProposed.contains(p)) { conflict = true; break; }
            }
            if (conflict) {
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
                    "user",
                    seed.pid,
                    sourcePids,
                    "tenant",
                    seed.category == null ? "general" : seed.category,
                    seed.title,
                    seed.content,
                    seed.importance,
                    MemoryPromotionMetrics.REASON_CROSS_USER_AGREEMENT,
                    detail,
                    confidence,
                    round2(minPairwise));

            for (String p : sourcePids) alreadyProposed.add(p);
            for (int idx : clusterIdx) used[idx] = true;
            proposals++;
        }
        return proposals;
    }

    /**
     * Shared qualify-and-emit helper used by the ANN path. Returns true if
     * a proposal was inserted (and mutates the {@code processed} set to mark
     * every cluster member so further seeds skip the cluster).
     */
    private boolean emitClusterIfQualifying(Long tenantId,
                                            MemoryRow seed,
                                            List<MemoryRow> cluster,
                                            double minPairwise,
                                            Set<String> alreadyProposed,
                                            Set<String> processed) {
        Set<Long> distinctUsers = new LinkedHashSet<>();
        for (MemoryRow m : cluster) {
            Long uid = m.scopeKeyAsLong();
            if (uid != null) distinctUsers.add(uid);
        }
        if (distinctUsers.size() < minUsersPerTenant) {
            return false;
        }

        List<String> sourcePids = new ArrayList<>();
        for (MemoryRow m : cluster) sourcePids.add(m.pid);

        // Dedup against the batch snapshot (+ anything emitted this tick).
        for (String p : sourcePids) {
            if (alreadyProposed.contains(p)) {
                log.debug("cross_user_agreement: cluster around {} already has pending/active proposal, skipping",
                        seed.pid);
                for (MemoryRow m : cluster) processed.add(m.pid);
                return false;
            }
        }

        double confidence = ConfidenceScorer.forCrossUserAgreement(distinctUsers.size(), minPairwise);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("user_ids", new ArrayList<>(distinctUsers));
        detail.put("agreement_count", distinctUsers.size());
        detail.put("min_similarity", round2(minPairwise));

        insertProposal(
                tenantId,
                "user",
                seed.pid,
                sourcePids,
                "tenant",
                seed.category == null ? "general" : seed.category,
                seed.title,
                seed.content,
                seed.importance,
                MemoryPromotionMetrics.REASON_CROSS_USER_AGREEMENT,
                detail,
                confidence,
                round2(minPairwise));

        for (MemoryRow m : cluster) processed.add(m.pid);
        for (String p : sourcePids) alreadyProposed.add(p);
        return true;
    }

    // ------------------------------------------------------------------
    // Strategy B — implicit_co_sign
    // ------------------------------------------------------------------

    int extractImplicitCoSign(Long tenantId) {
        return extractImplicitCoSign(tenantId, loadAlreadyProposedSourcePids(tenantId));
    }

    int extractImplicitCoSign(Long tenantId, Set<String> alreadyProposed) {
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
            if (alreadyProposed.contains(r.pid)) continue;

            double confidence = ConfidenceScorer.forImplicitCoSign(coSigners);
            List<String> coSignerIds = loadCoSignerUserIds(r);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("author_user_id", r.scopeKey);
            detail.put("co_signer_count", coSigners);
            detail.put("co_signer_user_ids", coSignerIds);

            insertProposal(tenantId, "user", r.pid, List.of(r.pid), "tenant",
                    r.category == null ? "general" : r.category,
                    r.title, r.content, r.importance,
                    MemoryPromotionMetrics.REASON_IMPLICIT_CO_SIGN,
                    detail, confidence, null);
            alreadyProposed.add(r.pid);
            proposals++;
        }
        return proposals;
    }

    /**
     * Count distinct co-signers of a memory over the last 90 days (PR-66
     * Phase 2). A co-signer is any user whose id appears in
     * {@code ab_agent_memory_access_log} for the memory and is <b>not</b>
     * the author. Access rows older than 90 days are excluded so the signal
     * reflects current relevance.
     */
    /** List the distinct co-signer user ids (excluding the author) in the last 90d. */
    protected List<String> loadCoSignerUserIds(MemoryRow row) {
        if (row == null || row.pid == null) return List.of();
        String authorUserId = row.scopeKey;
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT user_id "
                        + "FROM ab_agent_memory_access_log "
                        + "WHERE memory_pid = ? "
                        + "  AND (? IS NULL OR user_id <> ?) "
                        + "  AND last_seen_at >= NOW() - INTERVAL '90 days' "
                        + "ORDER BY user_id",
                String.class, row.pid, authorUserId, authorUserId);
    }

    protected int countCoSigners(MemoryRow row) {
        if (row == null || row.pid == null) return 0;
        // scope_key is the author user id; exclude it so self-access does
        // not count as a co-sign.
        String authorUserId = row.scopeKey;
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT user_id) "
                        + "FROM ab_agent_memory_access_log "
                        + "WHERE memory_pid = ? "
                        + "  AND (? IS NULL OR user_id <> ?) "
                        + "  AND last_seen_at >= NOW() - INTERVAL '90 days'",
                Integer.class, row.pid, authorUserId, authorUserId);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------
    // Strategy C — importance_spike
    // ------------------------------------------------------------------

    int extractImportanceSpike(Long tenantId) {
        return extractImportanceSpike(tenantId, loadAlreadyProposedSourcePids(tenantId));
    }

    int extractImportanceSpike(Long tenantId, Set<String> alreadyProposed) {
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
            if (alreadyProposed.contains(r.pid)) continue;
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("author_user_id", r.scopeKey);
            detail.put("importance", r.importance);
            insertProposal(tenantId, "user", r.pid, List.of(r.pid), "tenant",
                    r.category == null ? "general" : r.category,
                    r.title, r.content, r.importance,
                    MemoryPromotionMetrics.REASON_IMPORTANCE_SPIKE,
                    detail, ConfidenceScorer.forImportanceSpike(), null);
            alreadyProposed.add(r.pid);
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
        String aiRationale = rationaleEnabled ? generateRationale(tenantId, reasonCode, title, content, detail) : null;

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
     * Best-effort LLM-generated rationale. Returns null on any failure; the
     * source memories remain the primary evidence and no rationale is
     * stored in that case.
     *
     * <p><b>Graceful-null rationale (plan §6.1):</b> this is the single place
     * in the promotion pipeline where we intentionally swallow exceptions
     * and return null. The LLM provider can be unreachable, rate-limited,
     * or misconfigured per-tenant — none of those should block the
     * extractor from emitting a proposal with full evidence. Unlike e.g.
     * API response parsing (which has a strict contract), the rationale
     * is a cosmetic reviewer hint; the design explicitly allows a null
     * value.
     */
    protected String generateRationale(Long tenantId,
                                       String reasonCode,
                                       String title,
                                       String content,
                                       Map<String, Object> detail) {
        if (!rationaleEnabled) return null;
        if (llmProviderFactory == null) return null;
        try {
            LlmProviderFactory.ProviderConfig cfg = llmProviderFactory.resolveConfig(tenantId, null);
            if (cfg == null || cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
                return null;
            }
            String effectiveProviderCode = LlmProviderFactory.effectiveProviderCode(null, cfg);
            LlmProvider provider = llmProviderFactory.getProvider(effectiveProviderCode);
            if (provider == null) return null;

            String systemPrompt =
                    "You are a team knowledge-curation advisor. Based on the memory below,"
                            + " produce exactly one short sentence (≤60 characters) explaining"
                            + " why this is worth promoting to team-shared knowledge."
                            + " Respond in Chinese if the source is Chinese, otherwise English."
                            + " Output only the sentence, no preamble.";
            String userText = "Memory:\n"
                    + (title == null ? "" : ("Title: " + title + "\n"))
                    + "Content: " + (content == null ? "" : content) + "\n"
                    + "Reason code: " + reasonCode;

            LlmChatRequest req = LlmChatRequest.builder()
                    .providerCode(cfg.getProviderCode())
                    .model(cfg.getDefaultModel())
                    .systemPrompt(systemPrompt)
                    .messages(List.of(LlmChatRequest.Message.builder()
                            .role("user").content(userText).build()))
                    .maxTokens(120)
                    .build();

            LlmChatResponse resp = provider.chat(req, cfg.getApiKey(), cfg.getBaseUrl());
            if (resp == null || resp.getContent() == null || resp.getContent().isEmpty()) {
                return null;
            }
            for (LlmChatResponse.ContentBlock block : resp.getContent()) {
                if ("text".equals(block.getType()) && block.getText() != null) {
                    String out = block.getText().trim();
                    if (!out.isEmpty()) {
                        return out.length() > 240 ? out.substring(0, 240) : out;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            // Graceful-null by design — see method javadoc.
            log.debug("generateRationale: LLM unavailable or failed ({}); storing null", e.getMessage());
            return null;
        }
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
