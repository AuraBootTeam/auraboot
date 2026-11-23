package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.agent.integration.plugin.AgentDefinitionResourceAdapter;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.dto.uninstall.UninstallRequest;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.auraboot.framework.plugin.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real import/rollback/uninstall transactions; the mapper spy injects only read faults. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PluginAgentHandoverIntegrationTest extends BaseIntegrationTest {
    @Autowired PluginImportService imports;
    @Autowired PluginResourceService resources;
    @Autowired PluginManagerService lifecycle;
    @Autowired AgentDefinitionResourceAdapter adapter;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean PluginResourceMapper tracking;

    @Test
    void failedProtectionReadAbortsBeforeAgentOrEvalWrites() {
        String key = key(); var first = install(manifest("fault_" + key, agent(key, "Initial")), ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        JsonNode before = state(key); JsonNode beforeEval = evals(key);
        doThrow(new IllegalStateException("injected protection read failure")).when(tracking).findByTypeAndCode(tenant(), "agent_definition", key);
        doThrow(new IllegalStateException("injected protection read failure")).when(tracking).lockModificationState(tenant(), "agent_definition", key);
        try {
            assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> adapter.importAgentDefinition(
                    agent(key, "Changed"), first.getPluginPid(), UniqueIdGenerator.generate(), tenant(), ImportRequest.ConflictStrategy.OVERWRITE_SAFE)))
                    .hasMessageContaining("protection");
        } finally { reset(tracking); }
        assertThat(state(key)).isEqualTo(before); assertThat(evals(key)).isEqualTo(beforeEval);
    }

    @Test
    void nullProtectionMarkerRejectsPublicImportWithoutChangingAnything() {
        String key = key(); install(manifest("null_" + key, agent(key, "Initial")), ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        jdbc.update("UPDATE ab_plugin_resource SET user_modified=NULL WHERE tenant_id=? AND resource_type='agent_definition' AND resource_code=?", tenant(), key);
        JsonNode before = state(key); JsonNode beforeEval = evals(key);
        assertThatThrownBy(() -> install(manifest("null_" + key, agent(key, "Changed")), ImportRequest.ConflictStrategy.OVERWRITE_SAFE))
                .hasMessageContaining("protection");
        assertThat(state(key)).isEqualTo(before); assertThat(evals(key)).isEqualTo(beforeEval);
    }

    @Test
    void safeImportKeepsClaimedAndUntrackedDefinitions() {
        String key = key(); String untracked = key + "_legacy";
        install(manifest("preserve_" + key, agent(key, "Original"), agent(untracked, "Customer legacy")), ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        resources.claimByUser(tenant(), ResourceType.AGENT_DEFINITION, key);
        jdbc.update("DELETE FROM ab_plugin_resource WHERE tenant_id=? AND resource_type='agent_definition' AND resource_code=?", tenant(), untracked);
        JsonNode claimedBefore = state(key); JsonNode legacyBefore = state(untracked);
        install(manifest("preserve_" + key, agent(key, "Replacement"), agent(untracked, "Replacement")), ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        assertThat(state(key)).isEqualTo(claimedBefore); assertThat(state(untracked)).isEqualTo(legacyBefore);
        assertThat(resources.findByTypeAndCode(tenant(), ResourceType.AGENT_DEFINITION, key).getOwnershipType()).isEqualTo("user_claimed");
        assertThat(resources.findByTypeAndCode(tenant(), ResourceType.AGENT_DEFINITION, untracked)).isNull();
    }

    @Test
    void handoverSurvivesOldSourceSafeReimportAndExit() {
        String key = key(); var source = manifest("source_" + key, agent(key, "Source"));
        install(source, ImportRequest.ConflictStrategy.OVERWRITE_SAFE); String pid = state(key).path("pid").asText();
        var received = install(manifest("receiver_" + key, agent(key, "Receiver")), ImportRequest.ConflictStrategy.OVERWRITE);
        JsonNode receivedState = state(key); JsonNode receivedEval = evals(key);
        install(source, ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        assertThat(owner(key)).isEqualTo(received.getPluginPid());
        assertThat(state(key)).isEqualTo(receivedState); assertThat(evals(key)).isEqualTo(receivedEval);
        assertThat(lifecycle.enable(source.getPluginId()).isSuccess()).isTrue();
        assertThat(lifecycle.disable(source.getPluginId()).isSuccess()).isTrue();
        assertThat(lifecycle.uninstall(source.getPluginId(), false).isSuccess()).isTrue();
        assertThat(state(key).path("pid").asText()).isEqualTo(pid);
        assertThat(state(key)).isEqualTo(receivedState); assertThat(evals(key)).isEqualTo(receivedEval);
        assertThat(owner(key)).isEqualTo(received.getPluginPid());
    }

    @Test
    void rollbackRestoresCompleteAgentEvalIdentityAndPriorTracking() {
        String key = key(); var initial = install(manifest("original_" + key, agent(key, "Original")), ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        jdbc.update("UPDATE ab_agent_definition SET employee_id=731, system_user_id=?, execution_config=?::jsonb, proactive_policy=?::jsonb WHERE tenant_id=? AND agent_code=?", getTestUser().getId(), "{\"custom\":true}", "{\"enabled\":false}", tenant(), key);
        JsonNode before = state(key); JsonNode beforeEval = evals(key); JsonNode beforeTracking = trackingState(key);
        var adopted = install(manifest("adopter_" + key, agent(key, "Receiver")), ImportRequest.ConflictStrategy.OVERWRITE);
        assertThat(owner(key)).isEqualTo(adopted.getPluginPid());
        assertThat(imports.rollback(adopted.getImportId()).isSuccess()).isTrue();
        assertThat(state(key)).isEqualTo(before); assertThat(evals(key)).isEqualTo(beforeEval);
        assertThat(owner(key)).isEqualTo(initial.getPluginPid()); assertThat(trackingState(key)).isEqualTo(beforeTracking);
        install(manifest("original_" + key, agent(key, "Updated original")), ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        assertThat(state(key).path("name").asText()).isEqualTo("Updated original");
    }

    @Test
    void rollbackRefusesChangedContentAndClaimedResources() {
        String key = key(); install(manifest("start_" + key, agent(key, "Original")), ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        var adopted = install(manifest("changed_" + key, agent(key, "Receiver")), ImportRequest.ConflictStrategy.OVERWRITE);
        jdbc.update("UPDATE ab_agent_definition SET system_prompt='customer edited after adoption' WHERE tenant_id=? AND agent_code=?", tenant(), key);
        JsonNode changed = state(key); JsonNode changedEval = evals(key);
        assertThatThrownBy(() -> imports.rollback(adopted.getImportId())).hasMessageContaining("changed");
        assertThat(state(key)).isEqualTo(changed); assertThat(evals(key)).isEqualTo(changedEval);
        resources.claimByUser(tenant(), ResourceType.AGENT_DEFINITION, key);
        assertThatThrownBy(() -> imports.rollback(adopted.getImportId())).hasMessageContaining("claimed");
        assertThat(state(key)).isEqualTo(changed);
    }

    @Test
    void rollbackRefusesSupersededImportAndMissingRecoverySnapshot() {
        String key = key(); install(manifest("base_" + key, agent(key, "Original")), ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        var first = install(manifest("latest_" + key, agent(key, "First receiver")), ImportRequest.ConflictStrategy.OVERWRITE);
        var nextManifest = manifest("latest_" + key, agent(key, "Latest receiver")); nextManifest.setVersion("1.1.0");
        var latest = install(nextManifest, ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        JsonNode current = state(key);
        assertThatThrownBy(() -> imports.rollback(first.getImportId())).hasMessageContaining("latest");
        assertThat(state(key)).isEqualTo(current);
        jdbc.update("UPDATE ab_plugin_resource SET previous_state=NULL WHERE tenant_id=? AND resource_type='agent_definition' AND resource_code=?", tenant(), key);
        assertThatThrownBy(() -> imports.rollback(latest.getImportId())).hasMessageContaining("snapshot");
        assertThat(state(key)).isEqualTo(current);
    }

    @Test
    void receiverUninstallDeactivatesOnlyOwnedAgentAndEvalCases() {
        String key = key(); var installed = install(manifest("exit_" + key, agent(key, "Owned")), ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        assertThat(resources.executeUninstall(installed.getPluginPid(), tenant(), new UninstallRequest()).isSuccess()).isTrue();
        assertThat(state(key).path("deleted_flag").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_agent_eval_case WHERE tenant_id=? AND agent_code=? AND deleted_flag=false", Integer.class, tenant(), key)).isZero();
    }

    @Test
    void explicitReplacementRollbackRestoresTheSourcesExistingUserClaim() {
        String key = key(); var initial = install(manifest("claimed_source_" + key, agent(key, "Original")), ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        resources.claimByUser(tenant(), ResourceType.AGENT_DEFINITION, key);
        JsonNode before = state(key); JsonNode beforeTracking = trackingState(key); JsonNode beforeEval = evals(key);
        var replaced = install(manifest("claimed_receiver_" + key, agent(key, "Replacement")), ImportRequest.ConflictStrategy.OVERWRITE);
        assertThat(imports.rollback(replaced.getImportId()).isSuccess()).isTrue();
        assertThat(state(key)).isEqualTo(before); assertThat(trackingState(key)).isEqualTo(beforeTracking); assertThat(evals(key)).isEqualTo(beforeEval);
        assertThat(owner(key)).isEqualTo(initial.getPluginPid());
        assertThat(resources.findByTypeAndCode(tenant(), ResourceType.AGENT_DEFINITION, key).getOwnershipType()).isEqualTo("user_claimed");
    }

    @Test
    void sameOwnerRollbackRestoresPluginVersionAndNullableConfiguration() {
        String key = key(); var initialAgent = agent(key, "Initial");
        initialAgent.setDescription(null); initialAgent.setAvatarUrl(null); initialAgent.setSoulProfile(null);
        var source = manifest("version_" + key, initialAgent);
        var initial = install(source, ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        JsonNode before = state(key); JsonNode beforeEval = evals(key); JsonNode beforeTracking = trackingState(key);
        var upgrade = manifest("version_" + key, agent(key, "Upgrade")); upgrade.setVersion("1.1.0");
        var updated = install(upgrade, ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        assertThat(state(key).path("description").asText()).isEqualTo("Upgrade");
        assertThat(imports.rollback(updated.getImportId()).isSuccess()).isTrue();
        assertThat(state(key)).isEqualTo(before); assertThat(evals(key)).isEqualTo(beforeEval);
        assertThat(trackingState(key)).isEqualTo(beforeTracking);
        assertThat(jdbc.queryForObject("SELECT version FROM ab_plugin WHERE tenant_id=? AND pid=?", String.class, tenant(), initial.getPluginPid())).isEqualTo("1.0.0");
    }

    @Test
    void rollbackRefusesChangedEvalCasesOrTransferredOwner() {
        String key = key(); install(manifest("eval_base_" + key, agent(key, "Initial")), ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        var adopted = install(manifest("eval_receiver_" + key, agent(key, "Receiver")), ImportRequest.ConflictStrategy.OVERWRITE);
        jdbc.update("UPDATE ab_agent_eval_case SET task_description='customer eval after adoption' WHERE tenant_id=? AND agent_code=?", tenant(), key);
        JsonNode changed = evals(key);
        assertThatThrownBy(() -> imports.rollback(adopted.getImportId())).hasMessageContaining("changed");
        assertThat(evals(key)).isEqualTo(changed);
        var next = install(manifest("third_" + key, agent(key, "Third owner")), ImportRequest.ConflictStrategy.OVERWRITE);
        JsonNode nextState = state(key);
        assertThatThrownBy(() -> imports.rollback(adopted.getImportId())).hasMessageContaining("owner changed");
        assertThat(state(key)).isEqualTo(nextState); assertThat(owner(key)).isEqualTo(next.getPluginPid());
    }

    @Test
    void rollbackRejectsAnotherTenantAndRetiredPriorOwner() {
        String key = key(); var source = manifest("retired_" + key, agent(key, "Initial"));
        install(source, ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        var adopted = install(manifest("retired_receiver_" + key, agent(key, "Receiver")), ImportRequest.ConflictStrategy.OVERWRITE);
        long originalTenant = tenant(); Long user = MetaContext.getCurrentUserId(); String userPid = MetaContext.getCurrentUserPid(); Long member = MetaContext.getCurrentMemberId();
        long other = System.nanoTime();
        jdbc.update("INSERT INTO ab_tenant(id,pid,name) VALUES (?,?,?)", other, UniqueIdGenerator.generate(), "Agent boundary " + key);
        try {
            MetaContext.setContext(other, user, userPid, "Agent recovery reviewer");
            assertThatThrownBy(() -> imports.rollback(adopted.getImportId())).hasMessageContaining("Import not found");
        } finally { MetaContext.setContext(originalTenant, user, userPid, "Agent recovery reviewer"); MetaContext.setMemberId(member); }
        assertThat(lifecycle.uninstall(source.getPluginId(), false).isSuccess()).isTrue();
        JsonNode current = state(key);
        assertThatThrownBy(() -> imports.rollback(adopted.getImportId())).hasMessageContaining("prior owner");
        assertThat(state(key)).isEqualTo(current); assertThat(owner(key)).isEqualTo(adopted.getPluginPid());
    }

    @Test
    void missingLegacyImportSummaryCannotBypassRecoveryGuards() {
        String key = key(); var installed = install(manifest("legacy_history_" + key, agent(key, "Original")), ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        JsonNode before = state(key); JsonNode beforeEval = evals(key); JsonNode beforeTracking = trackingState(key);
        jdbc.update("UPDATE ab_plugin_import_history SET resource_summary=NULL WHERE tenant_id=? AND import_id=?", tenant(), installed.getImportId());
        assertThatThrownBy(() -> imports.rollback(installed.getImportId())).hasMessageContaining("summary");
        assertThat(state(key)).isEqualTo(before); assertThat(evals(key)).isEqualTo(beforeEval); assertThat(trackingState(key)).isEqualTo(beforeTracking);
    }

    @Test
    void failedAdoptionRollsBackEarlierAgentAndEvalWrites() {
        String key = key(); install(manifest("good_" + key, agent(key, "Original")), ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        JsonNode before = state(key); JsonNode beforeEval = evals(key); JsonNode beforeTracking = trackingState(key);
        var invalid = agent(key + "_bad", "Bad eval"); invalid.getEvalCases().getFirst().setCaseId("");
        assertThatThrownBy(() -> install(manifest("broken_" + key, agent(key, "Receiver"), invalid), ImportRequest.ConflictStrategy.OVERWRITE))
                .hasMessageContaining("eval");
        assertThat(state(key)).isEqualTo(before); assertThat(evals(key)).isEqualTo(beforeEval); assertThat(trackingState(key)).isEqualTo(beforeTracking);
    }

    private long tenant() { return MetaContext.getCurrentTenantId(); }
    private String key() { return "ah_" + UniqueIdGenerator.generate().toLowerCase(); }
    private String owner(String code) { return resources.findByTypeAndCode(tenant(), ResourceType.AGENT_DEFINITION, code).getPluginPid(); }
    private PluginManifestExtended manifest(String name, AgentDefinitionDTO... agents) {
        return PluginManifestExtended.builder().pluginId("com.test." + name).namespace(name).version("1.0.0").agentDefinitions(List.of(agents)).build();
    }
    private AgentDefinitionDTO agent(String code, String name) {
        return AgentDefinitionDTO.builder().agentCode(code).name(name).description(name).avatarUrl("/test/" + name)
                .agentType("reactive").model("test-model").systemPrompt("Prompt " + name).tools(List.of("tool_" + name))
                .skills(List.of("skill_" + name)).guardrails(Map.of("mode", name)).soulProfile(Map.of("persona", name))
                .allowedModels(List.of("test_model")).allowedOperations(List.of("query")).knowledgeBaseIds(List.of("kb-test"))
                .maxTools(4).maxConcurrentRuns(2).executionTimeoutSeconds(40).autoReplyMode("mention").status("disabled").visibility("private")
                .evalCases(List.of(CapabilityEvalCase.builder().caseId("case_" + name).category("TOOL_SELECTION")
                        .taskDescription("Select a test tool for " + name).expectedToolCodes(List.of("tool_" + name))
                        .forbiddenToolCodes(List.of("forbidden_tool")).expectedInputKeys(Map.of("key", name)).build())).build();
    }
    private ImportExecuteResult install(PluginManifestExtended manifest, ImportRequest.ConflictStrategy strategy) {
        var request = new ImportRequest(); request.setConflictStrategy(strategy);
        var result = imports.executeFromManifest(manifest, request); assertThat(result.isSuccess()).isTrue(); return result;
    }
    private JsonNode state(String code) { return read("SELECT to_jsonb(a)::text FROM ab_agent_definition a WHERE tenant_id=? AND agent_code=?", code); }
    private JsonNode evals(String code) { return read("SELECT coalesce(jsonb_agg(to_jsonb(e) ORDER BY e.id),'[]'::jsonb)::text FROM ab_agent_eval_case e WHERE tenant_id=? AND agent_code=?", code); }
    private JsonNode trackingState(String code) { return read("SELECT (to_jsonb(r)-'updated_at')::text FROM ab_plugin_resource r WHERE tenant_id=? AND resource_type='agent_definition' AND resource_code=?", code); }
    private JsonNode read(String sql, String code) {
        try { return json.readTree(jdbc.queryForObject(sql, String.class, tenant(), code)); }
        catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
}
