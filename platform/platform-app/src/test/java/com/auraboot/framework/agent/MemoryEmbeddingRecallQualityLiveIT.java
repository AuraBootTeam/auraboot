package com.auraboot.framework.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.agent.service.ActiveMemoryService;
import com.auraboot.framework.agent.service.AgentMemoryService;
import com.auraboot.framework.agent.service.MemoryEmbeddingService;
import com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.rag.service.EmbeddingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.security.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.*;

/** Actual cached embedding weights, production services and PostgreSQL; never controlled vectors. */
@Tag("agent-eval-live")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MemoryEmbeddingRecallQualityLiveIT extends BaseIntegrationTest {
    @Autowired private AgentMemoryService memories;
    @Autowired private MemoryEmbeddingService writer;
    @Autowired private ActiveMemoryService active;
    @Autowired private EmbeddingService embedding;
    @Autowired private CloudConfigService cloud;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    private Process server;
    private String oldAllowlist;
    private boolean allowlistChanged;
    private Long tenant;
    private String user;
    private String agent;
    private final String provider = "quality_local_qwen";
    private Path output;
    private JsonNode definition;
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final List<Map<String, Object>> rows = new ArrayList<>();
    private final Map<String, String> documentPids = new LinkedHashMap<>();
    private final Set<String> forbidden = new HashSet<>();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @BeforeEach
    void prepareRealProvider() throws Exception {
        assertThat(System.getenv("AURA_EMBEDDING_QUALITY_REPORT")).isNotBlank();
        output = Path.of(System.getenv("AURA_EMBEDDING_QUALITY_REPORT")); Files.createDirectories(output.getParent());
        try (var in = getClass().getResourceAsStream("/agent-eval/embedding-recall-v1.json")) {
            assertThat(in).isNotNull(); definition = json.readTree(in);
        }
        Path cache = Path.of(Objects.requireNonNull(System.getenv("AURA_EMBEDDING_MODEL_CACHE"))).toRealPath();
        String digest = definition.path("modelBlobSha256").asText();
        Path blob = cache.resolve("blobs/sha256-" + digest);
        assertThat(Files.isRegularFile(blob)).as("Existing real weights required; no download fallback").isTrue();
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(blob)) {
            byte[] buffer = new byte[1024 * 1024]; int n;
            while ((n = in.read(buffer)) != -1) sha.update(buffer, 0, n);
        }
        assertThat(HexFormat.of().formatHex(sha.digest())).isEqualTo(digest);
        report.put("modelBlobSha256", digest); report.put("modelBlobBytes", Files.size(blob));
        report.put("model", definition.path("model").asText()); report.put("profile", "embedding-recall-v1");
        report.put("rows", rows); report.put("documents", documentPids); report.put("expectedQueries", 30);
        report.put("cloudCredentialRequired", false); report.put("apiCharge", "local model; device cost not measured");
        int port = Integer.parseInt(Objects.requireNonNull(System.getenv("AURA_EMBEDDING_PORT")));
        assertThat(port).isGreaterThan(1024);
        try (var probe = new java.net.ServerSocket()) { probe.bind(new InetSocketAddress("127.0.0.1", port)); }
        Path temp = Path.of(Objects.requireNonNull(System.getenv("AURA_EMBEDDING_SERVICE_DIR"))); Files.createDirectories(temp);
        ProcessBuilder builder = new ProcessBuilder(Objects.requireNonNull(System.getenv("AURA_OLLAMA_BIN")), "serve");
        builder.environment().put("OLLAMA_MODELS", cache.toString());
        builder.environment().put("OLLAMA_HOST", "127.0.0.1:" + port);
        builder.environment().put("OLLAMA_TMPDIR", temp.toString());
        builder.environment().put("OLLAMA_MAX_LOADED_MODELS", "1");
        builder.environment().put("OLLAMA_NUM_PARALLEL", "1");
        builder.redirectErrorStream(true).redirectOutput(output.getParent().resolve("provider.log").toFile());
        server = builder.start(); report.put("ownedProviderPid", server.pid());
        String endpoint = "http://127.0.0.1:" + port;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (true) {
            assertThat(server.isAlive()).as("Owned provider must remain alive").isTrue();
            try {
                var tags = json.readTree(HTTP.send(HttpRequest.newBuilder(URI.create(endpoint + "/api/tags")).timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString()).body());
                if (tags.path("models").findValuesAsText("name").contains(definition.path("model").asText())) break;
            } catch (java.io.IOException unavailable) {
                if (System.nanoTime() > deadline) throw unavailable;
            }
            if (System.nanoTime() > deadline) throw new IllegalStateException("Expected cached model not listed");
            Thread.sleep(50);
        }
        report.put("providerVersion", json.readTree(HTTP.send(HttpRequest.newBuilder(URI.create(endpoint + "/api/version")).GET().build(), HttpResponse.BodyHandlers.ofString()).body()));
        oldAllowlist = System.getProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS");
        System.setProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS", "127.0.0.1"); allowlistChanged = true;
        tenant = getTestTenant().getId(); user = getTestUser().getId().toString(); agent = "eq-" + UniqueIdGenerator.generate();
        CloudConfigSaveRequest request = new CloudConfigSaveRequest();
        request.setConfigLevel("tenant"); request.setServiceType("embedding"); request.setProviderCode(provider);
        // Ollama ignores this non-secret compatibility marker; it is not a cloud API credential.
        request.setConfig(json.writeValueAsString(Map.of("apiKey", "local-ollama-no-auth", "baseUrl", endpoint,
                "defaultModel", definition.path("model").asText(), "dimensions", 1536, "maxBatchSize", 1,
                "spaceVersion", "qwen4b-" + digest)));
        request.setEnabled(true); request.setPriority(0); cloud.saveConfig(request);
        report.put("actualDatabase", jdbc.queryForObject("SELECT current_database()", String.class));
        report.put("agentCode", agent); report.put("tenantId", tenant);
        assertThat(ReflectionTestUtils.getField(active, "semanticRecallEnabled")).as("Default remains off").isEqualTo(false);
    }

    @AfterEach
    void preserveDataAndStopOnlyOwnedProvider() throws Exception {
        try {
            if (tenant != null) jdbc.update("DELETE FROM ab_cloud_config WHERE tenant_id=? AND service_type='embedding' AND provider_code=?", tenant, provider);
            ReflectionTestUtils.setField(active, "semanticRecallEnabled", false);
            if (allowlistChanged) {
                if (oldAllowlist == null) System.clearProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS");
                else System.setProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS", oldAllowlist);
            }
        } finally {
            if (server != null) {
                var children = server.descendants().toList(); server.destroy();
                if (!server.waitFor(10, TimeUnit.SECONDS) && server.isAlive()) server.destroyForcibly().waitFor();
                for (var child : children) if (child.isAlive()) {
                    child.destroy();
                    try { child.onExit().get(5, TimeUnit.SECONDS); }
                    catch (java.util.concurrent.TimeoutException stillOwned) { if (child.isAlive()) child.destroyForcibly(); }
                }
                report.put("ownedProviderStopped", !server.isAlive());
            }
            if (output != null) Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
    }

    @Test
    @Timeout(value = 25, unit = TimeUnit.MINUTES)
    void actualModelRecallAgainstFrozenRelevanceLabels() throws Exception {
        for (var document : definition.path("documents")) {
            String pid = memories.createScopedMemory(tenant, agent, "fact", "test", document.path("title").asText(),
                    document.path("content").asText(), 5, false, "user", user);
            assertThat(writer.computeAndStore(pid)).as("real model write for %s", document.path("id").asText()).isTrue();
            documentPids.put(document.path("id").asText(), pid);
        }
        String duplicate = definition.path("documents").get(6).path("content").asText();
        for (String variant : List.of("private", "foreign", "expired", "deleted", "wrong-space", "stale")) {
            String pid = memories.createScopedMemory(tenant, agent, "fact", "test", variant, duplicate, 0, false, "user", user);
            assertThat(writer.computeAndStore(pid)).isTrue(); forbidden.add(pid);
            switch (variant) {
                case "private" -> jdbc.update("UPDATE ab_agent_memory SET scope_key=? WHERE pid=?", "other-user", pid);
                case "foreign" -> jdbc.update("UPDATE ab_agent_memory SET tenant_id=? WHERE pid=?", tenant + 1000, pid);
                case "expired" -> jdbc.update("UPDATE ab_agent_memory SET expires_at=NOW()-INTERVAL '1 day' WHERE pid=?", pid);
                case "deleted" -> jdbc.update("UPDATE ab_agent_memory SET deleted_flag=TRUE WHERE pid=?", pid);
                case "wrong-space" -> jdbc.update("UPDATE ab_agent_memory SET embedding_space=? WHERE pid=?", "f".repeat(64), pid);
                case "stale" -> jdbc.update("UPDATE ab_agent_memory SET memory_content=memory_content||' edited' WHERE pid=?", pid);
                default -> throw new IllegalArgumentException(variant);
            }
        }
        report.put("forbiddenPids", forbidden);
        Map<String,String> inverse = new HashMap<>(); documentPids.forEach((id,pid) -> inverse.put(pid,id));
        for (var query : definition.path("queries")) {
            long started = System.nanoTime();
            String text = query.path("text").asText();
            var vector = embedding.embedWithIdentity(tenant, text, provider);
            assertThat(vector).isNotNull(); assertThat(vector.values()).hasSize(1536);
            var semantic = memories.searchScopedSemanticInSpace(tenant, user, agent, vector, 5);
            var semanticPids = semantic.stream().map(row -> row.get("pid").toString()).toList();
            assertThat(semanticPids).doesNotContainAnyElementsOf(forbidden);
            var keyword = memories.searchScoped(tenant, user, agent, text, 5);
            ReflectionTestUtils.setField(active, "semanticRecallEnabled", false);
            var baseline = active.preRecall(tenant, user, agent, text);
            ReflectionTestUtils.setField(active, "semanticRecallEnabled", true);
            var treatment = active.preRecall(tenant, user, agent, text);
            ReflectionTestUtils.setField(active, "semanticRecallEnabled", false);
            Set<String> relevant = new HashSet<>(json.convertValue(query.path("relevant"), List.class));
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("queryId", query.path("id").asText()); row.put("query", text); row.put("kind", query.path("kind").asText());
            row.put("space", vector.space()); row.put("dimensions", vector.values().length);
            row.put("queryVector", vector.values());
            double norm=0; for (float value: vector.values()) norm+=(double)value*value;
            row.put("queryNorm",Math.sqrt(norm));
            row.put("semanticPids", semanticPids);
            row.put("semantic", ids(semantic,inverse)); row.put("keyword", ids(keyword,inverse));
            row.put("activeOff", ids(baseline,inverse)); row.put("activeOn", ids(treatment,inverse));
            row.put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
            row.put("recallAt5", recall(ids(semantic,inverse), relevant, 5));
            row.put("mrrAt5", reciprocalRank(ids(semantic,inverse), relevant, 5));
            row.put("activeOffRecallAt8", recall(ids(baseline,inverse), relevant, 8));
            row.put("activeOnRecallAt8", recall(ids(treatment,inverse), relevant, 8));
            List<Double> scores = new ArrayList<>();
            String literal = Arrays.toString(vector.values()).replace(" ", "");
            for (String pid : semanticPids) scores.add(jdbc.queryForObject("SELECT 1-(embedding <=> ?::vector) FROM ab_agent_memory WHERE pid=?", Double.class, literal,pid));
            row.put("similarities", scores); rows.add(row); checkpoint();
        }
        assertThat(rows).hasSize(30); assertThat(documentPids).hasSize(30);
        report.put("storedVectors", jdbc.queryForList("SELECT pid,embedding::text AS vector,embedding_space,embedding_content_hash FROM ab_agent_memory WHERE memory_agent_id=?", agent));
        report.put("queryCount",rows.size()); report.put("scopeAndLifecyclePassed",true); checkpoint();
    }

    private static List<String> ids(List<Map<String,Object>> rows, Map<String,String> inverse) {
        return rows.stream().map(row -> inverse.getOrDefault(row.get("pid").toString(), "NON_CORPUS:"+row.get("pid"))).toList();
    }
    static double recall(List<String> ranked, Set<String> relevant, int k) {
        if (relevant.isEmpty()) return 0;
        return ranked.stream().limit(k).distinct().filter(relevant::contains).count() / (double) relevant.size();
    }
    static double reciprocalRank(List<String> ranked, Set<String> relevant, int k) {
        for (int i=0;i<Math.min(k,ranked.size());i++) if (relevant.contains(ranked.get(i))) return 1.0/(i+1);
        return 0;
    }
    private void checkpoint() throws Exception {
        Files.writeString(output,json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
}
