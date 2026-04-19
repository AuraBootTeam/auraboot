package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.metrics.UserSoulProfileMetrics;
import com.auraboot.framework.agent.profile.ProfileConfidenceScorer;
import com.auraboot.framework.agent.profile.ProfileHasher;
import com.auraboot.framework.agent.profile.ProfileProjector;
import com.auraboot.framework.agent.profile.ProfileProjector.ProjectionResult;
import com.auraboot.framework.agent.profile.UserSoulProfileStatus;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.util.PGobject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Nightly User Soul Profile deriver (PR-75, plan §5.1).
 *
 * <p>Pure-projection pipeline in Phase 1: gather user-scope memories +
 * recent actions → run {@link ProfileProjector} → score via
 * {@link ProfileConfidenceScorer} → hash via {@link ProfileHasher} →
 * persist as DRAFT if content differs from the prior ACTIVE profile.
 * LLM rendering of prose fields is planned for a later phase; the
 * deterministic templates produced here are still citable end-to-end.
 *
 * <p>Advisory-lock key {@code 7306} — distinct from Memory Promotion's
 * {@code 7303/7304/7305}.
 *
 * <p>Gated by {@code acp.user.soul-profile.derivation.enabled} (default
 * false). The scheduled entry point is a no-op when disabled so
 * production tenants must explicitly opt in.
 */
@Slf4j
@Service
public class UserSoulProfileDeriver {

    static final long LOCK_KEY = 7306L;
    private static final String DERIVATION_MODEL_TEMPLATE = "template:v1";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;
    private final AgentMemoryService agentMemoryService;
    private final UserSoulProfileMetrics metrics;
    private final TransactionTemplate transactionTemplate;

    @Value("${acp.user.soul-profile.derivation.enabled:false}")
    private boolean enabled;

    @Value("${acp.user.soul-profile.min-memories-for-derivation:3}")
    private int minMemories;

    @Value("${acp.user.soul-profile.look-back-days:90}")
    private int lookBackDays;

    @Value("${acp.user.soul-profile.llm.enabled:true}")
    private boolean llmEnabled;

