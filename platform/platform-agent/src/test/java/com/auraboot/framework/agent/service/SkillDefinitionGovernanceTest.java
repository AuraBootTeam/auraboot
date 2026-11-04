package com.auraboot.framework.agent.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.versioning.service.VersionHistoryService;
import com.auraboot.framework.versioning.dto.DesignVersionDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SkillDefinitionGovernanceTest {
    final ObjectMapper json = new ObjectMapper();
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final VersionHistoryService history = mock(VersionHistoryService.class);
    final AgentSkillService skills = mock(AgentSkillService.class);
    final UserPermissionService permissions = mock(UserPermissionService.class);
    SkillDefinitionGovernanceService service;
    Map<String, Object> row;

    @BeforeEach void setup() {
        MetaContext.setContext(42L, 7L, "user", "Editor");
        service = new SkillDefinitionGovernanceService(jdbc, history, skills, json, permissions);
        row = new HashMap<>(Map.of("pid", "skill-a", "tenant_id", 42L, "skill_code", "delivery", "skill_name", "Delivery",
                "execution_mode", "template", "skill_tools", List.of("list:tpm_task"), "skill_status", "draft"));
        lenient().when(jdbc.queryForList(anyString(), eq(42L), eq("skill-a"))).thenAnswer(i -> List.of(row));
        lenient().when(permissions.hasPermission(7L, "acp_agent_skill")).thenReturn(true);
        lenient().when(skills.resolveToolCodes(42L, List.of("list:tpm_task"))).thenReturn(List.of(
                com.auraboot.framework.agent.dto.AgentToolDefinition.builder().name("list:tpm_task").build()));
        lenient().when(history.getHistory(anyString(), anyString())).thenReturn(List.of());
    }
    @AfterEach void cleanup() { MetaContext.clear(); }
    String hash() { return SkillDefinitionSnapshot.hash(SkillDefinitionSnapshot.capture(row, json), json); }

    @Test void staleDraftCannotPublishOrCreateHistory() {
        assertThatThrownBy(() -> service.publish("skill-a", "stale", "", "test"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("409");
        verify(history, never()).recordVersionWithSnapshot(any(), any(), any(), any(), any());
    }
    @Test void anotherPublicationBetweenPreviewAndDecisionIsRejected() {
        row.put("execution_config", Map.of(SkillDefinitionSnapshot.PUBLICATION, Map.of("versionPid", "new", "hash", "0".repeat(64))));
        assertThatThrownBy(() -> service.publish("skill-a", hash(), "old", "test")).hasMessageContaining("409");
        verifyNoInteractions(history);
    }
    @Test void builtinsCannotBePublishedEvenByAnAuthor() {
        row.put("is_builtin", true);
        assertThatThrownBy(() -> service.publish("skill-a", hash(), "", "test")).hasMessageContaining("403");
        verifyNoInteractions(history);
    }
    @Test void runtimePermissionCannotReplaceAuthoringPermission() {
        when(permissions.hasPermission(7L, "acp_agent_skill")).thenReturn(false);
        assertThatThrownBy(() -> service.inspect("skill-a")).hasMessageContaining("403");
        verifyNoInteractions(jdbc, history);
    }
    @Test void anotherTenantCannotLoadTheOwnerRow() {
        MetaContext.setContext(43L, 7L, "user", "Editor");
        when(jdbc.queryForList(anyString(), eq(43L), eq("skill-a"))).thenReturn(List.of());
        assertThatThrownBy(() -> service.inspect("skill-a")).hasMessageContaining("404");
        verifyNoInteractions(history);
    }
    @Test void historyPidMustBelongToTheSkillAndResourceType() {
        when(history.getVersion("foreign")).thenReturn(DesignVersionDTO.builder().resourceType("page")
                .resourceId("skill-a").schemaSnapshot(json.createObjectNode()).build());
        assertThatThrownBy(() -> service.version("skill-a", "foreign")).hasMessageContaining("404");
        when(history.getVersion("foreign")).thenReturn(DesignVersionDTO.builder().resourceType("agent-skill")
                .resourceId("skill-b").schemaSnapshot(json.createObjectNode()).build());
        assertThatThrownBy(() -> service.version("skill-a", "foreign")).hasMessageContaining("404");
    }
    @Test void unavailableToolBlocksPublication() {
        when(skills.resolveToolCodes(42L, List.of("list:tpm_task"))).thenReturn(List.of());
        assertThatThrownBy(() -> service.publish("skill-a", hash(), "", "test")).hasMessageContaining("422");
        verifyNoInteractions(history);
    }
    @Test void legacyActiveDefinitionIsUnchangedAndMalformedPointerFailsClosed() {
        DynamicDataMapper mapper = mock(DynamicDataMapper.class);
        assertThat(SkillDefinitionSnapshot.resolve(row, mapper, json)).isSameAs(row);
        verifyNoInteractions(mapper);
        row.put("execution_config", Map.of(SkillDefinitionSnapshot.PUBLICATION, "invalid"));
        assertThatThrownBy(() -> SkillDefinitionSnapshot.resolve(row, mapper, json)).isInstanceOf(IllegalStateException.class);
    }
    @Test void runtimeUsesPublishedSnapshotDespiteLaterDraftEditsAndChecksFingerprint() {
        Map<String, Object> snapshot = SkillDefinitionSnapshot.capture(row, json);
        row.put("skill_name", "Unpublished edits");
        row.put("execution_config", Map.of(SkillDefinitionSnapshot.PUBLICATION,
                Map.of("versionPid", "version-1", "hash", SkillDefinitionSnapshot.hash(snapshot, json), "version", "1")));
        DynamicDataMapper mapper = mock(DynamicDataMapper.class);
        when(mapper.selectByQueryWithoutTenant(anyString(), anyMap())).thenReturn(List.of(Map.of("schema_snapshot", snapshot)));
        var effective = SkillDefinitionSnapshot.resolve(row, mapper, json);
        assertThat(effective).containsEntry("skill_name", "Delivery").containsEntry("skill_version_pid", "version-1");
        assertThat(row.get("skill_name")).isEqualTo("Unpublished edits");
        verify(mapper).selectByQueryWithoutTenant(contains("tenant_id = #{params.tenantId}"),
                eq(Map.of("tenantId",42L,"versionPid","version-1","resourceType","agent-skill","resourceId","skill-a")));
        snapshot.put("prompt_template", "tampered");
        assertThatThrownBy(() -> SkillDefinitionSnapshot.resolve(row, mapper, json)).hasMessageContaining("fingerprint mismatch");
    }
    @Test void externalSchemaReferencesCannotBePublished() {
        row.put("skill_input_schema", Map.of("type", "object", "properties", Map.of("secret", Map.of("$ref", "http://127.0.0.1:1/private"))));
        assertThatThrownBy(() -> service.publish("skill-a", hash(), "", "test")).hasMessageContaining("422");
        verifyNoInteractions(history);
    }
    @Test void nestedObjectOrderDoesNotChangeDefinitionIdentity() {
        Map<String,Object> left = Map.of("schema", new LinkedHashMap<>(Map.of("a",1,"b",2)));
        Map<String,Object> right = Map.of("schema", new TreeMap<>(Map.of("b",2,"a",1)));
        assertThat(SkillDefinitionSnapshot.hash(left,json)).isEqualTo(SkillDefinitionSnapshot.hash(right,json));
    }
    @Test void rollbackActivatesHistoryWithoutOverwritingTheDraft() {
        Map<String, Object> target = SkillDefinitionSnapshot.capture(row, json);
        target.put("skill_name", "Previous release");
        when(history.getVersion("old")).thenReturn(DesignVersionDTO.builder().pid("old").resourceType("agent-skill")
                .resourceId("skill-a").schemaSnapshot(json.valueToTree(target)).build());
        when(history.recordVersionWithSnapshot(eq("agent-skill"),eq("skill-a"),any(),eq("ROLLBACK"),any()))
                .thenReturn(DesignVersionDTO.builder().pid("rollback-2").version("2").build());
        when(jdbc.update(anyString(), any(), eq(42L), eq("skill-a"))).thenReturn(1);
        service.rollback("skill-a", "old", hash(), "", "Restore");
        verify(jdbc).queryForList(contains("FOR UPDATE"), eq(42L), eq("skill-a"));
        verify(jdbc).update(argThat((String sql) -> sql.contains("execution_config =") && !sql.contains("skill_name =")), any(), eq(42L), eq("skill-a"));
        assertThat(row.get("skill_name")).isEqualTo("Delivery");
    }
}
