package com.auraboot.framework.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.auraboot.framework.agent.memory.MemoryVectorPolicy;
import com.auraboot.framework.agent.memory.MemoryL1L2Promoter;
import com.auraboot.framework.agent.metrics.MemoryPromotionMetrics;
import com.auraboot.framework.cloudconfig.entity.CloudConfig;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.rag.service.EmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;

/** Real HTTP + PostgreSQL qualification; controlled vectors prove safety, not semantic model quality. */
@Tag("agent-memory-postgres")
class MemoryVectorSpacePostgresTest {
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private MemoryEmbeddingService vectors;
    private AgentMemoryService memories;
    private EmbeddingService embedding;
    private HttpServer server;
    private String endpoint;
    private String oldAllowlist;
    private boolean changedAllowlist;
    private long tenant;
    private String agent;
    private final AtomicReference<CloudConfig> config = new AtomicReference<>();
    private final AtomicReference<Runnable> duringRequest = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger responseDimensions = new AtomicInteger(1536);

    @BeforeEach
    void prepare() throws Exception {
        String url = System.getenv("TEST_DATABASE_URL");
        if (url == null) throw new IllegalStateException("Explicit isolated migrated PostgreSQL target required");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, System.getenv("TEST_DATABASE_USERNAME"), System.getenv("TEST_DATABASE_PASSWORD")));
        tenant = Math.abs(System.nanoTime()); agent = "vector-" + UniqueIdGenerator.generate();
        oldAllowlist = System.getProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS");
        System.setProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS", "127.0.0.1");changedAllowlist=true;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            try {
                var body = json.readTree(exchange.getRequestBody().readAllBytes());
                String model = body.path("model").asText();
                if (!Set.of("model-a", "model-b").contains(model) || body.path("input").size()!=1) {
                    exchange.sendResponseHeaders(400,-1); return;
                }
                requests.incrementAndGet();
                Runnable callback = duringRequest.getAndSet(null);
                if (callback != null) callback.run();
                float[] vector = new float[responseDimensions.get()]; vector[model.equals("model-a") ? 0 : 1] = 1;
                byte[] bytes = json.writeValueAsBytes(Map.of("data", List.of(Map.of("index",0,"embedding",vector))));
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();endpoint="http://127.0.0.1:"+server.getAddress().getPort();
        setConfig("model-a", "initial-key", "");
        var cloud = mock(CloudConfigService.class);
        when(cloud.getEnabledProviders(anyLong(), eq("embedding"))).thenAnswer(call -> List.of(config.get()));
        when(cloud.getEffectiveConfig(anyLong(), eq("embedding"), anyString())).thenAnswer(call -> config.get());
        embedding = new EmbeddingService(cloud,json);
        vectors = new MemoryEmbeddingService(embedding,jdbc); memories = new AgentMemoryService(jdbc);
    }

    @AfterEach
    void stopOnlyOwnedFixture() {
        if (server != null) server.stop(0);
        if (!changedAllowlist) return;
        if (oldAllowlist == null) System.clearProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS");
        else System.setProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS",oldAllowlist);
        // Preserve all seeded rows, requests and stored-vector identities for inspection.
    }

    private void setConfig(String model, String key, String revision) throws Exception {
        CloudConfig c = new CloudConfig();c.setProviderCode("fixture");
        c.setConfig(json.writeValueAsString(Map.of("apiKey",key,"baseUrl",endpoint,"defaultModel",model,"dimensions",1536,"spaceVersion",revision)));
        config.set(c);
    }
    private String seed(String content) {
        return memories.createScopedMemory(tenant,agent,"fact","user",content,content,8,false,"user","alice");
    }
    private String literal() { return "[1,"+"0,".repeat(1534)+"0]"; }
    private void setVector(String pid, String space) {
        jdbc.update("UPDATE ab_agent_memory SET embedding=?::vector,embedding_space=?,embedding_content_hash=encode(sha256(convert_to(memory_content,'UTF8')),'hex') WHERE pid=?",literal(),space,pid);
    }

    @Test
    void identityUsesTheRequestSnapshotAndNeverTheRotatingApiKey() throws Exception {
        var a=embedding.embedWithIdentity(tenant,"content",null);assertThat(a).isNotNull();
        assertThat(a.space()).isEqualTo(embedding.currentSpace(tenant,null,1536));
        setConfig("model-a","rotated-key","");assertThat(embedding.currentSpace(tenant,null,1536)).isEqualTo(a.space());
        String originalEndpoint=endpoint;endpoint += "/other";
        setConfig("model-a","rotated-key","");assertThat(embedding.currentSpace(tenant,null,1536)).isNotEqualTo(a.space());
        endpoint=originalEndpoint;setConfig("model-a","rotated-key","");
        duringRequest.set(() -> {try {setConfig("model-b","rotated-key","");} catch(Exception e) {throw new IllegalStateException(e);}});
        var inFlight=embedding.embedWithIdentity(tenant,"content",null);
        assertThat(inFlight.space()).isEqualTo(a.space()).isNotEqualTo(embedding.currentSpace(tenant,null,1536));
        setConfig("model-a","rotated-key","");String pid=seed("In-flight model switch");
        duringRequest.set(() -> {try {setConfig("model-b","rotated-key","");} catch(Exception e) {throw new IllegalStateException(e);}});
        assertThat(vectors.computeAndStore(pid)).isTrue();
        assertThat(jdbc.queryForObject("SELECT embedding_space FROM ab_agent_memory WHERE pid=?",String.class,pid)).isEqualTo(a.space());
        assertThat(vectors.resolveVersionedEmbedding(pid)).isNull();
        setConfig("model-a","rotated-key","epoch-2");assertThat(embedding.currentSpace(tenant,null,1536)).isNotEqualTo(a.space());
        assertThat(embedding.currentSpace(tenant,null,1024)).isNotEqualTo(a.space());
        assertThat(a.toString()).doesNotContain("initial-key","rotated-key");
    }

    @Test
    void unknownVectorsRemainStoredUntilExplicitRebuild() {
        String pid=seed("Legacy memory");setVector(pid,null);
        assertThat(vectors.resolveVersionedEmbedding(pid)).isNull();assertThat(requests.get()).isZero();
        assertThat(memories.searchScoped(tenant,"alice",agent,"Legacy",10)).hasSize(1);
        assertThat(vectors.computeAndStore(pid)).isTrue();assertThat(requests.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT embedding_generated_at IS NOT NULL FROM ab_agent_memory WHERE pid=?",Boolean.class,pid)).isTrue();
        var ready=vectors.resolveVersionedEmbedding(pid);assertThat(ready).isNotNull();
        assertThat(memories.searchScopedSemanticInSpace(tenant,"alice",agent,ready,10)).hasSize(1);
        assertThat(vectors.computeAndStore(pid)).isFalse();assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void modelChangesQuarantineOldVectorsWithoutImplicitNetworkOrCrossSpaceHits() throws Exception {
        String a=seed("Current memory");assertThat(vectors.computeAndStore(a)).isTrue();
        var old=vectors.resolveVersionedEmbedding(a);
        String other=seed("Other old memory");setVector(other,old.space());
        String legacy=seed("Unversioned closest neighbour");setVector(legacy,null);
        setConfig("model-b","same-key","");
        assertThat(vectors.resolveVersionedEmbedding(a)).isNull();assertThat(requests.get()).isEqualTo(1);
        assertThat(vectors.computeAndStore(a)).isTrue();var current=vectors.resolveVersionedEmbedding(a);
        assertThat(current.space()).isNotEqualTo(old.space());
        assertThat(memories.searchScopedSemanticInSpace(tenant,"alice",agent,current,20))
                .extracting(r -> r.get("pid")).containsExactly(a);
        jdbc.update("UPDATE ab_agent_memory SET memory_content='Edited content' WHERE pid=?",a);
        assertThat(vectors.resolveVersionedEmbedding(a)).isNull();
        assertThat(memories.searchScopedSemanticInSpace(tenant,"alice",agent,current,20)).isEmpty();
        assertThat(vectors.computeAndStore(a)).isTrue();
        assertThat(memories.searchScopedSemanticInSpace(tenant,"alice",agent,current,20)).hasSize(1);
    }

    @Test
    void inFlightContentEditAndConcurrentVectorUpdateCannotBeOverwritten() {
        String pid=seed("Original body");
        duringRequest.set(() -> jdbc.update("UPDATE ab_agent_memory SET memory_content='Concurrent edit' WHERE pid=?",pid));
        assertThat(vectors.computeAndStore(pid)).isFalse();
        assertThat(jdbc.queryForObject("SELECT embedding IS NULL FROM ab_agent_memory WHERE pid=?",Boolean.class,pid)).isTrue();
        duringRequest.set(() -> setVector(pid,"b".repeat(64)));
        assertThat(vectors.computeAndStore(pid)).isFalse();
        assertThat(jdbc.queryForObject("SELECT embedding_space FROM ab_agent_memory WHERE pid=?",String.class,pid)).isEqualTo("b".repeat(64));
        assertThat(vectors.computeAndStore(pid)).isTrue();
    }

    @Test
    void dimensionMismatchDoesNotWriteOrEnterTheVectorQuery() {
        String pid=seed("Dimension fixture");responseDimensions.set(1024);
        assertThat(vectors.computeAndStore(pid)).isFalse();
        assertThat(jdbc.queryForObject("SELECT embedding IS NULL FROM ab_agent_memory WHERE pid=?",Boolean.class,pid)).isTrue();
        assertThat(memories.searchScopedSemanticInSpace(tenant,"alice",agent,
                new EmbeddingService.EmbeddedVector(new float[]{1,0},"a".repeat(64)),10)).isEmpty();
    }

    @Test
    void semanticDedupHasSameSpaceAndFreshContentPositiveAndNegativeCases() {
        var promoter=mock(MemoryL1L2Promoter.class,CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(promoter,"jdbc",jdbc);
        for (String scope : List.of("user","tenant")) {
            String scopeKey=scope.equals("user") ? "alice" : null;
        String candidate=seed("Candidate");String target=seed("L2 target");
        jdbc.update("UPDATE ab_agent_memory SET scope=?,scope_key=? WHERE pid IN (?,?)",scope,scopeKey,candidate,target);
        setVector(candidate,"a".repeat(64));setVector(target,"b".repeat(64));
        assertThat((Double)ReflectionTestUtils.invokeMethod(promoter,"queryMaxCosineToL2",tenant,scope,scopeKey,candidate)).isZero();
        assertThat((String)ReflectionTestUtils.invokeMethod(promoter,"findNearestL2BySemantic",tenant,scope,scopeKey,candidate,0.92)).isNull();
        setVector(target,"a".repeat(64));
        assertThat((Double)ReflectionTestUtils.invokeMethod(promoter,"queryMaxCosineToL2",tenant,scope,scopeKey,candidate)).isEqualTo(1);
        assertThat((String)ReflectionTestUtils.invokeMethod(promoter,"findNearestL2BySemantic",tenant,scope,scopeKey,candidate,0.92)).isEqualTo(target);
        jdbc.update("UPDATE ab_agent_memory SET memory_content='Unembedded edit' WHERE pid=?",target);
        assertThat((Double)ReflectionTestUtils.invokeMethod(promoter,"queryMaxCosineToL2",tenant,scope,scopeKey,candidate)).isZero();
        }
    }

    @Test
    void annAndFallbackProduceSeparateSameSpaceClusters() {
        var extractor=new MemoryPromotionExtractor(jdbc,vectors,mock(MemoryPromotionMetrics.class),json,
                new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
        ReflectionTestUtils.setField(extractor,"minUsersPerTenant",3);
        ReflectionTestUtils.setField(extractor,"minSimilarity",0.85);
        for (boolean ann : List.of(true,false)) {
            tenant++;
        for (String space : List.of("a".repeat(64),"b".repeat(64))) {
            for (int i=0; i<3; i++) {
                String pid=seed("Cluster "+space.charAt(0)+i);
                jdbc.update("UPDATE ab_agent_memory SET scope_key=? WHERE pid=?",Integer.toString(i+1),pid);
                setVector(pid,space);
            }
        }
            for (String kind : List.of("expired","stale")) {
                for (int i=0; i<3; i++) {
                    String pid=seed(kind+" cluster "+i);
                    jdbc.update("UPDATE ab_agent_memory SET scope_key=? WHERE pid=?",Integer.toString(i+10),pid);
                    setVector(pid,(kind.equals("expired") ? "c" : "d").repeat(64));
                    if (kind.equals("expired")) jdbc.update("UPDATE ab_agent_memory SET valid_until=NOW()-INTERVAL '1 hour' WHERE pid=?",pid);
                    else jdbc.update("UPDATE ab_agent_memory SET memory_content='Changed after embedding' WHERE pid=?",pid);
                }
            }
            ReflectionTestUtils.setField(extractor,"usePgvectorShortlist",ann);
            int count=extractor.extractCrossUserAgreement(tenant,new HashSet<>());
            assertThat(count).isEqualTo(2);
            var proposals=jdbc.queryForList("SELECT source_memory_pids::text FROM ab_agent_memory_promotion WHERE tenant_id=?",String.class,tenant);
            assertThat(proposals).hasSize(2);
            for (String text : proposals) {
                List<?> pids=json.convertValue(readJson(text),List.class);
                assertThat(pids).hasSize(3);
                String marks=jdbc.queryForObject("SELECT string_agg(DISTINCT embedding_space,',') FROM ab_agent_memory WHERE pid IN (?,?,?)",String.class,pids.get(0),pids.get(1),pids.get(2));
                assertThat(marks).doesNotContain(",");
            }
        }
    }
    private Object readJson(String text) {try {return json.readTree(text);} catch(Exception failure) {throw new IllegalStateException(failure);}}
}