    public UserSoulProfileDeriver(JdbcTemplate jdbcTemplate,
                                  AgentMemoryService agentMemoryService,
                                  UserSoulProfileMetrics metrics,
                                  PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = jdbcTemplate;
        this.agentMemoryService = agentMemoryService;
        this.metrics = metrics;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Outcome summary returned from {@link #deriveForUser(Long, String)}.
     * Matches the outcome tag values emitted to the metrics registry.
     */
    public enum Outcome {
        DRAFTED(UserSoulProfileMetrics.OUTCOME_DRAFTED),
        SKIPPED_NO_CHANGE(UserSoulProfileMetrics.OUTCOME_SKIPPED_NO_CHANGE),
        SKIPPED_TOO_LITTLE_SIGNAL(UserSoulProfileMetrics.OUTCOME_SKIPPED_TOO_LITTLE_SIGNAL),
        SKIPPED_FORGOTTEN(UserSoulProfileMetrics.OUTCOME_SKIPPED_FORGOTTEN),
        FAILED(UserSoulProfileMetrics.OUTCOME_FAILED);
        public final String tag;
        Outcome(String tag) { this.tag = tag; }
    }

    public record DerivationResult(Outcome outcome, String profilePid, String profileHash) {}

    // ---- Scheduled entry point --------------------------------------

    @Scheduled(cron = "${acp.user.soul-profile.scheduler.cron:0 0 4 * * *}")
    public int runScheduled() {
        if (!enabled) return 0;
        Integer drafted = transactionTemplate.execute(status -> {
            Boolean acquired = jdbcTemplate.queryForObject(
                    "SELECT pg_try_advisory_lock(?)", Boolean.class, LOCK_KEY);
            if (!Boolean.TRUE.equals(acquired)) {
                log.debug("UserSoulProfileDeriver: advisory lock {} busy, skipping tick", LOCK_KEY);
                return 0;
            }
            try {
                return runScheduledLocked();
            } finally {
                Boolean released = jdbcTemplate.queryForObject(
                        "SELECT pg_advisory_unlock(?)", Boolean.class, LOCK_KEY);
                if (!Boolean.TRUE.equals(released)) {
                    log.warn("UserSoulProfileDeriver: pg_advisory_unlock({}) returned {}", LOCK_KEY, released);
                }
            }
        });
        return drafted == null ? 0 : drafted;
    }

    private int runScheduledLocked() {
        // Discover candidate (tenant, user) pairs from recent user-scope memories.
        List<Map<String, Object>> candidates = jdbcTemplate.queryForList(
                "SELECT DISTINCT tenant_id, scope_key AS user_id "
                        + "FROM ab_agent_memory "
                        + "WHERE scope = 'user' AND scope_key IS NOT NULL "
                        + "  AND (deleted_flag IS NULL OR deleted_flag = FALSE) "
                        + "  AND created_at >= NOW() - (? || ' days')::interval",
                lookBackDays);
        int drafted = 0;
        for (Map<String, Object> row : candidates) {
            Long tenantId = ((Number) row.get("tenant_id")).longValue();
            String userId = (String) row.get("user_id");
            try {
                DerivationResult r = deriveForUser(tenantId, userId);
                if (r.outcome() == Outcome.DRAFTED) drafted++;
            } catch (RuntimeException e) {
                log.warn("UserSoulProfileDeriver: derivation failed for tenant={} user={}: {}",
                        tenantId, userId, e.toString());
                metrics.recordDerivation(tenantId, Outcome.FAILED.tag);
            }
        }
        return drafted;
    }

    // ---- Direct entry point -----------------------------------------

    /**
     * Direct derivation for a specific (tenant, user). Exposed for
     * manual "derive-now" endpoints and integration tests — does NOT
     * acquire the advisory lock or consult {@code enabled}. Callers
     * that need serialisation should go through {@link #runScheduled}.
     */
    public DerivationResult deriveForUser(Long tenantId, String userId) {
        if (tenantId == null || userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("tenantId + userId required");
        }

        // 0. Honour GDPR tombstone. If the user has forget'd, do not re-derive.
        //    The tombstone is an ARCHIVED row with edited_fields._forgotten = true
        //    (see UserSoulProfileEditor.forgetProfile).
        if (isForgotten(tenantId, userId)) {
            metrics.recordDerivation(tenantId, Outcome.SKIPPED_FORGOTTEN.tag);
            return new DerivationResult(Outcome.SKIPPED_FORGOTTEN, null, null);
        }

        // 1. Inputs.
        List<Map<String, Object>> memories =
                agentMemoryService.loadScopedByImportance(tenantId, userId, "default", 50);
        if (memories.size() < minMemories) {
            metrics.recordDerivation(tenantId, Outcome.SKIPPED_TOO_LITTLE_SIGNAL.tag);
            return new DerivationResult(Outcome.SKIPPED_TOO_LITTLE_SIGNAL, null, null);
        }

        List<Map<String, Object>> actions = loadRecentActions(tenantId, userId, lookBackDays);

        // 2. Project.
        ProjectionResult projection = ProfileProjector.project(memories, actions);
        Map<String, Object> profile = renderProfile(projection);

        // 3. Hash + dedup.
        String newHash = ProfileHasher.hashProfile(profile);
        Map<String, Object> prior = loadActive(tenantId, userId);
        if (prior != null && newHash != null && newHash.equals(prior.get("profile_hash"))) {
            metrics.recordDerivation(tenantId, Outcome.SKIPPED_NO_CHANGE.tag);
            return new DerivationResult(Outcome.SKIPPED_NO_CHANGE, null, newHash);
        }

        // 4. Persist DRAFT (activation is Phase 2).
        int nextVersion = prior == null ? 1 : (((Number) prior.get("version")).intValue() + 1);
        String pid = insertDraft(tenantId, userId, nextVersion, profile, newHash, projection, memories);

        metrics.recordDerivation(tenantId, Outcome.DRAFTED.tag);
        return new DerivationResult(Outcome.DRAFTED, pid, newHash);
    }

    // ---- Helpers ----------------------------------------------------

    /**
     * Tombstone check — a forget'd user has an ARCHIVED row whose
     * {@code edited_fields._forgotten = true}. Inline SQL to avoid a
     * circular dependency on {@code UserSoulProfileEditor}.
     */
    private boolean isForgotten(Long tenantId, String userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_user_soul_profile "
                        + "WHERE tenant_id = ? AND user_id = ? AND status = ? "
                        + "  AND (edited_fields ->> '_forgotten') = 'true'",
                Integer.class, tenantId, userId, UserSoulProfileStatus.ARCHIVED.code());
        return count != null && count > 0;
    }

    private List<Map<String, Object>> loadRecentActions(Long tenantId, String userId, int days) {
        // Action actor_id stores the user that triggered the action. actor_type
        // distinguishes user-driven vs agent-driven; we want the former.
        return jdbcTemplate.queryForList(
                "SELECT pid, action_type, target_model, created_at "
                        + "FROM ab_agent_action "
                        + "WHERE tenant_id = ? AND actor_id = ? "
                        + "  AND actor_type IN ('user', 'human') "
                        + "  AND created_at >= NOW() - (? || ' days')::interval "
                        + "ORDER BY created_at DESC "
                        + "LIMIT 200",
                tenantId, userId, days);
    }

    private Map<String, Object> loadActive(Long tenantId, String userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT pid, version, profile_hash, edited_fields "
                        + "FROM ab_agent_user_soul_profile "
                        + "WHERE tenant_id = ? AND user_id = ? AND status = ? "
                        + "LIMIT 1",
                tenantId, userId, UserSoulProfileStatus.ACTIVE.code());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Render the projection into the JSONB profile shape defined in plan §4.
     * Phase 1: deterministic templates; LLM hook is TODO (Phase 2+).
     */
    Map<String, Object> renderProfile(ProjectionResult projection) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("schema_version", "1.0");

        if (projection.persona() != null) {
            Map<String, Object> persona = new LinkedHashMap<>();
            persona.put("text", projection.persona().text());
            persona.put("source_memory_pids", projection.persona().sourceMemoryPids());
            persona.put("confidence", projection.persona().confidence());
            persona.put("last_derived_at", Instant.ofEpochMilli(
                    projection.persona().lastDerivedAt()).toString());
            profile.put("persona", persona);
        }

        Map<String, Object> preferences = new LinkedHashMap<>();
        for (var p : projection.preferences()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("text", p.text());
            entry.put("source_memory_pids", p.sourceMemoryPids());
            entry.put("confidence", p.confidence());
            preferences.put(p.field(), entry);
        }
        if (!preferences.isEmpty()) profile.put("preferences", preferences);

        if (!projection.habits().isEmpty()) {
            List<Map<String, Object>> habits = new ArrayList<>();
            for (var h : projection.habits()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("pattern", h.pattern());
                row.put("frequency", h.frequency());
                row.put("source_action_count", h.sourceActionCount());
                row.put("last_seen", h.lastSeen());
                habits.add(row);
            }
            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("recurring_actions", habits);
            profile.put("habits", wrap);
        }

        if (!projection.expertise().isEmpty()) {
            List<Map<String, Object>> domains = new ArrayList<>();
            for (var d : projection.expertise()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", d.name());
                row.put("confidence", d.confidence());
                row.put("evidence_count", d.evidenceCount());
                domains.add(row);
            }
            Map<String, Object> wrap = new LinkedHashMap<>();
            wrap.put("domains", domains);
            profile.put("expertise", wrap);
        }

        if (projection.boundaries() != null) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("text", projection.boundaries().text());
            b.put("source_memory_pids", projection.boundaries().sourceMemoryPids());
            b.put("confidence", projection.boundaries().confidence());
            b.put("user_pinned", false);
            profile.put("boundaries", b);
        }

