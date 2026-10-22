package com.auraboot.framework.agent.memory.audit;

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
 * Spike-2 Memory prompt 装配审计 — Phase 1 skeleton.
 *
 * <p>Disabled by default. Phase 2 wiring (anonymized PG snapshot + JdbcTemplate +
 * annotation-loading pipeline) is out of scope for the initial scaffold PR.
 *
 * <p>Phase 1 (this PR) verifies:
 * <ul>
 *   <li>{@code audit-queries.sql} is loadable from classpath</li>
 *   <li>{@code annotation.schema.json} is loadable from classpath</li>
 *   <li>{@link ConflictMetrics} unit tests pass (see {@link ConflictMetricsTest})</li>
 * </ul>
 *
 * <p>Phase 2 will:
 * <ol>
 *   <li>Promote to {@code @SpringBootTest} with read-only PG profile</li>
 *   <li>Execute Q1-Q4 against anonymized snapshot</li>
 *   <li>Emit {@code prompt-segments-<ts>.json} + {@code extraction-volume-<ts>.json}
 *       + {@code dedupe-proxy-<ts>.json}</li>
 *   <li>Wait for human reviewer to produce {@code annotations-<ts>.json}</li>
 *   <li>Load annotations + compute distribution / conflict rate / threshold</li>
 *   <li>Emit {@code report-<ts>.md} with B1/B2/B3 recommendation</li>
 *   <li>Data lands in {@code auraboot-enterprise/docs/system-reference/runtime-traces/memory-audit/}</li>
 * </ol>
 *
 * <p>Run: {@code ./gradlew :platform:test --tests '*MemoryPromptAuditHarness*' -PmemoryAudit=true}
 * (Phase 2 — currently @Disabled).
 *
 * @see <a href="../../../../../../docs/backlog/2026-05-27-spike-2-memory-prompt-audit-design.md">design doc</a>
 */
@Tag("memory-audit")
@DisplayName("Spike-2 Memory prompt 装配 audit (Phase 1 — resources only)")
class MemoryPromptAuditHarness {

    private static final String SQL_RESOURCE = "/memory-audit/audit-queries.sql";
    private static final String SCHEMA_RESOURCE = "/memory-audit/annotation.schema.json";

    @Test
    @DisplayName("Phase 1: audit SQL templates load and contain all 4 queries")
    void sqlTemplatesLoad() throws IOException {
        String sql = loadResource(SQL_RESOURCE);
        assertNotNull(sql);
        // Q1: triple sampling / Q2: snippet bundle / Q3: extraction volume / Q4: dedupe proxy
        assertTrue(sql.contains("Q1: Sample"), "Q1 missing");
        assertTrue(sql.contains("Q2: Snippet bundle"), "Q2 missing");
        assertTrue(sql.contains("Q3: LLM extraction call volume"), "Q3 missing");
        assertTrue(sql.contains("Q4: Deduplicate hit-rate proxy"), "Q4 missing");
        assertTrue(sql.contains(":tenant_id"), "missing :tenant_id parameter");
        assertTrue(sql.contains(":time_window"), "missing :time_window parameter");
        assertTrue(sql.contains(":sample_limit"), "missing :sample_limit parameter");
    }

    @Test
    @DisplayName("Phase 1: annotation JSON schema loads and declares all 5 tags")
    void annotationSchemaLoads() throws IOException {
        String schema = loadResource(SCHEMA_RESOURCE);
        assertNotNull(schema);
        assertTrue(schema.contains("\"no-conflict\""));
        assertTrue(schema.contains("\"temporal-conflict\""));
        assertTrue(schema.contains("\"factual-conflict\""));
        assertTrue(schema.contains("\"granularity-conflict\""));
        assertTrue(schema.contains("\"unclear\""));
    }

    @Test
    @Disabled("Phase 2 — needs Spring context + read-only PG + anonymized snapshot. See design doc §4.")
    @DisplayName("Phase 2: run Q1-Q4 + load annotations + emit report")
    void runFullAudit() {
        // Phase 2 implementation:
        //   1. @SpringBootTest injects JdbcTemplate against memory-audit profile
        //   2. Execute Q1 → 10 sample triples
        //   3. For each: execute Q2 → snippet bundle → write PromptSegmentSample
        //   4. Execute Q3 → write extraction-volume-<ts>.json
        //   5. Execute Q4 → write dedupe-proxy-<ts>.json
        //   6. Block until annotations-<ts>.json present (CI hint) OR skip metrics
        //   7. ConflictMetrics.distribution + conflictRate + justifiesDualZoneSchema
        //   8. Write report-<ts>.md to auraboot-enterprise/.../memory-audit/
        throw new UnsupportedOperationException("Phase 2 stub — see design doc §4");
    }

    private String loadResource(String path) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in == null) throw new IOException("classpath resource not found: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
