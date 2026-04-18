package com.auraboot.framework.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * ACP Learning Loop — driver for Shadow Mode execution (§6.1).
 *
 * Previously {@link ShadowExecutor} had no caller. This scheduler picks
 * drafts in {@code REVIEWED_OK} / {@code SHADOW_RUNNING} status and, for
 * each, replays a handful of the original runs the draft was derived
 * from (via {@code derived_from_runs}). Each replay records one row in
 * {@code ab_agent_shadow_run} which the {@link PromotionEvaluator} then
 * consumes.
 *
 * Idempotency: joins {@code ab_agent_shadow_run} so already-shadowed
 * (draft_id, original_run_id) pairs are skipped. On the first successful
 * shadow of a draft, its status flips from {@code REVIEWED_OK} →
 * {@code SHADOW_RUNNING}.
 *
 * Disabled by default; the enterprise operator opts in per-environment.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShadowRunScheduler {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ShadowExecutor shadowExecutor;

    @Value("${acp.learning.shadow.scheduler.enabled:false}")
    private boolean enabled;

    @Value("${acp.learning.shadow.scheduler.max-drafts-per-tick:20}")
    private int maxDraftsPerTick;

    @Value("${acp.learning.shadow.scheduler.max-runs-per-draft:3}")
    private int maxRunsPerDraft;

    @Scheduled(cron = "${acp.learning.shadow.scheduler.cron:0 */10 * * * *}")
    public void runScheduled() {
        if (!enabled) return;
        int executed = runOnce();
        if (executed > 0) {
            log.info("ShadowRunScheduler: recorded {} shadow runs", executed);
        }
    }

    /** @return number of shadow runs recorded across all drafts this pass. */
    public int runOnce() {
        List<Map<String, Object>> drafts = jdbcTemplate.queryForList(
                "SELECT pid, derived_from_runs::text AS derived_json, status " +
                        "FROM ab_agent_skill_draft " +
                        "WHERE status IN ('REVIEWED_OK', 'SHADOW_RUNNING') " +
                        "ORDER BY reviewed_at ASC NULLS LAST, created_at ASC " +
                        "LIMIT ?",
                Math.max(1, Math.min(maxDraftsPerTick, 200)));

        int totalExecuted = 0;
        for (Map<String, Object> d : drafts) {
            String draftPid = (String) d.get("pid");
            String currentStatus = (String) d.get("status");
            String derivedJson = (String) d.get("derived_json");
            List<String> runIds = parseRunIds(derivedJson);
            if (runIds.isEmpty()) continue;

            int executedForDraft = 0;
            for (String runId : runIds) {
                if (executedForDraft >= maxRunsPerDraft) break;
                if (alreadyShadowed(draftPid, runId)) continue;

                Map<String, Object> origin = loadOriginalRun(runId);
                if (origin == null) continue;

                ShadowExecutor.ExecutionRequest req = ShadowExecutor.ExecutionRequest.builder()
                        .draftPid(draftPid)
                        .originalRunId(runId)
                        .originalOutputHash((String) origin.get("output_hash"))
                        .originalDurationMs(origin.get("duration_ms") == null ? null :
                                ((Number) origin.get("duration_ms")).longValue())
                        .originalStatus((String) origin.get("status"))
                        .args(Map.of())      // invocation args not reconstructed in this PR
                        .build();
                try {
                    ShadowExecutor.ExecutionResult r = shadowExecutor.execute(req);
                    if ("executed".equals(r.getOutcome())) {
                        executedForDraft++;
                        totalExecuted++;
                    }
                } catch (Exception e) {
                    log.warn("ShadowRunScheduler: executor failed draft={} run={}: {}",
                            draftPid, runId, e.getMessage());
                }
            }

            if (executedForDraft > 0 && "REVIEWED_OK".equals(currentStatus)) {
                jdbcTemplate.update(
                        "UPDATE ab_agent_skill_draft " +
                                "SET status = 'SHADOW_RUNNING', " +
                                "    shadow_started_at = COALESCE(shadow_started_at, NOW()) " +
                                "WHERE pid = ? AND status = 'REVIEWED_OK'",
                        draftPid);
            }
        }
        return totalExecuted;
    }

    private List<String> parseRunIds(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> arr = objectMapper.readValue(json, new TypeReference<>() {});
            return arr.stream()
                    .map(m -> m.get("run_id"))
                    .filter(v -> v instanceof String)
                    .map(Object::toString)
                    .toList();
        } catch (Exception e) {
            log.debug("ShadowRunScheduler: malformed derived_from_runs json: {}", e.getMessage());
            return List.of();
        }
    }

    private boolean alreadyShadowed(String draftPid, String originalRunId) {
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_shadow_run WHERE draft_id = ? AND original_run_id = ?",
                Integer.class, draftPid, originalRunId);
        return cnt != null && cnt > 0;
    }

    private Map<String, Object> loadOriginalRun(String runId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT action_status AS status, " +
                        "  EXTRACT(EPOCH FROM (updated_at - executed_at)) * 1000 AS duration_ms, " +
                        "  md5(COALESCE(after_snapshot::text, '')) AS output_hash " +
                        "FROM ab_agent_action WHERE run_id = ? LIMIT 1",
                runId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
