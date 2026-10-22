package com.auraboot.framework.rag.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spike-1 RAG + D7 evaluation harness — Phase 1 skeleton.
 *
 * <p>This class is intentionally <em>disabled by default</em> ({@link Disabled})
 * because Phase 2 wiring (Spring context + populated Postgres KB + embedding
 * provider) is out of scope for the initial scaffold PR.
 *
 * <p>Phase 1 (this PR) verifies:
 * <ul>
 *   <li>Golden query JSON loads + parses against schema</li>
 *   <li>{@link RetrievalMetrics} unit tests pass (see {@link RetrievalMetricsTest})</li>
 * </ul>
 *
 * <p>Phase 2 will:
 * <ol>
 *   <li>Promote to {@code @SpringBootTest} with PG profile</li>
 *   <li>Wire {@code RagRetrievalService} (Path A) + {@code D7CompiledKnowledgeService} (Path B)</li>
 *   <li>Drive each {@link GoldenQuery} through both paths, capture top-K results</li>
 *   <li>Aggregate metrics per language / length_class / tag</li>
 *   <li>Emit {@code results-<ts>-path-a.json} + {@code results-<ts>-path-b.json} + {@code report-<ts>.md}</li>
 *   <li>Data lands in {@code auraboot-enterprise/docs/system-reference/runtime-traces/rag-evaluation/}</li>
 * </ol>
 *
 * <p>Run: {@code ./gradlew :platform:test --tests '*RagEvaluationHarness*' -PragEval=true}
 * (Phase 2 — currently @Disabled).
 *
 * @see <a href="../../../../../docs/backlog/2026-05-27-rag-d7-eval-harness-design.md">design doc</a>
 */
@Tag("rag-eval")
@DisplayName("Spike-1 RAG + D7 evaluation harness (Phase 1 — schema + parsing only)")
class RagEvaluationHarness {

    private static final String GOLDEN_RESOURCE = "/rag-eval/golden-queries.json";

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("Phase 1: golden-queries.json loads and parses")
    void goldenQueriesParse() throws IOException {
        GoldenQuerySet set = loadGoldenQueries();
        assertNotNull(set, "golden set must load");
        assertNotNull(set.version(), "version field required");
        assertTrue(set.version().startsWith("0."),
                "Phase 1 seed should be 0.x semver, got: " + set.version());
        List<GoldenQuery> queries = set.queries();
        assertTrue(queries.size() >= 10,
                "Phase 1 seed expects ≥ 10 queries, got " + queries.size());
        // Coverage check — Phase 1 must touch every language + every expected_path
        assertTrue(queries.stream().anyMatch(q -> "zh".equals(q.language())));
        assertTrue(queries.stream().anyMatch(q -> "en".equals(q.language())));
        assertTrue(queries.stream().anyMatch(q -> "mixed".equals(q.language())));
        assertTrue(queries.stream().anyMatch(GoldenQuery::expectsPathB));
        assertTrue(queries.stream().anyMatch(GoldenQuery::expectsNeither));
    }

    @Test
    @Disabled("Phase 2 — needs Spring context + populated PG + D7 fixtures. See design doc §6.")
    @DisplayName("Phase 2: drive each query through Path A + Path B, emit results JSON + report MD")
    void runFullEvaluation() {
        // Phase 2 implementation:
        //   1. Inject RagRetrievalService + D7CompiledKnowledgeService via @SpringBootTest
        //   2. For each GoldenQuery in loadGoldenQueries().queries():
        //        a. List<String> retrievedA = pathARetrieve(q, topK=5);
        //        b. List<String> retrievedB = pathBRetrieve(q, topK=5);
        //        c. if (q.expectsNeither()) record correctNoAnswer / falsePositive
        //           else record recall + precision per path
        //   3. Aggregate per language / length_class / tag
        //   4. Write JSON + MD report into runtime-traces/rag-evaluation/
        throw new UnsupportedOperationException("Phase 2 stub — see design doc §6");
    }

    private GoldenQuerySet loadGoldenQueries() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(GOLDEN_RESOURCE)) {
            if (in == null) {
                throw new IOException("classpath resource not found: " + GOLDEN_RESOURCE);
            }
            return mapper.readValue(in, GoldenQuerySet.class);
        }
    }
}
