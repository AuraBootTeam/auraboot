package com.auraboot.framework.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.auraboot.framework.agent.service.ActiveMemoryService;
import com.auraboot.framework.agent.service.AgentMemoryService;
import com.auraboot.framework.agent.service.MemoryEmbeddingService;
import com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.rag.service.EmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Explicit live qualification of the managed local provider through actual memory services. */
@Tag("agent-eval-live")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ManagedMemoryEmbeddingLiveIT extends BaseIntegrationTest {
    @Autowired private CloudConfigService cloud;
    @Autowired private EmbeddingService embedding;
    @Autowired private MemoryEmbeddingService writer;
    @Autowired private AgentMemoryService memory;
    @Autowired private ActiveMemoryService active;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    private final List<Map<String, Object>> lifecycle = new ArrayList<>();

    private Map<String, Object> service(String action) throws Exception {
        String script = System.getenv("AURA_LOCAL_EMBEDDING_LAUNCHER");
        String runtime = System.getenv("AURA_LOCAL_EMBEDDING_RUNTIME");
        String workspace = System.getenv("AURA_LOCAL_EMBEDDING_WORKSPACE");
        assertThat(script).isNotBlank(); assertThat(runtime).isNotBlank(); assertThat(workspace).isNotBlank();
        Path log = Files.createTempFile("managed-embedding-", ".json");
        try {
            Process process = new ProcessBuilder("node", script, action, runtime, "--workspace", workspace)
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("Managed operation must finish").isTrue();
            String output = Files.readString(log);
            assertThat(process.exitValue()).as("Managed operation %s: %s", action, output).isZero();
            @SuppressWarnings("unchecked") Map<String, Object> result = json.readValue(output, Map.class);
            lifecycle.add(Map.of("action", action, "result", result)); return result;
        } finally { Files.deleteIfExists(log); }
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void configuredProviderSurvivesRestartAndPreservesDefaultMemoryFallback() throws Exception {
        assertThat(System.getenv("AURA_MANAGED_EMBEDDING_REPORT")).isNotBlank();
        String oldAllowlist = System.getProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS");
        Map<String, Object> report = new LinkedHashMap<>(); report.put("lifecycle", lifecycle);
        boolean stopped = false;
        try {
            Map<String, Object> before = service("status");
            System.setProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS", "127.0.0.1");
            Long tenant = getTestTenant().getId();
            CloudConfigSaveRequest request = new CloudConfigSaveRequest();
            request.setConfigLevel("tenant"); request.setServiceType("embedding"); request.setProviderCode("local_qwen");
            request.setEnabled(true); request.setPriority(-200);
            request.setConfig(json.writeValueAsString(Map.of("apiKey", "local-ollama-no-auth", "baseUrl", before.get("endpoint"),
                    "defaultModel", "qwen3-embedding:4b", "dimensions", 1536, "maxBatchSize", 1,
                    "spaceVersion", "qwen4b-" + before.get("blobSha256"))));
            cloud.saveConfig(request);
            assertThat(cloud.getEnabledProviders(tenant, "embedding").getFirst().getProviderCode()).isEqualTo("local_qwen");
            assertThat(ReflectionTestUtils.getField(active, "semanticRecallEnabled")).isEqualTo(false);
            String agent = "managed-" + UUID.randomUUID();
            String user = getTestUser().getId().toString();
            String pid = memory.createScopedMemory(tenant, agent, "fact", "test", "Managed embedding readiness",
                    "EMBED-READY-001 使用受管本地向量服务，维度为1536。", 5, false, "user", user);
            assertThat(writer.computeAndStore(pid)).isTrue();
            var vector = embedding.embedWithIdentity(tenant, "受管本地向量服务的维度是多少？", "local_qwen");
            assertThat(vector).isNotNull(); assertThat(vector.values()).hasSize(1536);
            assertThat(memory.searchScopedSemanticInSpace(tenant, user, agent, vector, 1)).extracting(row -> row.get("pid")).containsExactly(pid);
            service("down"); stopped = true;
            assertThat(active.preRecall(tenant, user, agent, "EMBED-READY-001")).extracting(row -> row.get("pid")).contains(pid);
            ReflectionTestUtils.setField(active, "semanticRecallEnabled", true);
            assertThat(active.preRecall(tenant, user, agent, "EMBED-READY-001")).extracting(row -> row.get("pid")).contains(pid);
            ReflectionTestUtils.setField(active, "semanticRecallEnabled", false);
            Map<String, Object> after = service("resume"); stopped = false;
            assertThat(after.get("pid")).isNotEqualTo(before.get("pid"));
            assertThat(after.get("manifestSha256")).isEqualTo(before.get("manifestSha256"));
            var restored = embedding.embedWithIdentity(tenant, "EMBED-READY-001", "local_qwen");
            assertThat(restored).isNotNull(); assertThat(restored.space()).isEqualTo(vector.space());
            report.put("actualDatabase", jdbc.queryForObject("SELECT current_database()", String.class));
            report.put("tenantId", tenant); report.put("memoryPid", pid); report.put("provider", "local_qwen");
            report.put("space", restored.space()); report.put("dimensions", restored.values().length);
            report.put("defaultSemanticEnabled", false); report.put("defaultAndOptInOutageFallbackPassed", true);
            report.put("status", "pass");
        } finally {
            ReflectionTestUtils.setField(active, "semanticRecallEnabled", false);
            if (oldAllowlist == null) System.clearProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS");
            else System.setProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS", oldAllowlist);
            if (stopped) service("resume");
            Path output = Path.of(System.getenv("AURA_MANAGED_EMBEDDING_REPORT"));
            Files.createDirectories(output.getParent()); Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
    }
}
