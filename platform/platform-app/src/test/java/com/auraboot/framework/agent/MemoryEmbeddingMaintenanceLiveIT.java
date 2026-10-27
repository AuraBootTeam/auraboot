package com.auraboot.framework.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import com.auraboot.framework.agent.service.AgentMemoryService;
import com.auraboot.framework.agent.service.MemoryEmbeddingService;
import com.auraboot.framework.agent.service.MemoryEmbeddingRebuildService;
import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.application.security.AdminRoleChecker;
import com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.AuditTrailEvent;
import com.auraboot.framework.meta.service.impl.AuditTrailService;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.rag.service.EmbeddingService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real HTTP/JWT, PostgreSQL and managed Qwen; spies inject faults, never synthetic vectors. */
@Tag("agent-eval-live")
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MemoryEmbeddingMaintenanceLiveIT extends BaseIntegrationTest {
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired AgentMemoryService memory;
    @Autowired MemoryEmbeddingService writer;
    @Autowired MemoryEmbeddingRebuildService maintenance;
    @Autowired CloudConfigService cloud;
    @Autowired com.auraboot.framework.saas.config.service.SystemConfigService systemConfig;
    @Autowired RoleService roles;
    @Autowired UserRoleService userRoles;
    @Autowired AdminRoleChecker admin;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean EmbeddingService embedding;
    @MockitoSpyBean AuditTrailService audit;
    @LocalServerPort int port;
    String token, agent, space;
    Long tenant;
    final List<String> passed = new ArrayList<>();

    JsonNode request(String path, Object body) throws Exception {
        var headers = new HttpHeaders(); headers.setBearerAuth(token); headers.setContentType(MediaType.APPLICATION_JSON);
        var response = rest.exchange(path, body == null ? HttpMethod.GET : HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        if (response.getStatusCode().value() != 200) {
            var error = json.readTree(response.getBody());
            throw new AssertionError("HTTP " + response.getStatusCode().value() + " at " + path + ": code=" + error.path("code").asText() + ", message=" + error.path("message").asText());
        }
        return json.readTree(response.getBody());
    }
    JsonNode inspect(String pid) throws Exception { return request("/api/admin/memory/embeddings/" + pid + "?agentCode=" + agent, null); }
    JsonNode rebuild(String pid, String expected) throws Exception {
        return request("/api/admin/memory/embeddings/" + pid + "/rebuild", Map.of("agentCode", agent, "expectedSpace", expected, "reason", "isolated readiness qualification"));
    }
    String fixture(String suffix) {
        return memory.createScopedMemory(tenant, agent, "fact", "test", "Maintenance " + suffix,
                "MAINT-" + suffix + " 本轮测试审批记录保留三十天。", 5, false, "user", getTestUser().getId().toString());
    }
    Map<String,Object> row(String pid) { return jdbc.queryForMap("SELECT embedding::text AS vector,embedding_space,embedding_content_hash,embedding_generated_at FROM ab_agent_memory WHERE pid=?", pid); }
    int audits(String pid) { return jdbc.queryForObject("SELECT COUNT(*) FROM ab_audit_trail WHERE entity_pid=? AND event_type='MEMORY_VECTOR_REBUILT'", Integer.class, pid); }

    AuditTrailService auditTarget() { return AopTestUtils.getUltimateTargetObject(audit); }

    @Test @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void scopedRebuildIsAtomicAuditedAndResumableThroughAuthenticatedHttp() throws Exception {
        String oldAllow = System.getProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS");
        String oldMode = systemConfig.getOrDefault("system.mode", "single");
        var report = new LinkedHashMap<String,Object>(); report.put("checks", passed);
        Path output = Path.of(Objects.requireNonNull(System.getenv("AURA_MEMORY_MAINTENANCE_REPORT")));
        try {
            systemConfig.initialize("system.mode", "multi", "system", "string", "Isolated maintenance qualification mode", false);
            systemConfig.set("system.mode", "multi");
            tenant = getTestTenant().getId(); agent = "maintenance-" + UUID.randomUUID();
            System.setProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS", "127.0.0.1");
            var config = new CloudConfigSaveRequest(); config.setConfigLevel("tenant"); config.setServiceType("embedding"); config.setProviderCode("local_qwen"); config.setEnabled(true); config.setPriority(-200);
            var existing = cloud.getEffectiveConfig(tenant, "embedding", "local_qwen");
            if (existing != null && tenant.equals(existing.getTenantId())) config.setPid(existing.getPid());
            config.setConfig(json.writeValueAsString(Map.of("apiKey", "local-ollama-no-auth", "baseUrl", System.getenv("AURA_LOCAL_EMBEDDING_ENDPOINT"), "defaultModel", "qwen3-embedding:4b", "dimensions", 1536, "maxBatchSize", 1,
                    "spaceVersion", "qwen4b-2b0cf8f17b4c723c27303015383c27ec4bf2d8314bb677d05e920dd70bb0f16b")));
            cloud.saveConfig(config); space = embedding.currentSpace(tenant, null, 1536); assertThat(space).matches("[a-f0-9]{64}");
            var login = json.readTree(rest.postForObject("/api/auth/login", Map.of("email", "integration-test@auraboot.com", "password", "test-password-123"), String.class));
            assertThat(login.path("code").asText()).isEqualTo("0"); token = login.path("data").path("jwt").asText();
            var selected = request("/api/tenant-selection/process", Map.of("action", "select", "tenantId", tenant.toString()));
            assertThat(selected.path("code").asText()).isEqualTo("0"); token = selected.path("data").path("jwt").asText(); assertThat(token).isNotBlank();
            Role role = roles.findByTenantId(tenant).stream().filter(r -> "tenant_admin".equals(r.getCode())).findFirst().orElse(null);
            if (role == null) { role = new Role(); role.setCode("tenant_admin"); role.setName("Tenant maintenance admin"); role.setTenantId(tenant); role.setStatus("active"); role.setType("TENANT"); role = roles.createRole(role); }
            userRoles.assignRolesToMember(getTestTenantMember().getId(), List.of(getTestRole().getId(), role.getId()), tenant, null);
            var grant = userRoles.findByMemberIdAndRoleIdAndTenantId(getTestTenantMember().getId(), role.getId(), tenant);
            grant.setStatus("inactive"); userRoles.updateById(grant); admin.invalidateAll();
            String legacy = fixture("legacy"), missing = fixture("missing");
            assertThat(rest.getForEntity("/api/admin/memory/embeddings?agentCode=" + agent, String.class).getStatusCode().value()).isEqualTo(401);
            assertThat(inspect(legacy).path("code").asText()).isEqualTo("409");
            grant.setStatus("active"); userRoles.updateById(grant); admin.invalidateAll();
            grantCommittedPermissionToTestRole(MetaPermission.ACP_MEMORY_ADMIN, "API", "acp_memory", "admin", "Memory maintenance");
            passed.add("anonymous_and_non_admin_denied");
            assertThat(writer.computeAndStore(legacy)).isTrue(); var original = row(legacy);
            jdbc.update("UPDATE ab_agent_memory SET embedding_space=NULL,embedding_content_hash=NULL WHERE pid=?", legacy);
            var inventory = request("/api/admin/memory/embeddings?agentCode=" + agent + "&limit=1", null);
            assertThat(inventory.path("code").asText()).isEqualTo("0"); assertThat(inventory.path("data").path("hasMore").asBoolean()).isTrue();
            assertThat(inventory.toString()).doesNotContain("memory_content", "embedding_text", "本轮测试");
            assertThat(inspect(legacy).path("data").path("status").asText()).isEqualTo("unknown_space");
            assertThat(inspect(missing).path("data").path("status").asText()).isEqualTo("missing");
            assertThat(request("/api/admin/memory/embeddings/" + legacy + "?agentCode=other-agent", null).path("code").asText()).isEqualTo("404");
            assertThat(inspect(legacy).path("data").path("tenantId").asText()).isEqualTo(tenant.toString());
            assertThat(rebuild(legacy, "0".repeat(64)).path("code").asText()).isEqualTo("409"); assertThat(audits(legacy)).isZero();
            String foreign = memory.createScopedMemory(tenant + 123, agent, "fact", "test", "Private other tenant", "must not be visible", 5, false, "tenant", null);
            assertThat(inspect(foreign).path("code").asText()).isEqualTo("404"); assertThat(rebuild(foreign, space).path("code").asText()).isEqualTo("404");
            assertThat(row(foreign).get("vector")).isNull(); passed.add("inventory_scope_privacy_fixed_space");
            assertThat(rebuild(legacy, space).path("data").path("outcome").asText()).isEqualTo("rebuilt"); assertThat(audits(legacy)).isEqualTo(1);
            var before = json.readTree(jdbc.queryForObject("SELECT before_snapshot::text FROM ab_audit_trail WHERE entity_pid=? AND event_type='MEMORY_VECTOR_REBUILT'", String.class, legacy));
            assertThat(before.path("embedding_text").asText()).isEqualTo(original.get("vector")); assertThat(before.path("embedding_space").isNull()).isTrue();
            var after = row(legacy); assertThat(rebuild(legacy, space).path("data").path("outcome").asText()).isEqualTo("already_current");
            assertThat(row(legacy)).isEqualTo(after); assertThat(audits(legacy)).isEqualTo(1); passed.add("exact_before_image_atomic_audit_and_idempotence");
            String rollback = fixture("audit-failure"); var rollbackBefore = row(rollback);
            doThrow(new IllegalStateException("injected audit failure")).when(auditTarget()).recordInCurrentTransaction(any());
            assertThatThrownBy(() -> maintenance.rebuild(tenant, getTestUser().getId(), agent, rollback, space, "rollback probe")).isInstanceOf(IllegalStateException.class);
            reset(auditTarget()); assertThat(row(rollback)).isEqualTo(rollbackBefore); assertThat(audits(rollback)).isZero();
            assertThatThrownBy(() -> audit.recordInCurrentTransaction(AuditTrailEvent.builder().tenantId(tenant).build())).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
            assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> maintenance.rebuild(tenant, getTestUser().getId(), agent, rollback, space, "outer transaction rejected")))
                    .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class); passed.add("audit_failure_rolls_back_and_transaction_contracts");
            String race = fixture("race"); CountDownLatch computed = new CountDownLatch(1), release = new CountDownLatch(1);
            doAnswer(invocation -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).as("model outside transaction").isFalse();
                var result = invocation.callRealMethod(); computed.countDown(); assertThat(release.await(30, TimeUnit.SECONDS)).isTrue(); return result;
            }).when(embedding).embedWithIdentity(anyLong(), anyString(), isNull());
            try (var pool = Executors.newSingleThreadExecutor()) {
                var future = pool.submit(() -> rebuild(race, space)); assertThat(computed.await(60, TimeUnit.SECONDS)).isTrue();
                jdbc.update("UPDATE ab_agent_memory SET memory_content=? WHERE pid=?", "Concurrent edited memory", race); release.countDown();
                assertThat(future.get(60, TimeUnit.SECONDS).path("code").asText()).isEqualTo("409");
            } finally { release.countDown(); reset(embedding); }
            assertThat(row(race).get("vector")).isNull(); assertThat(audits(race)).isZero(); passed.add("real_model_outside_transaction_and_content_CAS");
            String concurrent = fixture("concurrent"); CountDownLatch both = new CountDownLatch(2), apply = new CountDownLatch(1);
            doAnswer(invocation -> {
                var result = invocation.callRealMethod(); both.countDown(); assertThat(apply.await(60, TimeUnit.SECONDS)).isTrue(); return result;
            }).when(embedding).embedWithIdentity(anyLong(), anyString(), isNull());
            try (var pool = Executors.newFixedThreadPool(2)) {
                var first = pool.submit(() -> rebuild(concurrent, space)); var second = pool.submit(() -> rebuild(concurrent, space));
                assertThat(both.await(60, TimeUnit.SECONDS)).isTrue(); apply.countDown();
                assertThat(List.of(first.get(60, TimeUnit.SECONDS).path("code").asText(), second.get(60, TimeUnit.SECONDS).path("code").asText())).containsExactlyInAnyOrder("0", "409");
            } finally { apply.countDown(); reset(embedding); }
            assertThat(audits(concurrent)).isEqualTo(1); passed.add("concurrent_real_inference_single_CAS_and_audit");
            String inactive = fixture("inactive"); jdbc.update("UPDATE ab_agent_memory SET deleted_flag=TRUE WHERE pid=?", inactive);
            assertThat(inspect(inactive).path("data").path("status").asText()).isEqualTo("inactive"); assertThat(rebuild(inactive, space).path("code").asText()).isEqualTo("404"); passed.add("inactive_rows_not_rebuilt");
            String expired = fixture("expired"); jdbc.update("UPDATE ab_agent_memory SET valid_until=NOW()-INTERVAL '1 day' WHERE pid=?", expired);
            assertThat(inspect(expired).path("data").path("status").asText()).isEqualTo("inactive"); assertThat(rebuild(expired, space).path("code").asText()).isEqualTo("404");
            String stale = fixture("stale"); assertThat(writer.computeAndStore(stale)).isTrue();
            jdbc.update("UPDATE ab_agent_memory SET memory_content=? WHERE pid=?", "Updated policy now sixty days", stale);
            assertThat(inspect(stale).path("data").path("status").asText()).isEqualTo("stale_content");
            assertThat(rebuild(stale, space).path("data").path("outcome").asText()).isEqualTo("rebuilt");
            jdbc.update("UPDATE ab_agent_memory SET embedding_space=? WHERE pid=?", "b".repeat(64), stale);
            assertThat(inspect(stale).path("data").path("status").asText()).isEqualTo("different_space");
            assertThat(rebuild(stale, space).path("data").path("outcome").asText()).isEqualTo("rebuilt");
            passed.add("expired_stale_and_different_space");
            Path cliRoot = output.getParent().resolve("checkpoint-" + agent); Files.createDirectories(cliRoot);
            Path manifest = cliRoot.resolve("targets.json"), checkpoint = cliRoot.resolve("checkpoint.json");
            Files.writeString(manifest, json.writeValueAsString(Map.of("baseUrl", "http://127.0.0.1:" + port, "tenantId", tenant.toString(), "agentCode", agent,
                    "expectedSpace", space, "reason", "bounded resume qualification", "pids", List.of(missing, rollback))));
            String cli = Objects.requireNonNull(System.getenv("AURA_MEMORY_MAINTENANCE_CLI"));
            for (int round = 0; round < 3; round++) {
                var args = new ArrayList<>(List.of("node", cli, "--manifest", manifest.toString(), "--checkpoint", checkpoint.toString(), "--token-env", "AURA_MAINTENANCE_TOKEN", "--max-items", "1")); if (round > 0) args.add("--resume");
                var builder = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(cliRoot.resolve("round-" + round + ".log").toFile());
                builder.environment().put("AURA_MAINTENANCE_TOKEN", token); var process = builder.start();
                assertThat(process.waitFor(120, TimeUnit.SECONDS)).isTrue(); assertThat(process.exitValue()).isEqualTo(round == 0 ? 3 : 0);
            }
            assertThat(audits(missing)).isEqualTo(1); assertThat(audits(rollback)).isEqualTo(1);
            var checkpointState = json.readTree(Files.readString(checkpoint)); assertThat(checkpointState.path("completed").size()).isEqualTo(2); assertThat(checkpointState.path("remaining").isEmpty()).isTrue(); passed.add("real_http_cli_partial_resume_and_completed_noop");
            Long max = jdbc.queryForObject("SELECT MAX(sequence_no) FROM ab_audit_trail WHERE tenant_id=?", Long.class, tenant);
            assertThat(audit.verifyChainIntegrity(tenant, 1L, max).isValid()).isTrue(); passed.add("audit_chain_integrity");
            report.put("database", jdbc.queryForObject("SELECT current_database()", String.class)); report.put("tenantId", tenant.toString()); report.put("agentCode", agent); report.put("space", space); report.put("checkpoint", checkpoint.toString()); report.put("status", "pass");
        } finally {
            reset(embedding, auditTarget()); admin.invalidateAll(); systemConfig.set("system.mode", oldMode);
            if (oldAllow == null) System.clearProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS"); else System.setProperty("AURA_SSRF_ALLOWED_PRIVATE_HOSTS", oldAllow);
            Files.createDirectories(output.getParent()); Files.writeString(output, json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        }
    }
}
