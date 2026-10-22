package com.auraboot.framework.rag.eval;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.rag.d7.D7CompiledKnowledgeMatch;
import com.auraboot.framework.rag.d7.D7CompiledKnowledgeService;
import com.auraboot.framework.rag.d7.D7KnowledgeProperties;
import com.auraboot.framework.rag.dto.RetrievalResult;
import com.auraboot.framework.rag.service.EmbeddingService;
import com.auraboot.framework.rag.service.InternalDocImportService;
import com.auraboot.framework.rag.service.RagRetrievalService;
import com.auraboot.framework.rag.util.KeywordCoverage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-2 RAG + D7 golden-query evaluation harness — full live run.
 *
 * <p>This is a <em>measurement</em>, not a quality gate: no recall/MRR
 * thresholds are asserted (first runs are expected to execute in
 * keyword-fallback mode whose numbers are unknown). Hard assertions only
 * cover harness integrity: all golden queries executed, output files written,
 * results JSON parseable.
 *
 * <p>Gated by env vars so plain {@code ./gradlew test} skips it:
 * <ul>
 *   <li>{@code RAG_EVAL_DOCS_PATH} — directory of markdown docs to import as
 *       the Path-A knowledge base (e.g. an absolute path to
 *       {@code docs/system-reference})</li>
 *   <li>{@code RAG_EVAL_D7_PAGES_PATH} — directory of compiled-knowledge page
 *       JSON files for Path B</li>
 *   <li>{@code RAG_EVAL_OUTPUT_DIR} — optional; defaults to
 *       {@code build/rag-eval-output}</li>
 * </ul>
 *
 * <p>Run:
 * <pre>{@code
 * cd platform && \
 *   RAG_EVAL_DOCS_PATH=/abs/path/docs/system-reference \
 *   RAG_EVAL_D7_PAGES_PATH=/abs/path/docs/system-reference/compiled-knowledge/pages \
 *   ./gradlew :test --tests '*RagEvaluationPhase2IT*'
 * }</pre>
 *
 * <p>EmbeddingService is deliberately NOT mocked: with no embedding provider
 * key configured, {@code embed} returns null and the retrieval stack degrades
 * to its BM25/keyword leg — that degradation is itself part of what this
 * harness measures. The actual mode is probed and recorded in the report as
 * {@code embeddingMode: live|keyword-fallback}.
 *
 * <p>Phase 1 (schema + parsing) lives in {@link RagEvaluationHarness} and is
 * unchanged.
 */
@Slf4j
@Tag("rag-eval")
@DisplayName("Phase 2: RAG (Path A) + D7 (Path B) golden-query evaluation — live measurement run")
class RagEvaluationPhase2IT extends BaseIntegrationTest {

    private static final String GOLDEN_RESOURCE = "/rag-eval/golden-queries.json";
    private static final int TOP_K = 10;
    private static final int RECALL_AT = 5;

    @Autowired
    private InternalDocImportService importService;

    @Autowired
    private RagRetrievalService ragRetrievalService;

    @Autowired
    private D7CompiledKnowledgeService d7Service;

    @Autowired
    private D7KnowledgeProperties d7Properties;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Per-query evaluation row for one path. Serialized into results JSON.
     *
     * <p>{@code topSignal} / {@code topSimilarity} are the rejection-floor
     * calibration signals (G10): for Path A {@code topSignal} is the max
     * {@link KeywordCoverage} over returned chunks and {@code topSimilarity} the
     * max vector similarity; for Path B {@code topSignal} is the max D7 match
     * score (similarity unused). Comparing these across {@code neither} vs
     * answerable queries is how the floor thresholds are chosen from data.
     */
    record QueryResult(
            String id,
            String language,
            String lengthClass,
            String expectedPath,
            String query,
            List<String> ranked,
            List<String> expected,
            Double recallAt5,
            Double rr,
            boolean countedForMetrics,
            double topSignal,
            double topSimilarity
    ) {}

