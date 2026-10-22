package com.auraboot.framework.agent.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * ACP Learning Loop — Phase 4 (design/learning-loop.md §6). Records one
 * shadow-mode comparison between a draft and a live run.
 *
 * This is the persistence + comparison layer. Actually dispatching the
 * shadow execution (dry-run vs. live) requires the
 * {@link ShadowEligibilityChecker} pre-gate plus per-substrate dry-run
 * machinery (CommandPipeline dry-run, MCP annotations, Code sandbox
 * read-only tokens) that is outside the scope of this PR — callers that
 * already have both the original {@code SkillResult} and a simulated
 * shadow result invoke {@link #recordRun} to persist the comparison.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShadowRunner {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * Persist one shadow-run comparison.
     * @return the generated pid of the new ab_agent_shadow_run row
     */
    public String recordRun(ShadowOutcome outcome) {
        String pid = UniqueIdGenerator.generate();
        String outputDiffJson = null;
        try {
            if (outcome.outputDiff != null) {
                outputDiffJson = objectMapper.writeValueAsString(outcome.outputDiff);
            }
        } catch (Exception e) {
            log.debug("Failed to serialize output_diff for shadow run of draft {}: {}",
                    outcome.draftPid, e.getMessage());
        }

        jdbcTemplate.update(
                "INSERT INTO ab_agent_shadow_run " +
                        "(pid, tenant_id, draft_id, original_run_id, " +
                        " shadow_status, shadow_duration_ms, shadow_cost_usd, shadow_tokens, shadow_output_hash, " +
                        " original_status, original_duration_ms, original_cost_usd, original_output_hash, " +
                        " output_match, output_diff, fidelity_match) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
                pid, outcome.tenantId, outcome.draftPid, outcome.originalRunId,
                outcome.shadowStatus, outcome.shadowDurationMs, outcome.shadowCostUsd,
                outcome.shadowTokens, outcome.shadowOutputHash,
                outcome.originalStatus, outcome.originalDurationMs, outcome.originalCostUsd,
                outcome.originalOutputHash,
                outcome.outputMatch, outputDiffJson, outcome.fidelityMatch);

        log.info("Shadow run recorded: draft={} original_run={} match={} fidelity_match={}",
                outcome.draftPid, outcome.originalRunId, outcome.outputMatch, outcome.fidelityMatch);
        return pid;
    }

    /**
     * Outcome of a single shadow comparison. All metrics optional except
     * the structural fields (tenantId, draftPid, originalRunId).
     */
    @Data
    @Builder
    public static class ShadowOutcome {
        private Long tenantId;
        private String draftPid;
        private String originalRunId;

        private String shadowStatus;          // success | failed | timeout
        private Long shadowDurationMs;
        private java.math.BigDecimal shadowCostUsd;
        private Integer shadowTokens;
        private String shadowOutputHash;      // SHA-256 of shadow result json

        private String originalStatus;
        private Long originalDurationMs;
        private java.math.BigDecimal originalCostUsd;
        private String originalOutputHash;    // SHA-256 of original result json

        private Boolean outputMatch;          // shadowOutputHash == originalOutputHash
        private Map<String, Object> outputDiff;
        private Boolean fidelityMatch;        // shadow Action fidelity ≥ original fidelity
    }
}
