package com.auraboot.framework.agent.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ACP Learning Loop — Phase 1 (design/learning-loop.md §3).
 *
 * Scans {@code ab_agent_action} rows from the last N days and aggregates them
 * by a canonical {@code (command_signature, target_model, action_type)} tuple
 * into {@code ab_agent_learning_pattern} rows. Each pattern carries
 * invocation_count + success_rate; the upsert is idempotent on pattern_hash
 * so re-running the extractor refreshes stats without creating duplicates.
 *
 * Quality filter: patterns below {@code minInvocations} or with
 * {@code success_rate < minSuccessRate} are skipped (not even inserted) —
 * we don't want to seed the draft pipeline with weak signal.
 *
 * Scheduled daily at 02:00 UTC; also callable programmatically for tests /
 * manual triggering.
 *
 * Pragmatic deviation from learning-loop.md §3.1: the doc's canonical hash
 * mixes a dozen BIF dimensions (filters / scope / profile / channel / ...).
 * Our PR-15 {@code command_signature} already captures the (code + canonical
 * args) decision surface, so we combine it with target_model + action_type
 * to produce pattern_hash. When BIF.profile_id / channel land we can extend
 * the key without data migration (pattern_hash just bumps).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PatternExtractor {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${acp.learning.pattern.window-days:30}")
    private int windowDays;

    @Value("${acp.learning.pattern.min-invocations:5}")
    private int minInvocations;

    @Value("${acp.learning.pattern.min-success-rate:0.80}")
    private double minSuccessRate;

    /**
     * Scan recent Actions and upsert pattern rows. Returns the number of
     * pattern rows upserted (created OR updated). Patterns that don't pass
     * the quality filter are counted as observations only on existing rows
     * (no new row created).
     */
    @Scheduled(cron = "${acp.learning.pattern.cron:0 0 2 * * *}")
    public int extractPatterns() {
        log.info("Learning Loop — pattern extraction starting: window={}d, min_inv={}, min_succ={}",
                windowDays, minInvocations, minSuccessRate);

        List<PatternCandidate> candidates = loadCandidates();
        int upserted = 0;
        int filtered = 0;
        for (PatternCandidate c : candidates) {
            if (!qualifies(c)) {
                filtered++;
                continue;
            }
            if (upsert(c)) upserted++;
        }
        log.info("Learning Loop — pattern extraction done: candidates={}, upserted={}, filtered={}",
                candidates.size(), upserted, filtered);
        return upserted;
    }

    /**
     * Read-only aggregation query. Exposed for testing / HITL listings.
     */
    public List<PatternCandidate> loadCandidates() {
        String sql =
                "SELECT tenant_id, command_signature, target_model, action_type, " +
                "  COUNT(*)                                        AS invocation_count, " +
                "  SUM(CASE WHEN action_status='success' THEN 1 ELSE 0 END)::float " +
                "    / NULLIF(COUNT(*), 0)                         AS success_rate, " +
                "  MIN(executed_at)                                AS first_seen_at, " +
                "  MAX(executed_at)                                AS last_seen_at " +
                "FROM ab_agent_action " +
                "WHERE executed_at > NOW() - (? || ' days')::interval " +
                "  AND command_signature IS NOT NULL " +
                "  AND target_model IS NOT NULL " +
                "GROUP BY tenant_id, command_signature, target_model, action_type " +
                "ORDER BY invocation_count DESC";
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> PatternCandidate.builder()
                        .tenantId(rs.getObject("tenant_id", Long.class))
                        .commandSignature(rs.getString("command_signature"))
                        .targetModel(rs.getString("target_model"))
                        .actionType(rs.getString("action_type"))
                        .invocationCount(rs.getLong("invocation_count"))
                        .successRate(rs.getDouble("success_rate"))
                        .firstSeenAt(rs.getObject("first_seen_at", java.time.OffsetDateTime.class))
                        .lastSeenAt(rs.getObject("last_seen_at", java.time.OffsetDateTime.class))
                        .build(),
                String.valueOf(windowDays));
    }

    /**
     * Quality filter — simplified from learning-loop.md §3.2 (entropy /
     * parameter diversity checks are deferred until we have multi-skill runs
     * to compare against).
     */
    public boolean qualifies(PatternCandidate c) {
        if (c.invocationCount < minInvocations) return false;
        if (c.successRate < minSuccessRate) return false;
        return true;
    }

    /**
     * Idempotent upsert on pattern_hash. Returns true when a new row was
     * inserted (first observation); false when an existing row was updated
     * (re-observation).
     */
    private boolean upsert(PatternCandidate c) {
        String hash = patternHash(c);
        Map<String, Object> signature = new LinkedHashMap<>();
        signature.put("command_signature", c.commandSignature);
        signature.put("target_model", c.targetModel);
        signature.put("action_type", c.actionType);

        try {
            String signatureJson = objectMapper.writeValueAsString(signature);
            int inserted = jdbcTemplate.update(
                    "INSERT INTO ab_agent_learning_pattern " +
                            "(pid, tenant_id, pattern_hash, pattern_signature, " +
                            " invocation_count, success_rate, first_seen_at, last_observed_at) " +
                            "VALUES (?, ?, ?, ?::jsonb, ?, ?, NOW(), NOW()) " +
                            "ON CONFLICT (pattern_hash) DO UPDATE SET " +
                            "  invocation_count = EXCLUDED.invocation_count, " +
                            "  success_rate     = EXCLUDED.success_rate, " +
                            "  last_observed_at = NOW(), " +
                            "  updated_at       = NOW()",
                    UniqueIdGenerator.generate(), c.tenantId, hash, signatureJson,
                    c.invocationCount, c.successRate);
            return inserted == 1;
        } catch (Exception e) {
            log.warn("Failed to upsert pattern hash={} tenant={}: {}", hash, c.tenantId, e.getMessage());
            return false;
        }
    }

    /**
     * Canonical hash for the pattern. SHA-256 of the pipe-joined decision
     * surface so two Actions representing the same semantic op collapse.
     */
    public String patternHash(PatternCandidate c) {
        String raw = nullSafe(c.tenantId) + "|"
                + nullSafe(c.commandSignature) + "|"
                + nullSafe(c.targetModel) + "|"
                + nullSafe(c.actionType);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : bytes) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String nullSafe(Object v) {
        return v == null ? "" : v.toString();
    }

    // =========================================================================
    // DTO
    // =========================================================================

    /** Raw aggregation row from ab_agent_action, pre-upsert. */
    @Data
    @Builder
    public static class PatternCandidate {
        private Long tenantId;
        private String commandSignature;
        private String targetModel;
        private String actionType;
        private long invocationCount;
        private double successRate;
        private java.time.OffsetDateTime firstSeenAt;
        private java.time.OffsetDateTime lastSeenAt;
    }
}
