package com.auraboot.framework.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.auraboot.framework.agent.service.AgentMemoryService;
import com.auraboot.framework.agent.service.MemoryEmbeddingService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.rag.service.EmbeddingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Finite index/selectivity qualification using real managed Qwen vectors and an independent exact PID oracle. */
@Tag("agent-eval-live")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MemoryEmbeddingScaleLiveIT extends BaseIntegrationTest {
    @Autowired AgentMemoryService memories;
    @Autowired MemoryEmbeddingService writer;
    @Autowired EmbeddingService embedding;
    @Autowired com.auraboot.framework.cloudconfig.service.CloudConfigService cloud;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    final List<Map<String,Object>> rounds = new ArrayList<>();
    final Map<String,Object> report = new LinkedHashMap<>();

    static String sha(String text) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
    @Test @Timeout(value = 45, unit = TimeUnit.MINUTES)
    void realVectorsRetainScopedTopFiveAgainstAnExactOracleAtFiniteScales() throws Exception {
        Path output = Path.of(Objects.requireNonNull(System.getenv("AURA_MEMORY_SCALE_REPORT")));
        Path fixture = Path.of(Objects.requireNonNull(System.getenv("AURA_MEMORY_SCALE_FIXTURE")));
        String allow = System.getProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS");
        List<Map<String,Object>> records = new ArrayList<>();
        var retained = Files.exists(fixture) ? json.readTree(Files.readString(fixture)) : null;
        if (retained != null) for (var record : retained.path("records")) records.add(json.convertValue(record, Map.class));
        report.put("rounds", rounds); report.put("profile", "embedding-scale-v1"); report.put("threshold", .95); report.put("fixture", fixture.toString());
        int created = 0, reused = 0;
        try {
            System.setProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS", "127.0.0.1");
            Long tenant = getTestTenant().getId(); String user = getTestUser().getId().toString();
            Path probeFile = Files.createTempFile("scale-managed-provider-", ".json");
            try {
                var probe = new ProcessBuilder("node", Objects.requireNonNull(System.getenv("AURA_LOCAL_EMBEDDING_LAUNCHER")), "status",
                        Objects.requireNonNull(System.getenv("AURA_LOCAL_EMBEDDING_RUNTIME")), "--workspace", Objects.requireNonNull(System.getenv("AURA_LOCAL_EMBEDDING_WORKSPACE")))
                        .redirectErrorStream(true).redirectOutput(probeFile.toFile()).start();
                assertThat(probe.waitFor(120, TimeUnit.SECONDS)).isTrue(); assertThat(probe.exitValue()).as(Files.readString(probeFile)).isZero();
                var provider = json.readTree(Files.readString(probeFile));
                assertThat(provider.path("model").asText()).isEqualTo("qwen3-embedding:4b"); assertThat(provider.path("dimensions").asInt()).isEqualTo(1536);
                assertThat(provider.path("blobSha256").asText()).isEqualTo("2b0cf8f17b4c723c27303015383c27ec4bf2d8314bb677d05e920dd70bb0f16b");
                assertThat(provider.path("endpoint").asText()).isEqualTo(System.getenv("AURA_LOCAL_EMBEDDING_ENDPOINT"));
                report.put("managedProvider", provider);
                var config = new com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest();
                config.setConfigLevel("tenant"); config.setServiceType("embedding"); config.setProviderCode("local_qwen"); config.setEnabled(true); config.setPriority(-200);
                var existing = cloud.getEffectiveConfig(tenant, "embedding", "local_qwen");
                if (existing != null && tenant.equals(existing.getTenantId())) config.setPid(existing.getPid());
                config.setConfig(json.writeValueAsString(Map.of("apiKey", "local-ollama-no-auth", "baseUrl", provider.path("endpoint").asText(),
                        "defaultModel", "qwen3-embedding:4b", "dimensions", 1536, "maxBatchSize", 1, "spaceVersion", "qwen4b-" + provider.path("blobSha256").asText())));
                cloud.saveConfig(config);
            } finally { Files.deleteIfExists(probeFile); }

            String space = embedding.currentSpace(tenant, "local_qwen", 1536);
            assertThat(space).isNotBlank(); assertThat(embedding.currentSpace(tenant, null, 1536)).isEqualTo(space);
            report.put("space", space); report.put("database", jdbc.queryForObject("SELECT current_database()", String.class));
            report.put("pgvector", jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname='vector'", String.class));
            JsonNode corpus;
            try (var source = getClass().getResourceAsStream("/agent-eval/embedding-recall-v1.json")) { corpus = json.readTree(source); }
            String corpusSha = sha(json.writeValueAsString(corpus));
            if (retained != null) { assertThat(retained.path("corpusSha256").asText()).isEqualTo(corpusSha); assertThat(retained.path("space").asText()).isEqualTo(space); }
            List<JsonNode> queries = new ArrayList<>(); for (var query : corpus.path("queries")) if (!query.path("relevant").isEmpty()) queries.add(query);
            assertThat(queries).hasSize(24);
            for (int size : List.of(30, 1000, 5000)) {
                String agent = "scale-readiness-v1-" + size;
                List<String> valid = new ArrayList<>(); Set<String> forbidden = new HashSet<>();
                for (int i = 0; i < size; i++) {
                    var document = corpus.path("documents").get(i % 30);
                    String title = "Scale " + size + "/" + i + " " + document.path("title").asText();
                    String content = i < 30 ? document.path("content").asText()
                            : document.path("content").asText() + " 归档记录 " + i + "，独立业务主体的历史材料。";
                    String kind = i < 30 ? "visible" : List.of("private", "foreign", "wrong-space", "expired", "deleted", "visible").get(i % 6);
                    final int index = i; final String code = agent;
                    var previous = records.stream().filter(row -> code.equals(row.get("agent")) && ((Number) row.get("index")).intValue() == index).findFirst().orElse(null);
                    String pid;
                    if (previous != null) {
                        pid = previous.get("pid").toString();
                        var row = jdbc.queryForMap("SELECT memory_content,embedding::text AS vector,embedding_space,embedding_content_hash FROM ab_agent_memory WHERE pid=? AND memory_agent_id=?", pid, agent);
                        assertThat(row.get("memory_content")).isEqualTo(content); assertThat(sha(row.get("vector").toString())).isEqualTo(previous.get("vectorSha256"));
                        assertThat(row.get("embedding_content_hash")).isEqualTo(sha(content));
                        assertThat(row.get("embedding_space")).isEqualTo(kind.equals("wrong-space") ? "e".repeat(64) : space); reused++;
                    } else {
                        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ab_agent_memory WHERE memory_agent_id=? AND memory_title=?", Integer.class, agent, title)).isZero();
                        pid = memories.createScopedMemory(tenant, agent, "fact", "scale_fixture", title, content, 5, false, "user", user);
                        assertThat(writer.computeAndStore(pid)).as("real provider write %s/%s", size, i).isTrue();
                        switch (kind) {
                            case "private" -> jdbc.update("UPDATE ab_agent_memory SET scope_key=? WHERE pid=?", "other-user", pid);
                            case "foreign" -> jdbc.update("UPDATE ab_agent_memory SET tenant_id=? WHERE pid=?", tenant + 900, pid);
                            case "wrong-space" -> jdbc.update("UPDATE ab_agent_memory SET embedding_space=? WHERE pid=?", "e".repeat(64), pid);
                            case "expired" -> jdbc.update("UPDATE ab_agent_memory SET valid_until=NOW()-INTERVAL '1 day' WHERE pid=?", pid);
                            case "deleted" -> jdbc.update("UPDATE ab_agent_memory SET deleted_flag=TRUE WHERE pid=?", pid);
                            case "visible" -> { }
                            default -> throw new IllegalArgumentException(kind);
                        }
                        String vector = jdbc.queryForObject("SELECT embedding::text FROM ab_agent_memory WHERE pid=?", String.class, pid);
                        records.add(Map.of("agent", agent, "index", i, "pid", pid, "kind", kind, "contentSha256", sha(content), "vectorSha256", sha(vector))); created++;
                        Files.createDirectories(fixture.getParent());
                        Files.writeString(fixture, json.writeValueAsString(Map.of("profile", "embedding-scale-v1", "space", space, "corpusSha256", corpusSha, "records", records)));
                    }
                    if (kind.equals("visible")) valid.add(pid); else forbidden.add(pid);
                }
                List<Map<String,Object>> measurements = new ArrayList<>();
                for (var query : queries) {
                    var vector = embedding.embedWithIdentity(tenant, query.path("text").asText(), "local_qwen"); assertThat(vector).isNotNull(); assertThat(vector.space()).isEqualTo(space);
                    StringBuilder encoded = new StringBuilder("[");
                    for (float value : vector.values()) { if (encoded.length() > 1) encoded.append(','); encoded.append(value); }
                    String literal = encoded.append(']').toString();
                    String placeholders = String.join(",", Collections.nCopies(valid.size(), "?"));
                    List<String> exact = new TransactionTemplate(transactions).execute(status -> {
                        jdbc.execute("SET LOCAL enable_indexscan=off"); jdbc.execute("SET LOCAL enable_bitmapscan=off");
                        return jdbc.queryForList("SELECT pid FROM ab_agent_memory WHERE pid IN (" + placeholders + ") ORDER BY embedding <=> ?::vector LIMIT 5", String.class,
                                java.util.stream.Stream.concat(valid.stream(), java.util.stream.Stream.of(literal)).toArray());
                    });
                    long started = System.nanoTime();
                    var actual = memories.searchScopedSemanticInSpace(tenant, user, agent, vector, 5).stream().map(row -> row.get("pid").toString()).toList();
                    double elapsed = (System.nanoTime() - started) / 1_000_000d;
                    assertThat(actual).noneMatch(forbidden::contains); assertThat(actual).allMatch(valid::contains);
                    double recall = actual.stream().filter(exact::contains).count() / 5d;
                    String productionSql = ReflectionTestUtils.invokeMethod(memories, "semanticSearchSql");
                    var ann = new TransactionTemplate(transactions).execute(status -> {
                        jdbc.execute("SET LOCAL enable_seqscan=off"); jdbc.execute("SET LOCAL enable_bitmapscan=off"); jdbc.execute("SET LOCAL enable_sort=off");
                        jdbc.execute("SET LOCAL hnsw.iterative_scan=off"); jdbc.execute("SET LOCAL hnsw.ef_search=40");
                        String plan = jdbc.queryForObject("EXPLAIN (FORMAT JSON) " + productionSql, String.class, agent, space, tenant, user, literal, 5);
                        long annStarted = System.nanoTime();
                        var hits = memories.searchScopedSemanticInSpace(tenant, user, agent, vector, 5).stream().map(row -> row.get("pid").toString()).toList();
                        double annElapsedMs = (System.nanoTime() - annStarted) / 1_000_000d;
                        assertThat(jdbc.queryForObject("SELECT current_setting('hnsw.iterative_scan')", String.class)).as("joined transaction setting restored").isEqualTo("off");
                        assertThat(jdbc.queryForObject("SELECT current_setting('hnsw.ef_search')", String.class)).as("joined transaction budget restored").isEqualTo("40");
                        return Map.<String,Object>of("elapsedMs", annElapsedMs, "plan", plan, "hnsw", plan.contains("idx_agent_memory_embedding"), "hits", hits,
                                "recallAt5", hits.stream().filter(exact::contains).count() / 5d);
                    });
                    @SuppressWarnings("unchecked") List<String> annHits = (List<String>) ann.get("hits");
                    assertThat(annHits).noneMatch(forbidden::contains); assertThat(annHits).allMatch(valid::contains);
                    measurements.add(Map.of("query", query.path("id").asText(), "exact", exact, "actual", actual, "recallAt5", recall, "elapsedMs", elapsed, "forcedAnn", ann));
                }
                double average = measurements.stream().mapToDouble(row -> ((Number)row.get("recallAt5")).doubleValue()).average().orElseThrow();
                double annAverage = measurements.stream().mapToDouble(row -> ((Number)((Map<?,?>)row.get("forcedAnn")).get("recallAt5")).doubleValue()).average().orElseThrow();
                long hnswPlans = measurements.stream().filter(row -> Boolean.TRUE.equals(((Map<?,?>)row.get("forcedAnn")).get("hnsw"))).count();
                rounds.add(Map.of("averageAnnExactTop5Recall", annAverage, "hnswPlans", hnswPlans, "corpusRows", size, "visibleRows", valid.size(), "excludedRows", forbidden.size(), "indexedGlobalRows",
                        jdbc.queryForObject("SELECT COUNT(*) FROM ab_agent_memory WHERE embedding IS NOT NULL", Integer.class), "averageExactTop5Recall", average, "queries", measurements));
                report.put("createdRealVectors", created); report.put("reusedVerifiedVectors", reused);
                Files.createDirectories(output.getParent()); Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            }
            assertThat(rounds).allSatisfy(round -> {
                assertThat(((Number)round.get("averageExactTop5Recall")).doubleValue()).isGreaterThanOrEqualTo(.95);
                assertThat(((Number)round.get("averageAnnExactTop5Recall")).doubleValue()).isGreaterThanOrEqualTo(.95);
                assertThat(((Number)round.get("hnswPlans")).intValue()).isEqualTo(24);
            });
            report.put("status", "pass");
        } finally {
            if (allow == null) System.clearProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS"); else System.setProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS", allow);
            report.put("createdRealVectors", created); report.put("reusedVerifiedVectors", reused);
            Files.createDirectories(output.getParent()); Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
    }
}
