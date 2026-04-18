package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.metrics.LearningLoopMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Shadow Mode Executor (design/learning-loop.md §6).
 *
 * Orchestrates one shadow run: eligibility gate → iterate tool_refs →
 * dispatch to registered {@link ShadowToolInvoker}s → hash the combined
 * result → hand to {@link ShadowRunner#recordRun}.
 *
 * Scope (this PR): the executor wires the registry/eligibility/runner
 * chain and plugs in a substrate-invoker extension point. No built-in
 * invokers ship here — substrates (NamedQuery, DSL command dry-run, MCP)
 * register their own beans.  When no invoker claims a tool_ref, the
 * executor records a skip rather than invoking anything.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShadowExecutor {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ShadowEligibilityChecker eligibilityChecker;
    private final ShadowRunner shadowRunner;
    private final List<ShadowToolInvoker> invokers;

    @Autowired(required = false)
    private LearningLoopMetrics metrics;

    @Data
    @Builder
    public static class ExecutionRequest {
        private String draftPid;
        private String originalRunId;
        private String originalOutputHash;
        private Long originalDurationMs;
        private String originalStatus;
        private Map<String, Object> args;        // inputs to replay
    }

    @Data
    @Builder
    public static class ExecutionResult {
        private String shadowRunPid;             // null if skipped before persist
        private String outcome;                   // executed | skipped_ineligible | skipped_not_found
        private ShadowEligibilityChecker.Eligibility eligibility;
    }

    public ExecutionResult execute(ExecutionRequest req) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT tenant_id, contract_yaml FROM ab_agent_skill_draft WHERE pid = ?", req.draftPid);
        if (rows.isEmpty()) {
            log.warn("ShadowExecutor: draft {} not found", req.draftPid);
            if (metrics != null) metrics.recordShadowRunOutcome(null, "skipped_not_found");
            return ExecutionResult.builder()
                    .outcome("skipped_not_found")
                    .eligibility(ShadowEligibilityChecker.Eligibility.NOT_FOUND).build();
        }
        Long tenantId = ((Number) rows.get(0).get("tenant_id")).longValue();
        String yaml = (String) rows.get(0).get("contract_yaml");

        ShadowEligibilityChecker.Eligibility eligibility = eligibilityChecker.classify(tenantId, yaml);
        if (eligibility == ShadowEligibilityChecker.Eligibility.INELIGIBLE_NO_DRY_RUN_SUPPORT
                || eligibility == ShadowEligibilityChecker.Eligibility.INELIGIBLE_CODE_SIDE_EFFECT_UNKNOWN) {
            log.info("ShadowExecutor: draft {} ineligible ({}), skipping shadow", req.draftPid, eligibility);
            if (metrics != null) metrics.recordShadowRunOutcome(tenantId, "skipped_ineligible");
            return ExecutionResult.builder()
                    .outcome("skipped_ineligible").eligibility(eligibility).build();
        }

        List<String> toolRefs = parseToolRefs(yaml);
        long startMs = System.currentTimeMillis();
        List<Map<String, Object>> results = new ArrayList<>();
        String shadowStatus = "success";
        for (String toolRef : toolRefs) {
            ShadowToolInvoker invoker = findInvoker(toolRef);
            if (invoker == null) {
                log.info("ShadowExecutor: no invoker for tool_ref={} on draft {} — recording as skipped",
                        toolRef, req.draftPid);
                shadowStatus = "skipped";
                results.add(Map.of("tool_ref", toolRef, "status", "no_invoker"));
                continue;
            }
            try {
                Map<String, Object> r = invoker.invokeShadow(tenantId, toolRef, req.args);
                results.add(Map.of("tool_ref", toolRef, "result", r == null ? Map.of() : r));
            } catch (Exception e) {
                log.warn("ShadowExecutor: invoker failed for tool_ref={}: {}", toolRef, e.getMessage());
                shadowStatus = "failed";
                results.add(Map.of("tool_ref", toolRef, "error", e.getMessage() == null ? "" : e.getMessage()));
            }
        }
        long elapsed = System.currentTimeMillis() - startMs;

        String shadowHash = hashCanonical(results);
        Boolean outputMatch = req.originalOutputHash != null && req.originalOutputHash.equals(shadowHash);

        ShadowRunner.ShadowOutcome outcome = ShadowRunner.ShadowOutcome.builder()
                .tenantId(tenantId)
                .draftPid(req.draftPid)
                .originalRunId(req.originalRunId)
                .shadowStatus(shadowStatus)
                .shadowDurationMs(elapsed)
                .shadowOutputHash(shadowHash)
                .originalStatus(req.originalStatus)
                .originalDurationMs(req.originalDurationMs)
                .originalOutputHash(req.originalOutputHash)
                .outputMatch(outputMatch)
                .fidelityMatch(Boolean.TRUE)
                .build();
        String pid = shadowRunner.recordRun(outcome);
        if (metrics != null) metrics.recordShadowRunOutcome(tenantId, "executed");

        return ExecutionResult.builder()
                .shadowRunPid(pid).outcome("executed").eligibility(eligibility).build();
    }

    private ShadowToolInvoker findInvoker(String toolRef) {
        for (ShadowToolInvoker i : invokers) {
            if (i.supports(toolRef)) return i;
        }
        return null;
    }

    private String hashCanonical(Object payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(json.getBytes()));
        } catch (Exception e) {
            log.debug("hashCanonical failed: {}", e.getMessage());
            return "";
        }
    }

    private List<String> parseToolRefs(String yaml) {
        if (yaml == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        boolean inBlock = false;
        for (String line : yaml.split("\n")) {
            if (line.startsWith("tool_refs:")) { inBlock = true; continue; }
            if (!inBlock) continue;
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))) break;
            String t = line.trim();
            if (t.startsWith("- ")) out.add(t.substring(2).trim());
        }
        return out;
    }
}
