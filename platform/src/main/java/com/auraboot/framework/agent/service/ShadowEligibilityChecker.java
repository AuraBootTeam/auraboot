package com.auraboot.framework.agent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shadow safety pre-gate (design/learning-loop.md §6.0).
 *
 * Decides whether a {@link SkillDraftGenerator}-produced draft is safe to
 * shadow. Read-type Actions and prompt-only reasoning run directly. Writes
 * consult the {@link DryRunSupportRegistry} per tool_ref: ALL tools FULL
 * → direct; ALL FULL or SIMULATED → dry-run; any NONE → ineligible.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShadowEligibilityChecker {

    public enum Eligibility {
        ELIGIBLE_DIRECT,
        ELIGIBLE_DRY_RUN,
        INELIGIBLE_NO_DRY_RUN_SUPPORT,
        INELIGIBLE_CODE_SIDE_EFFECT_UNKNOWN,
        NOT_FOUND
    }

    private final JdbcTemplate jdbcTemplate;
    private final DryRunSupportRegistry dryRunRegistry;

    public Eligibility check(String draftPid) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT tenant_id, contract_yaml, status FROM ab_agent_skill_draft WHERE pid = ?", draftPid);
        if (rows.isEmpty()) return Eligibility.NOT_FOUND;
        Long tenantId = ((Number) rows.get(0).get("tenant_id")).longValue();
        String yaml = (String) rows.get(0).get("contract_yaml");
        return classify(tenantId, yaml);
    }

    /** Exposed for testing callers that already have the YAML. */
    public Eligibility classify(Long tenantId, String yaml) {
        if (yaml == null) return Eligibility.INELIGIBLE_NO_DRY_RUN_SUPPORT;
        String substrate = parseLine(yaml, "substrate:");
        String actionType = parseLine(yaml, "action_type:");

        if (isReadAction(actionType)) return Eligibility.ELIGIBLE_DIRECT;
        if ("prompt".equals(substrate)) return Eligibility.ELIGIBLE_DIRECT;
        if ("code".equals(substrate)) return Eligibility.INELIGIBLE_CODE_SIDE_EFFECT_UNKNOWN;

        List<String> toolRefs = parseToolRefs(yaml);
        if (toolRefs.isEmpty()) {
            // Write substrate with no tool_refs declared — can't verify safety.
            return Eligibility.INELIGIBLE_NO_DRY_RUN_SUPPORT;
        }

        boolean anySimulated = false;
        for (String toolRef : toolRefs) {
            DryRunSupportRegistry.SupportLevel level = dryRunRegistry.lookup(tenantId, toolRef);
            if (level == DryRunSupportRegistry.SupportLevel.NONE) {
                return Eligibility.INELIGIBLE_NO_DRY_RUN_SUPPORT;
            }
            if (level == DryRunSupportRegistry.SupportLevel.SIMULATED) {
                anySimulated = true;
            }
        }
        return anySimulated ? Eligibility.ELIGIBLE_DRY_RUN : Eligibility.ELIGIBLE_DIRECT;
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

    private List<String> parseToolRefs(String yaml) {
        List<String> out = new ArrayList<>();
        boolean inBlock = false;
        for (String line : yaml.split("\n")) {
            if (line.startsWith("tool_refs:")) { inBlock = true; continue; }
            if (!inBlock) continue;
            // End of block when we hit a non-indented non-blank line
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))) break;
            String t = line.trim();
            if (t.startsWith("- ")) {
                out.add(t.substring(2).trim());
            }
        }
        return out;
    }
}