    @Test
    @Timeout(value = 90, unit = TimeUnit.MINUTES)
    @DisplayName("drive all golden queries through Path A + Path B, emit results JSON + report MD")
    void runFullEvaluation() throws Exception {
        String docsPath = System.getenv("RAG_EVAL_DOCS_PATH");
        String d7PagesPath = System.getenv("RAG_EVAL_D7_PAGES_PATH");
        Assumptions.assumeTrue(docsPath != null && !docsPath.isBlank(),
                "RAG_EVAL_DOCS_PATH not set — skipping Phase-2 evaluation");
        Assumptions.assumeTrue(d7PagesPath != null && !d7PagesPath.isBlank(),
                "RAG_EVAL_D7_PAGES_PATH not set — skipping Phase-2 evaluation");
        Assumptions.assumeTrue(Files.isDirectory(Path.of(docsPath)),
                "RAG_EVAL_DOCS_PATH is not a directory: " + docsPath);
        Assumptions.assumeTrue(Files.isDirectory(Path.of(d7PagesPath)),
                "RAG_EVAL_D7_PAGES_PATH is not a directory: " + d7PagesPath);

        String outputDir = System.getenv().getOrDefault("RAG_EVAL_OUTPUT_DIR", "build/rag-eval-output");
        Path outDir = Path.of(outputDir);
        if (!outDir.isAbsolute()) {
            outDir = Path.of(System.getProperty("user.dir")).resolve(outDir).normalize();
        }
        Files.createDirectories(outDir);
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

        GoldenQuerySet goldenSet = loadGoldenQueries();
        List<GoldenQuery> queries = goldenSet.queries();
        assertTrue(queries.size() >= 52, "expected the full 52-query golden set, got " + queries.size());

        Long tenantId = getTestTenant().getId();
        Long userId = getTestUser().getId();

        // ─── Probe embedding mode (do not mock — degradation is part of the measurement) ───
        String embeddingMode;
        try {
            float[] probe = embeddingService.embed(tenantId, "auraboot rag evaluation probe", null);
            embeddingMode = probe != null ? "live" : "keyword-fallback";
        } catch (Exception e) {
            log.info("Embedding probe threw ({}); recording keyword-fallback", e.getMessage());
            embeddingMode = "keyword-fallback";
        }
        log.info("RAG eval embeddingMode = {}", embeddingMode);

        // ─── Path A corpus: import docs into KB (rolled back with the test tx) ───
        Instant importStart = Instant.now();
        InternalDocImportService.ImportResult importResult =
                importService.importDocs(tenantId, userId, docsPath);
        Duration importDuration = Duration.between(importStart, Instant.now());
        assertTrue(importResult.totalFiles() > 0,
                "RAG_EVAL_DOCS_PATH contains no markdown files: " + docsPath);
        log.info("Imported docs in {}s: {}", importDuration.toSeconds(), importResult);
        List<String> kbPids = List.of(importResult.kbPid());

        // ─── Path B corpus: point D7 at the pages directory (restored after run) ───
        String previousPageDir = d7Properties.getPageDirectory();
        boolean previousEnabled = d7Properties.isEnabled();
        d7Properties.setPageDirectory(d7PagesPath);
        d7Properties.setEnabled(true);

        List<QueryResult> pathAResults = new ArrayList<>();
        List<QueryResult> pathBResults = new ArrayList<>();
        Instant evalStart = Instant.now();
        try {
            for (GoldenQuery q : queries) {
                // Path A — KB retrieval; dedupe chunk hits to doc granularity, rank order kept
                List<RetrievalResult> rawA = ragRetrievalService.retrieve(tenantId, q.query(), kbPids, TOP_K, null);
                List<String> rankedDocs = dedupe(rawA.stream().map(RetrievalResult::getDocName).toList());
                double covA = rawA.stream()
                        .mapToDouble(r -> KeywordCoverage.coverage(q.query(), r.getContent())).max().orElse(0.0);
                double simA = rawA.stream().mapToDouble(RetrievalResult::getSimilarity).max().orElse(0.0);
                pathAResults.add(score(q, rankedDocs, canonicalizeDocNames(rankedDocs, q.expectedKbPages()),
                        q.expectedKbPages(), covA, simA));

                // Path B — D7 compiled-knowledge retrieval; page ids matched exactly
                List<D7CompiledKnowledgeMatch> rawB = d7Service.retrieve(tenantId, q.query(), TOP_K);
                List<String> rankedPages = rawB.stream()
                        .map((D7CompiledKnowledgeMatch m) -> m.getPage().getId()).toList();
                double scoreB = rawB.stream()
                        .mapToDouble(D7CompiledKnowledgeMatch::getScore).max().orElse(0.0);
                pathBResults.add(score(q, rankedPages, rankedPages, q.expectedD7Pages(), scoreB, 0.0));
            }
        } finally {
            d7Properties.setPageDirectory(previousPageDir);
            d7Properties.setEnabled(previousEnabled);
        }
        Duration evalDuration = Duration.between(evalStart, Instant.now());

        // ─── Emit artifacts ───
        Path pathAFile = outDir.resolve("results-" + ts + "-path-a.json");
        Path pathBFile = outDir.resolve("results-" + ts + "-path-b.json");
        Path reportFile = outDir.resolve("report-" + ts + ".md");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(pathAFile.toFile(), pathAResults);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(pathBFile.toFile(), pathBResults);
        String report = renderReport(goldenSet, pathAResults, pathBResults,
                embeddingMode, importResult, importDuration, evalDuration, ts);
        Files.writeString(reportFile, report);
        log.info("RAG eval artifacts written to {}", outDir);
        log.info("\n{}", report);

        // ─── Harness-integrity assertions only (measurement, not a gate) ───
        assertEquals(queries.size(), pathAResults.size(), "every golden query must run through Path A");
        assertEquals(queries.size(), pathBResults.size(), "every golden query must run through Path B");
        assertTrue(Files.size(reportFile) > 0, "report must not be empty");
        JsonNode parsedA = objectMapper.readTree(pathAFile.toFile());
        JsonNode parsedB = objectMapper.readTree(pathBFile.toFile());
        assertNotNull(parsedA, "path-a results JSON must parse");
        assertNotNull(parsedB, "path-b results JSON must parse");
        assertEquals(queries.size(), parsedA.size(), "path-a results JSON must hold one row per query");
        assertEquals(queries.size(), parsedB.size(), "path-b results JSON must hold one row per query");
    }

