package com.auraboot.framework.agent.memory.extraction;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spike-4 LLM extraction rule pre-filter — Phase 1 skeleton.
 *
 * <p>Disabled by default. Phase 2 wiring (read history from
 * {@code ab_agent_run + ab_agent_observation}, replay through rule-prefilter
 * only, diff against historical LLM-extracted memory rows) is out of scope
 * for this PR.
 *
 * <p>Phase 1 (this PR) verifies:
 * <ul>
 *   <li>Pattern catalog JSON loads from classpath</li>
 *   <li>{@link ExtractionRuleMatcher} unit tests pass (see {@link ExtractionRuleMatcherTest})</li>
 * </ul>
 *
 * <p>Phase 2 will:
 * <ol>
 *   <li>Promote to {@code @SpringBootTest} OR Python replay tool (TBD)</li>
 *   <li>Pull N=100 historical runs from {@code ab_agent_run} with their
 *       linked memory rows via {@code source_run_id}</li>
 *   <li>For each run: rebuild ExtractionSignal list from BIF / tool calls /
 *       BPM events / artifacts (recorded in {@code ab_agent_observation})</li>
 *   <li>Replay through {@link ExtractionRuleMatcher#match}</li>
 *   <li>Diff candidates vs historical LLM-extracted rows: compute
 *       Coverage / Recall vs LLM / Precision vs LLM / Savings</li>
 *   <li>Emit replay-&lt;ts&gt;.json + report-&lt;ts&gt;.md to
 *       {@code auraboot-enterprise/docs/system-reference/runtime-traces/extraction-prefilter/}</li>
 * </ol>
 *
 * <p>Run: {@code ./gradlew :platform:test --tests '*ExtractionPrefilterHarness*' -PextractionPrefilter=true}
 *
 * @see <a href="../../../../../../docs/backlog/2026-05-28-spike-4-extraction-rule-prefilter-design.md">design doc</a>
 */
@Tag("extraction-prefilter")
@DisplayName("Spike-4 LLM extraction rule pre-filter (Phase 1 — resources only)")
class ExtractionPrefilterHarness {

    private static final String PATTERNS_RESOURCE = "/extraction-prefilter/patterns.json";

    @Test
    @DisplayName("Phase 1: patterns.json loads and declares ≥ 6 deterministic patterns")
    void patternsCatalogLoads() throws IOException {
        String json = loadResource(PATTERNS_RESOURCE);
        assertNotNull(json);
        // 7 patterns p1-p7 per design doc §3.1
        for (String pid : new String[]{
                "p1-tool-record-user-preference",
                "p2-tool-success-with-record-id",
                "p3-tool-failure",
                "p4-bpm-task-assigned",
                "p5-bpm-task-completed",
                "p6-state-transition",
                "p7-approval-decision"}) {
            assertTrue(json.contains(pid), "missing pattern: " + pid);
        }
        // Memory-type vocabulary cross-check
        for (String t : new String[]{"FACT", "LESSON", "PREFERENCE", "DECISION"}) {
            assertTrue(json.contains(t), "missing memory_type: " + t);
        }
    }

    @Test
    @Disabled("Phase 2 — needs historical run + observation data + Spring context (or Python). See design doc §5.")
    @DisplayName("Phase 2: replay 100 runs through matcher, diff against LLM-extracted memories")
    void replayHistoricalRuns() {
        // Phase 2 implementation:
        //   1. Pull N runs from ab_agent_run (last 30 days, status='completed')
        //   2. For each: rebuild signal list from
        //        a. ab_agent_observation events of type tool_call / tool_response / bpm_event
        //        b. result.lastResponse JSON parsing for tool_response fields
        //   3. Replay → List<ExtractedMemoryCandidate>
        //   4. Pull historical memories via source_run_id = run.pid
        //   5. Diff:
        //        Coverage = % runs where rule-prefilter produced ≥ 1 candidate
        //        Recall vs LLM = candidates matching LLM-extracted memory (title/content sim ≥ 0.6) / LLM-extracted count
        //        Precision vs LLM = candidates that match LLM / total candidates
        //   6. Cost-savings extrapolation: coverage × avg_extraction_call_cost
        //   7. Emit replay-<ts>.json + report-<ts>.md
        throw new UnsupportedOperationException("Phase 2 stub — see design doc §5");
    }

    private String loadResource(String path) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) throw new IOException("classpath resource not found: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