        if (projection.language() != null) {
            profile.put("language", projection.language());
        }

        // meta is excluded from the hash (ProfileHasher), so it's safe to
        // include volatile run info here.
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("derivation_window_days", lookBackDays);
        meta.put("derivation_run_id", UniqueIdGenerator.generate());
        meta.put("llm_enabled", llmEnabled);
        meta.put("derivation_model", DERIVATION_MODEL_TEMPLATE);
        profile.put("meta", meta);

        return profile;
    }

    private String insertDraft(Long tenantId, String userId, int version,
                               Map<String, Object> profile, String hash,
                               ProjectionResult projection,
                               List<Map<String, Object>> memories) {
        String pid = UniqueIdGenerator.generate();
        double confidence = aggregateConfidence(projection);
        List<String> sourcePids = memories.stream()
                .map(m -> (String) m.get("pid"))
                .filter(s -> s != null && !s.isBlank())
                .toList();
        jdbcTemplate.update(
                "INSERT INTO ab_agent_user_soul_profile "
                        + "(pid, tenant_id, user_id, version, status, profile, profile_hash, "
                        + " language_preference, source_memory_pids, source_action_count, "
                        + " source_window_days, derivation_model, derivation_confidence, "
                        + " created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?, ?, ?, ?, NOW())",
                pid, tenantId, userId, version, UserSoulProfileStatus.DRAFT.code(),
                toJson(profile), hash,
                projection.language(),
                toJson(sourcePids),
                0, lookBackDays, DERIVATION_MODEL_TEMPLATE, confidence);
        return pid;
    }

    private static double aggregateConfidence(ProjectionResult p) {
        List<Double> parts = new ArrayList<>();
        if (p.persona() != null) parts.add(p.persona().confidence());
        for (var pref : p.preferences()) parts.add(pref.confidence());
        if (p.boundaries() != null) parts.add(p.boundaries().confidence());
        if (parts.isEmpty()) return 0.0;
        double[] arr = parts.stream().mapToDouble(Double::doubleValue).toArray();
        return ProfileConfidenceScorer.aggregateMin(arr);
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("canonical json serialisation failed", e);
        }
    }

    // PGobject kept available for future typed JSONB inserts; unused in Phase 1
    // since string casts via ?::jsonb are simpler and sufficient.
    @SuppressWarnings("unused")
    private static PGobject jsonb(String json) throws SQLException {
        PGobject o = new PGobject();
        o.setType("jsonb");
        o.setValue(json);
        return o;
    }
}