    /**
     * Score one query for one path. {@code canonicalRanked} is the ranked list
     * after mapping each entry onto the expected vocabulary (suffix-matching
     * for Path A doc names; identity for Path B page ids). Queries with an
     * empty expected set are recorded but not counted for recall/MRR — for
     * {@code expected_path=neither} they feed the no-answer matrix instead.
     */
    private QueryResult score(GoldenQuery q, List<String> ranked, List<String> canonicalRanked,
                              List<String> expected, double topSignal, double topSimilarity) {
        Double recall = null;
        Double rr = null;
        boolean counted = !expected.isEmpty();
        if (counted) {
            recall = RetrievalMetrics.recall(
                    canonicalRanked.subList(0, Math.min(RECALL_AT, canonicalRanked.size())), expected);
            rr = RetrievalMetrics.reciprocalRank(canonicalRanked, expected);
        }
        return new QueryResult(q.id(), q.language(), q.lengthClass(), q.expectedPath(), q.query(),
                ranked, expected, recall, rr, counted, topSignal, topSimilarity);
    }

    /**
     * Map retrieved doc names onto the expected vocabulary by path-suffix match.
     * Expected entries are repo-rooted ({@code docs/system-reference/core/x.md})
     * while {@code docName} is relative to the import base ({@code core/x.md}),
     * so match when either is a path-boundary suffix of the other.
     */
    static List<String> canonicalizeDocNames(List<String> rankedDocNames, List<String> expected) {
        List<String> out = new ArrayList<>(rankedDocNames.size());
        for (String docName : rankedDocNames) {
            String canonical = docName;
            for (String exp : expected) {
                if (pathSuffixMatch(exp, docName)) {
                    canonical = exp;
                    break;
                }
            }
            out.add(canonical);
        }
        return out;
    }

