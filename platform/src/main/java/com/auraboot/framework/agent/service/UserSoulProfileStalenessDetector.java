package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.metrics.UserSoulProfileMetrics;
import com.auraboot.framework.agent.profile.UserSoulProfileStatus;
import com.auraboot.framework.rag.service.EmbeddingService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * User Soul Profile staleness detector (PR-76, plan §5.4).
 *
 * <p>Scheduled daily (default {@code 0 30 4 * * *}). For each ACTIVE,
 * non-hidden profile: embed the profile's persona+preferences text,
 * compare against the embeddings of recent high-importance user-scope
 * memories, and raise {@code stale_flagged_at} when ≥ N recent memories
 * diverge beyond the cosine threshold.
 *
 * <p>Advisory-lock key {@code 7308} — distinct from the deriver
 * ({@code 7306}) and activator ({@code 7307}).
 */
@Slf4j
@Service
public class UserSoulProfileStalenessDetector {

    static final long LOCK_KEY = 7308L;
    private static final String DEFAULT_PROVIDER = "openai";

    private final JdbcTemplate jdbcTemplate;
    private final MemoryEmbeddingService memoryEmbeddingService;
    private final EmbeddingService embeddingService;
    private final UserSoulProfileMetrics metrics;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    @Value("${acp.user.soul-profile.staleness.enabled:false}")
    private boolean enabled;

    @Value("${acp.user.soul-profile.staleness.min-divergent-memories:3}")
    private int minDivergentMemories;

    @Value("${acp.user.soul-profile.staleness.divergence-cosine-threshold:0.6}")
    private double divergenceCosineThreshold;

    @Value("${acp.user.soul-profile.staleness.recent-importance-threshold:7}")
    private int recentImportanceThreshold;

    @Value("${acp.user.soul-profile.staleness.recent-window-days:7}")
    private int recentWindowDays;

