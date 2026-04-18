package com.auraboot.framework.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Shadow safety pre-gate (design/learning-loop.md §6.0).
 *
 * Decides whether a {@link SkillDraftGenerator}-produced draft is safe
 * to shadow: read-only / reasoning drafts run directly; write drafts
 * need dry-run support per ToolRef; code drafts need sandbox dry-run.
 *
 * Minimal v0 — infers read-only vs. write from the draft's {@code substrate}
 * + {@code action_type} fields parsed from {@code contract_yaml}. Per-
 * ToolRef dry-run registry lands later (not wired yet; write drafts
 * default to INELIGIBLE_NO_DRY_RUN_SUPPORT until that registry exists).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShadowEligibilityChecker {

    public enum Eligibility {
        /** Read-only / reasoning — shadow execution is safe to run directly. */
        ELIGIBLE_DIRECT,
        /** Write but every ToolRef supports dry-run (future). */
        ELIGIBLE_DRY_RUN,
        /** No dry-run support — must skip shadow, go straight to reinforced human gate. */
        INELIGIBLE_NO_DRY_RUN_SUPPORT,
        /** Code substrate without sandbox dry-run. */
        INELIGIBLE_CODE_SIDE_EFFECT_UNKNOWN,
        /** Draft not found. */
        NOT_FOUND
    }

    private final JdbcTemplate jdbcTemplate;

    public Eligibility check(String draftPid) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT contract_yaml, status FROM ab_agent_skill_draft WHERE pid = ?", draftPid);
        if (rows.isEmpty()) return Eligibility.NOT_FOUND;
        String yaml = (String) rows.get(0).get("contract_yaml");
        return classifyFromYaml(yaml);
    }

    /** Exposed for testing callers that already have the YAML. */
    public Eligibility classifyFromYaml(String yaml) {
        if (yaml == null) return Eligibility.INELIGIBLE_NO_DRY_RUN_SUPPORT;
        String substrate = parseLine(yaml, "substrate:");
        String actionType = parseLine(yaml, "action_type:");

        // Read-type Actions have no side effect → safe to shadow directly.
        if (isReadAction(actionType)) return Eligibility.ELIGIBLE_DIRECT;

        // Prompt substrate pure reasoning → safe.
        if ("prompt".equals(substrate)) return Eligibility.ELIGIBLE_DIRECT;

        // Code substrate without sandbox dry-run is unsafe.
        if ("code".equals(substrate)) return Eligibility.INELIGIBLE_CODE_SIDE_EFFECT_UNKNOWN;

        // Writes (dsl / api / mcp) — dry-run registry not wired yet,
        // so reject conservatively. Upgrading this to ELIGIBLE_DRY_RUN
        // will happen when per-ToolRef dry-run support lands.
        return Eligibility.INELIGIBLE_NO_DRY_RUN_SUPPORT;
    }

    private boolean isReadAction(String actionType) {
        if (actionType == null) return false;
        return switch (actionType) {
            case "query", "read", "analyze", "summarize", "report", "explain", "compare" -> true;
            default -> false;
        };
    }

    private String parseLine(String yaml, String prefix) {
        for (String line : yaml.split("\n")) {
            String t = line.trim();
            if (t.startsWith(prefix)) {
                return t.substring(prefix.length()).trim();
            }
        }
        return null;
    }
}