    static boolean pathSuffixMatch(String a, String b) {
        if (a.equals(b)) return true;
        String longer = a.length() >= b.length() ? a : b;
        String shorter = a.length() >= b.length() ? b : a;
        return longer.endsWith("/" + shorter);
    }

    private static List<String> dedupe(List<String> in) {
        return new ArrayList<>(new LinkedHashSet<>(in));
    }

    // ─────────────────────────── report rendering ───────────────────────────

    private String renderReport(GoldenQuerySet goldenSet,
                                List<QueryResult> pathA, List<QueryResult> pathB,
                                String embeddingMode,
                                InternalDocImportService.ImportResult importResult,
                                Duration importDuration, Duration evalDuration, String ts) {
        StringBuilder sb = new StringBuilder();
        sb.append("# RAG golden-query evaluation — Phase 2 run ").append(ts).append("\n\n");
        sb.append("| Run metadata | Value |\n|---|---|\n");
        sb.append("| Golden set version | ").append(goldenSet.version()).append(" |\n");
        sb.append("| Queries executed | ").append(pathA.size()).append(" |\n");
        sb.append("| embeddingMode | ").append(embeddingMode).append(" |\n");
        sb.append("| Path A corpus | ").append(importResult.totalFiles()).append(" md files (imported=")
                .append(importResult.imported()).append(", updated=").append(importResult.updated())
                .append(", skipped=").append(importResult.skipped()).append(", failed=")
                .append(importResult.failed()).append(") |\n");
        sb.append("| Import duration | ").append(importDuration.toSeconds()).append("s |\n");
        sb.append("| Eval duration (52×2 retrievals) | ").append(evalDuration.toSeconds()).append("s |\n");
        sb.append("| topK / recall@K | ").append(TOP_K).append(" / ").append(RECALL_AT).append(" |\n\n");

        sb.append("## Path A — KB retrieval (RagRetrievalService), recall@5 / MRR@10 by language\n\n");
        appendAggregateTable(sb, pathA);
        sb.append("\n## Path B — D7 compiled knowledge (D7CompiledKnowledgeService), recall@5 / MRR@10 by language\n\n");
        appendAggregateTable(sb, pathB);

        sb.append("\n## No-answer behavior (expected_path = neither)\n\n");
        appendNoAnswerMatrix(sb, pathA, pathB);

        sb.append("\n## Rejection-floor calibration signals (G10)\n\n");
        appendFloorCalibration(sb, pathA, pathB);

        sb.append("\n## Per-query results\n\n");
        sb.append("| id | lang | expected_path | A recall@5 | A rr@10 | B recall@5 | B rr@10 "
                + "| A_cov | A_sim | B_score | no-answer |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (int i = 0; i < pathA.size(); i++) {
            QueryResult a = pathA.get(i);
            QueryResult b = pathB.get(i);
            String noAnswer = "neither".equals(a.expectedPath())
                    ? (a.ranked().isEmpty() && b.ranked().isEmpty() ? "correct-reject" : "false-positive")
                    : "—";
            sb.append("| ").append(a.id())
                    .append(" | ").append(a.language())
                    .append(" | ").append(a.expectedPath())
                    .append(" | ").append(fmt(a.recallAt5()))
                    .append(" | ").append(fmt(a.rr()))
                    .append(" | ").append(fmt(b.recallAt5()))
                    .append(" | ").append(fmt(b.rr()))
                    .append(" | ").append(String.format("%.3f", a.topSignal()))
                    .append(" | ").append(String.format("%.3f", a.topSimilarity()))
                    .append(" | ").append(String.format("%.3f", b.topSignal()))
                    .append(" | ").append(noAnswer)
                    .append(" |\n");
        }
        sb.append("\nFull ranked lists per query: `results-").append(ts).append("-path-a.json` / `results-")
                .append(ts).append("-path-b.json`.\n");
        return sb.toString();
    }