    public UserSoulProfileStalenessDetector(JdbcTemplate jdbcTemplate,
                                            MemoryEmbeddingService memoryEmbeddingService,
                                            EmbeddingService embeddingService,
                                            UserSoulProfileMetrics metrics,
                                            PlatformTransactionManager transactionManager,
                                            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.memoryEmbeddingService = memoryEmbeddingService;
        this.embeddingService = embeddingService;
        this.metrics = metrics;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    @Scheduled(cron = "${acp.user.soul-profile.staleness.cron:0 30 4 * * *}")
    public void runScheduled() {
        if (!enabled) return;
        int flagged = runOnce();
        if (flagged > 0) {
            log.info("UserSoulProfileStalenessDetector: flagged {} profile(s) stale", flagged);
        }
    }

    /** Returns the number of profiles flagged in this tick. */
    public int runOnce() {
        Integer result = transactionTemplate.execute(status -> {
            Boolean acquired = jdbcTemplate.queryForObject(
                    "SELECT pg_try_advisory_lock(?)", Boolean.class, LOCK_KEY);
            if (!Boolean.TRUE.equals(acquired)) {
                log.debug("UserSoulProfileStalenessDetector: advisory lock {} busy, skipping tick", LOCK_KEY);
                return 0;
            }
            try {
                return runOnceLocked();
            } finally {
                Boolean released = jdbcTemplate.queryForObject(
                        "SELECT pg_advisory_unlock(?)", Boolean.class, LOCK_KEY);
                if (!Boolean.TRUE.equals(released)) {
                    log.warn("UserSoulProfileStalenessDetector: pg_advisory_unlock({}) returned {}",
                            LOCK_KEY, released);
                }
            }
        });
        return result == null ? 0 : result;
    }

    private int runOnceLocked() {
        List<Map<String, Object>> profiles = jdbcTemplate.queryForList(
                "SELECT pid, tenant_id, user_id, profile::text AS profile_json "
                        + "FROM ab_agent_user_soul_profile "
                        + "WHERE status = ? "
                        + "  AND hidden_at IS NULL "
                        + "  AND stale_flagged_at IS NULL",
                UserSoulProfileStatus.ACTIVE.code());

        int flagged = 0;
        for (Map<String, Object> row : profiles) {
            String pid = (String) row.get("pid");
            Long tenantId = ((Number) row.get("tenant_id")).longValue();
            String userId = (String) row.get("user_id");
            String profileJson = (String) row.get("profile_json");

            String profileText = extractProfileText(profileJson, objectMapper);
            if (profileText == null || profileText.isBlank()) {
                log.debug("StalenessDetector: pid {} has no persona/preferences text, skipping", pid);
                continue;
            }

            double[] profileEmbedding;
            try {
                float[] v = embeddingService.embed(tenantId, profileText, DEFAULT_PROVIDER);
                profileEmbedding = v == null ? null : toDoubles(v);
            } catch (RuntimeException e) {
                // Allowed catch: embedding provider outage must not abort the
                // detector tick. Skip this profile; a subsequent run retries.
                log.warn("StalenessDetector: embedding provider failed for tenant={} user={}: {}",
                        tenantId, userId, e.toString());
                continue;
            }
            if (profileEmbedding == null || profileEmbedding.length == 0) {
                log.warn("StalenessDetector: no profile embedding for tenant={} user={} pid={}",
                        tenantId, userId, pid);
                continue;
            }

            // Fetch recent high-importance user-scope memories.
            List<Map<String, Object>> recent = jdbcTemplate.queryForList(
                    "SELECT pid FROM ab_agent_memory "
                            + "WHERE tenant_id = ? AND scope = 'user' AND scope_key = ? "
                            + "  AND importance >= ? "
                            + "  AND (deleted_flag = FALSE OR deleted_flag IS NULL) "
                            + "  AND created_at >= NOW() - make_interval(days => ?) "
                            + "ORDER BY created_at DESC",
                    tenantId, userId, recentImportanceThreshold, recentWindowDays);

            int divergent = 0;
            for (Map<String, Object> mem : recent) {
                String memPid = (String) mem.get("pid");
                double[] memEmbedding = memoryEmbeddingService.resolveEmbedding(memPid);
                if (memEmbedding == null || memEmbedding.length != profileEmbedding.length) {
                    continue;
                }
                double cos = cosineSimilarity(profileEmbedding, memEmbedding);
                if (cos < divergenceCosineThreshold) {
                    divergent++;
                    if (divergent >= minDivergentMemories) break;
                }
            }

            if (divergent >= minDivergentMemories) {
                int updated = jdbcTemplate.update(
                        "UPDATE ab_agent_user_soul_profile "
                                + "SET stale_flagged_at = NOW() "
                                + "WHERE pid = ? AND status = ? AND stale_flagged_at IS NULL",
                        pid, UserSoulProfileStatus.ACTIVE.code());
                if (updated == 1) {
                    flagged++;
                    metrics.recordStaleFlagged(tenantId);
                    log.info("StalenessDetector: flagged stale tenant={} user={} pid={} divergent={}",
                            tenantId, userId, pid, divergent);
                }
            }
        }
        return flagged;
    }

    /**
     * Extract the persona + preferences free-text for embedding. Tombstone
     * / empty profiles return null → caller skips.
     */
    static String extractProfileText(String profileJson, ObjectMapper mapper) {
        if (profileJson == null || profileJson.isBlank()) return null;
        Map<String, Object> profile;
        try {
            profile = mapper.readValue(profileJson, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            // Explicit validation: profile column should always be a JSON
            // object. Corrupt rows must not silently pass.
            throw new IllegalStateException("corrupt profile JSON for staleness check", e);
        }
        if (profile == null) return null;
        StringBuilder sb = new StringBuilder();
        Object persona = profile.get("persona");
        if (persona instanceof Map<?, ?> pm && pm.get("text") instanceof String s && !s.isBlank()) {
            sb.append(s).append('\n');
        }
        Object prefs = profile.get("preferences");
        if (prefs instanceof Map<?, ?> pm) {
            for (var e : pm.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> entry
                        && entry.get("text") instanceof String s && !s.isBlank()) {
                    sb.append(e.getKey()).append(": ").append(s).append('\n');
                }
            }
        }
        Object boundaries = profile.get("boundaries");
        if (boundaries instanceof Map<?, ?> bm && bm.get("text") instanceof String s && !s.isBlank()) {
            sb.append(s).append('\n');
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? null : out;
    }

    static double cosineSimilarity(double[] a, double[] b) {
        if (a.length != b.length || a.length == 0) return 0.0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0.0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static double[] toDoubles(float[] v) {
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) out[i] = v[i];
        return out;
    }

}
