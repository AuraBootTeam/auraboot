package com.auraboot.framework.agent.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ACP Learning Loop — Phase 2 (design/learning-loop.md §4).
 *
 * Consumes {@code ab_agent_learning_pattern} rows where status='OBSERVED' and
 * {@code draft_skill_id IS NULL}, synthesizes a Skill contract (YAML), and
 * inserts an {@code ab_agent_skill_draft} row with
 * status='DRAFT_PENDING_REVIEW'. The pattern row is then flipped to
 * status='DRAFT_GENERATED' and draft_skill_id set — the extractor re-run
 * skips already-drafted patterns.
 *
 * Deterministic v0 — §4.2 substrate rules + §4.5 tool_refs discovery are
 * implemented; §4.6 LLM-based Namer is deferred (uses a hash-derived code
 * for now). This is enough to seed the HITL review queue; humans rename
 * and refine before promotion.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillDraftGenerator {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final FidelityGrader fidelityGrader;

    /**
     * Scan OBSERVED patterns, produce drafts for each. Returns the number of
     * drafts created.
     */
    public int generateDrafts() {
        List<PatternRow> patterns = loadPatternsAwaitingDraft();
        int created = 0;
        for (PatternRow p : patterns) {
            try {
                if (createDraftFor(p)) created++;
            } catch (Exception e) {
                log.warn("Failed to generate draft for pattern pid={}: {}", p.pid, e.getMessage());
            }
        }
        log.info("Learning Loop — SkillDraftGenerator: {} patterns scanned, {} drafts created",
                patterns.size(), created);
        return created;
    }

    /**
     * Generate a draft for a specific pattern pid — testing / manual trigger.
     * Returns the draft pid, or null if the pattern was already drafted or
     * not eligible.
     */
    public String generateDraftFor(String patternPid) {
        PatternRow p = loadPatternByPid(patternPid);
        if (p == null) return null;
        if (p.draftSkillId != null) {
            log.debug("Pattern {} already has draft {}", patternPid, p.draftSkillId);
            return p.draftSkillId;
        }
        createDraftFor(p);
        // Re-read to get the new draft id.
        PatternRow refreshed = loadPatternByPid(patternPid);
        return refreshed == null ? null : refreshed.draftSkillId;
    }

    // =========================================================================

    private boolean createDraftFor(PatternRow p) {
        // Resolve decision-surface details from the pattern_signature.
        Map<String, Object> sig = parseSignature(p.patternSignatureJson);
        String commandSig = strOr(sig.get("command_signature"), "unknown");
        String targetModel = strOr(sig.get("target_model"), "unknown");
        String actionType  = strOr(sig.get("action_type"), "unknown");

        // §4.2 substrate rules — infer from the dominant Action fidelity
        // observed for this pattern. Defaults to 'dsl' for full-fidelity
        // writes, 'api' for semantic, 'code' for blackbox.
        String substrate = chooseSubstrate(p.tenantId, commandSig);

        // §4.5 tool_refs — every distinct tool_ref observed for Actions
        // matching this pattern. Ordered by frequency.
        List<String> toolRefs = collectToolRefs(p.tenantId, commandSig, targetModel, actionType);

        // Deterministic name v0: "auto.{model}_{action_type}_{pattern_hash_prefix}"
        // HITL renames this before promotion — we just need something unique
        // and traceable back to the pattern.
        String draftCode = buildDraftCode(targetModel, actionType, p.patternHash);

        String yaml = renderYaml(draftCode, substrate, targetModel, actionType, toolRefs, p);
        String contractHash = sha256Hex(yaml);

        List<Map<String, Object>> derivedFromRuns = sampleRunRefs(p.tenantId, commandSig, targetModel, actionType);
        String derivedFromRunsJson;
        try {
            derivedFromRunsJson = objectMapper.writeValueAsString(derivedFromRuns);
        } catch (Exception e) {
            derivedFromRunsJson = "[]";
        }

        String draftPid = UniqueIdGenerator.generate();
        int inserted = jdbcTemplate.update(
                "INSERT INTO ab_agent_skill_draft " +
                        "(pid, tenant_id, draft_skill_code, contract_yaml, contract_hash, " +
                        " source_pattern_hash, derived_from_runs, status) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, 'DRAFT_PENDING_REVIEW')",
                draftPid, p.tenantId, draftCode, yaml, contractHash,
                p.patternHash, derivedFromRunsJson);

        if (inserted != 1) {
            log.warn("Draft insert returned {} for pattern {}", inserted, p.pid);
            return false;
        }

        // Link pattern → draft + flip status. idempotent-guarded by draft_skill_id IS NULL.
        int updated = jdbcTemplate.update(
                "UPDATE ab_agent_learning_pattern " +
                        "SET status = 'DRAFT_GENERATED', draft_skill_id = ?, updated_at = NOW() " +
                        "WHERE pid = ? AND draft_skill_id IS NULL",
                draftPid, p.pid);
        if (updated != 1) {
            log.warn("Pattern {} concurrently updated; draft {} may be orphaned", p.pid, draftPid);
        }

        log.info("SkillDraft created: draft_pid={} code={} substrate={} tools={} from pattern={}",
                draftPid, draftCode, substrate, toolRefs.size(), p.pid);
        return true;
    }

    private String chooseSubstrate(Long tenantId, String commandSig) {
        // Pick the most common fidelity among Actions with this command_signature.
        // Fall back to 'dsl' when the pattern came from a mix without a
        // dominant substrate.
        String dominantFidelity = jdbcTemplate.query(
                "SELECT fidelity, COUNT(*) AS n FROM ab_agent_action " +
                        "WHERE tenant_id = ? AND command_signature = ? " +
                        "  AND fidelity IS NOT NULL " +
                        "GROUP BY fidelity ORDER BY n DESC LIMIT 1",
                rs -> rs.next() ? rs.getString("fidelity") : null,
                tenantId, commandSig);
        if (FidelityGrader.FIDELITY_FULL.equals(dominantFidelity)) return "dsl";
        if (FidelityGrader.FIDELITY_SEMANTIC.equals(dominantFidelity)) return "api";
        if (FidelityGrader.FIDELITY_BLACKBOX.equals(dominantFidelity)) return "code";
        return "dsl";
    }

    private List<String> collectToolRefs(Long tenantId, String commandSig,
                                          String targetModel, String actionType) {
        return jdbcTemplate.queryForList(
                "SELECT tool_ref FROM ab_agent_action " +
                        "WHERE tenant_id = ? AND command_signature = ? " +
                        "  AND target_model = ? AND action_type = ? " +
                        "  AND tool_ref IS NOT NULL " +
                        "GROUP BY tool_ref ORDER BY COUNT(*) DESC LIMIT 5",
                String.class, tenantId, commandSig, targetModel, actionType);
    }

    private List<Map<String, Object>> sampleRunRefs(Long tenantId, String commandSig,
                                                     String targetModel, String actionType) {
        List<String> runIds = jdbcTemplate.queryForList(
                "SELECT DISTINCT run_id FROM ab_agent_action " +
                        "WHERE tenant_id = ? AND command_signature = ? " +
                        "  AND target_model = ? AND action_type = ? " +
                        "ORDER BY run_id DESC LIMIT 5",
                String.class, tenantId, commandSig, targetModel, actionType);
        List<Map<String, Object>> out = new ArrayList<>(runIds.size());
        for (String id : runIds) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("run_id", id);
            out.add(m);
        }
        return out;
    }

    private String buildDraftCode(String targetModel, String actionType, String patternHash) {
        // Keep it within VARCHAR(128): "auto.{model}_{action}.{hash_prefix}"
        String prefix = patternHash == null ? "noref" : patternHash.substring(0, Math.min(12, patternHash.length()));
        return "auto." + targetModel + "_" + actionType + "." + prefix;
    }

    /**
     * Minimal deterministic YAML. Grows when LLM Namer + §4.3 Input Schema
     * induction land.
     */
    private String renderYaml(String draftCode, String substrate, String targetModel,
                               String actionType, List<String> toolRefs, PatternRow p) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Auto-generated Skill draft (Learning Loop §4)\n");
        sb.append("skill_code: ").append(draftCode).append('\n');
        sb.append("substrate: ").append(substrate).append('\n');
        sb.append("target_model: ").append(targetModel).append('\n');
        sb.append("action_type: ").append(actionType).append('\n');
        sb.append("source_pattern_hash: ").append(p.patternHash).append('\n');
        sb.append("invocation_count: ").append(p.invocationCount).append('\n');
        sb.append("success_rate: ").append(String.format("%.2f", p.successRate)).append('\n');
        sb.append("tool_refs:\n");
        if (toolRefs.isEmpty()) {
            sb.append("  []\n");
        } else {
            for (String t : toolRefs) sb.append("  - ").append(t).append('\n');
        }
        sb.append("description: |\n");
        sb.append("  Auto-derived from ").append(p.invocationCount)
                .append(" successful invocations of ").append(targetModel)
                .append(".").append(actionType).append(". Review before promotion.\n");
        return sb.toString();
    }

    private Map<String, Object> parseSignature(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<PatternRow> loadPatternsAwaitingDraft() {
        return jdbcTemplate.query(
                "SELECT pid, tenant_id, pattern_hash, pattern_signature::text AS sig_json, " +
                        "  invocation_count, success_rate, status, draft_skill_id " +
                        "FROM ab_agent_learning_pattern " +
                        "WHERE status = 'OBSERVED' AND draft_skill_id IS NULL " +
                        "ORDER BY invocation_count DESC LIMIT 100",
                this::mapPattern);
    }

    private PatternRow loadPatternByPid(String pid) {
        List<PatternRow> rows = jdbcTemplate.query(
                "SELECT pid, tenant_id, pattern_hash, pattern_signature::text AS sig_json, " +
                        "  invocation_count, success_rate, status, draft_skill_id " +
                        "FROM ab_agent_learning_pattern WHERE pid = ?",
                this::mapPattern, pid);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private PatternRow mapPattern(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        PatternRow p = new PatternRow();
        p.pid = rs.getString("pid");
        p.tenantId = rs.getObject("tenant_id", Long.class);
        p.patternHash = rs.getString("pattern_hash");
        p.patternSignatureJson = rs.getString("sig_json");
        p.invocationCount = rs.getLong("invocation_count");
        p.successRate = rs.getDouble("success_rate");
        p.status = rs.getString("status");
        p.draftSkillId = rs.getString("draft_skill_id");
        return p;
    }

    private static String strOr(Object o, String fallback) {
        return o == null ? fallback : String.valueOf(o);
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Internal row shape for pattern lookups. */
    private static class PatternRow {
        String pid;
        Long tenantId;
        String patternHash;
        String patternSignatureJson;
        long invocationCount;
        double successRate;
        String status;
        String draftSkillId;
    }
}