    private void appendAggregateTable(StringBuilder sb, List<QueryResult> results) {
        sb.append("| language | queries scored | recall@5 (avg) | MRR@10 (avg) |\n|---|---|---|---|\n");
        Map<String, List<QueryResult>> byLang = new LinkedHashMap<>();
        for (QueryResult r : results) {
            if (r.countedForMetrics()) {
                byLang.computeIfAbsent(r.language(), k -> new ArrayList<>()).add(r);
            }
        }
        for (Map.Entry<String, List<QueryResult>> e : byLang.entrySet()) {
            appendAggregateRow(sb, e.getKey(), e.getValue());
        }
        appendAggregateRow(sb, "**all**",
                results.stream().filter(QueryResult::countedForMetrics).toList());
    }

    private void appendAggregateRow(StringBuilder sb, String label, List<QueryResult> rows) {
        if (rows.isEmpty()) {
            sb.append("| ").append(label).append(" | 0 | n/a | n/a |\n");
            return;
        }
        double recall = rows.stream().mapToDouble(QueryResult::recallAt5).average().orElse(0);
        double mrr = rows.stream().mapToDouble(QueryResult::rr).average().orElse(0);
        sb.append("| ").append(label).append(" | ").append(rows.size())
                .append(" | ").append(String.format("%.3f", recall))
                .append(" | ").append(String.format("%.3f", mrr)).append(" |\n");
    }

    /**
     * No-answer scoring: a {@code neither} query is correctly rejected iff both
     * paths return empty. Precision = correct rejections / all queries where
     * both paths returned empty; recall = correct rejections / all neither
     * queries.
     */
    private void appendNoAnswerMatrix(StringBuilder sb, List<QueryResult> pathA, List<QueryResult> pathB) {
        int neitherTotal = 0;
        int correctReject = 0;
        int bothEmptyTotal = 0;
        List<String> falsePositives = new ArrayList<>();
        List<String> wrongRejections = new ArrayList<>();
        for (int i = 0; i < pathA.size(); i++) {
            QueryResult a = pathA.get(i);
            QueryResult b = pathB.get(i);
            boolean bothEmpty = a.ranked().isEmpty() && b.ranked().isEmpty();
            if (bothEmpty) bothEmptyTotal++;
            if ("neither".equals(a.expectedPath())) {
                neitherTotal++;
                if (bothEmpty) correctReject++;
                else falsePositives.add(a.id() + (a.ranked().isEmpty() ? " (B hit)" : b.ranked().isEmpty() ? " (A hit)" : " (A+B hit)"));
            } else if (bothEmpty) {
                wrongRejections.add(a.id());
            }
        }
        sb.append("| metric | value |\n|---|---|\n");
        sb.append("| neither queries | ").append(neitherTotal).append(" |\n");
        sb.append("| correctly rejected (both paths empty) | ").append(correctReject).append(" |\n");
        sb.append("| no-answer recall | ").append(neitherTotal == 0 ? "n/a"
                : String.format("%.3f", (double) correctReject / neitherTotal)).append(" |\n");
        sb.append("| no-answer precision | ").append(bothEmptyTotal == 0 ? "n/a"
                : String.format("%.3f", (double) correctReject / bothEmptyTotal)).append(" |\n");
        sb.append("| false positives (neither but retrieved) | ")
                .append(falsePositives.isEmpty() ? "none" : String.join(", ", falsePositives)).append(" |\n");
        sb.append("| wrong rejections (expected hits but both empty) | ")
                .append(wrongRejections.isEmpty() ? "none" : String.join(", ", wrongRejections)).append(" |\n");
    }

    /**
     * Floor calibration (G10): a usable threshold must sit ABOVE every neither
     * query's top signal (so they are rejected) and BELOW every answerable
     * true-hit's top signal (so recall is kept). This prints both distributions
     * so the gap — if any — is visible and the floor is chosen from data, not
     * guessed.
     */
    private void appendFloorCalibration(StringBuilder sb, List<QueryResult> pathA, List<QueryResult> pathB) {
        List<Double> neitherCovA = new ArrayList<>();
        List<Double> neitherScoreB = new ArrayList<>();
        List<Double> hitCovA = new ArrayList<>();
        List<Double> hitScoreB = new ArrayList<>();
        for (int i = 0; i < pathA.size(); i++) {
            QueryResult a = pathA.get(i);
            QueryResult b = pathB.get(i);
            if ("neither".equals(a.expectedPath())) {
                neitherCovA.add(a.topSignal());
                neitherScoreB.add(b.topSignal());
            } else {
                // Answerable: only count the path the query is expected to (and did) hit.
                if (a.countedForMetrics() && a.recallAt5() != null && a.recallAt5() > 0) {
                    hitCovA.add(a.topSignal());
                }
                if (b.countedForMetrics() && b.recallAt5() != null && b.recallAt5() > 0) {
                    hitScoreB.add(b.topSignal());
                }
            }
        }
        sb.append("| signal | neither max | neither mean | answerable-hit min | answerable-hit mean |\n");
        sb.append("|---|---|---|---|---|\n");
        appendCalibrationRow(sb, "Path A keyword coverage", neitherCovA, hitCovA);
        appendCalibrationRow(sb, "Path B D7 match score", neitherScoreB, hitScoreB);
        sb.append("\nA floor strictly between `neither max` and `answerable-hit min` rejects every "
                + "off-topic query without dropping any true hit. If they overlap, the floor trades "
                + "a few false-positives for a few rejections — pick by product tolerance.\n");
        sb.append("\nNeither-query signals (sorted desc, the values the floor must exceed):\n\n");
        sb.append("| id | query | A_cov | B_score |\n|---|---|---|---|\n");
        List<QueryResult> neithers = new ArrayList<>();
        for (int i = 0; i < pathA.size(); i++) {
            if ("neither".equals(pathA.get(i).expectedPath())) neithers.add(pathA.get(i));
        }
        neithers.sort((x, y) -> Double.compare(y.topSignal(), x.topSignal()));
        for (QueryResult n : neithers) {
            QueryResult b = pathB.stream().filter(r -> r.id().equals(n.id())).findFirst().orElse(n);
            sb.append("| ").append(n.id()).append(" | ").append(n.query())
                    .append(" | ").append(String.format("%.3f", n.topSignal()))
                    .append(" | ").append(String.format("%.3f", b.topSignal())).append(" |\n");
        }
    }

    private void appendCalibrationRow(StringBuilder sb, String label,
                                      List<Double> neither, List<Double> hits) {
        sb.append("| ").append(label)
                .append(" | ").append(neither.isEmpty() ? "n/a"
                        : String.format("%.3f", neither.stream().mapToDouble(d -> d).max().orElse(0)))
                .append(" | ").append(neither.isEmpty() ? "n/a"
                        : String.format("%.3f", neither.stream().mapToDouble(d -> d).average().orElse(0)))
                .append(" | ").append(hits.isEmpty() ? "n/a"
                        : String.format("%.3f", hits.stream().mapToDouble(d -> d).min().orElse(0)))
                .append(" | ").append(hits.isEmpty() ? "n/a"
                        : String.format("%.3f", hits.stream().mapToDouble(d -> d).average().orElse(0)))
                .append(" |\n");
    }

    private static String fmt(Double v) {
        return v == null ? "—" : String.format("%.3f", v);
    }

    private GoldenQuerySet loadGoldenQueries() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(GOLDEN_RESOURCE)) {
            if (in == null) {
                throw new IOException("classpath resource not found: " + GOLDEN_RESOURCE);
            }
            return objectMapper.readValue(in, GoldenQuerySet.class);
        }
    }
}
