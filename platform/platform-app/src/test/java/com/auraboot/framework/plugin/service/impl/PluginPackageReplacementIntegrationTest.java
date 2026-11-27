package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.dto.uninstall.UninstallRequest;
import com.auraboot.framework.plugin.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real legacy resources are installed with only the new retirement guard temporarily disabled. */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PluginPackageReplacementIntegrationTest extends BaseIntegrationTest {
    @Autowired PluginImportService imports;
    @Autowired PluginResourceService resources;
    @Autowired PluginManagerService lifecycle;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @MockitoSpyBean PluginPackageReplacements replacements;

    @BeforeEach
    void isolatedTenantAndPlatformPrerequisites() {
        long tenant = System.nanoTime();
        String key = com.auraboot.framework.common.util.UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_tenant(id,pid,name) VALUES (?,?,?)",tenant,key,"Package replacement " + key);
        MetaContext.setContext(tenant,getTestUser().getId(),getTestUser().getPid(),"Package replacement reviewer");
        var support=PluginManifestExtended.builder().pluginId("com.test.replacement-support").namespace("support").version("1.0.0")
                .permissions(List.of(PermissionDefinitionDTO.builder().code("plugin.plugin.manage").name("Plugin management").resourceType("plugin").action("manage").build()))
                .menus(List.of(MenuDefinitionDTO.builder().code("system_management").name("System").type(0).visible(true).build())).build();
        install(support);
    }

    @ParameterizedTest
    @ValueSource(strings={"platform-admin-ee","d7-knowledge-wiki","ai-employees"})
    void adoptPreservesResourcesAcrossOldReimportUpgradeAndBothExitPaths(String name) throws Exception {
        var source = legacy(name);
        var installed = seedLegacy(source);
        JsonNode before = liveState(source); JsonNode beforeTracking = trackingState(installed.getPluginPid());
        assertThatThrownBy(() -> lifecycle.uninstall(source.getPluginId(), false)).hasMessageContaining("receiver");
        assertThatThrownBy(() -> resources.executeUninstall(installed.getPluginPid(), tenant(), new UninstallRequest())).hasMessageContaining("receiver");
        var receiver = receiver(name);
        var adopted = install(receiver);
        assertThat(liveState(source)).isEqualTo(before);
        assertThat(trackingWithoutOwner(adopted.getPluginPid(), source)).isEqualTo(beforeTracking);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_plugin_resource WHERE tenant_id=? AND plugin_pid=?", Integer.class, tenant(), installed.getPluginPid())).isZero();
        assertThatThrownBy(() -> install(source)).hasMessageContaining("Retired plugin");
        source.setVersion("99.0.0");
        assertThatThrownBy(() -> install(source)).hasMessageContaining("Retired plugin");
        assertThatThrownBy(() -> imports.rollback(installed.getImportId())).hasMessageContaining("Retired plugin");
        assertThatThrownBy(() -> imports.rollback(adopted.getImportId())).hasMessageContaining("adoption");
        receiver.setVersion("2.0.0"); install(receiver);
        // Receiver upgrades may update their own declarations, but retain every resource identity.
        assertThat(resourcePids(source)).isEqualTo(pids(before));
        assertThat(resources.executeUninstall(installed.getPluginPid(), tenant(), new UninstallRequest()).isSuccess()).isTrue();
        assertThat(lifecycle.uninstall(source.getPluginId(), false).isSuccess()).isTrue();
        assertThat(resourcePids(source)).isEqualTo(pids(before));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_role_permission rp JOIN ab_role r ON rp.role_id=r.id JOIN ab_permission p ON rp.permission_id=p.id WHERE r.tenant_id=? AND r.code='d7_knowledge_viewer' AND p.code='d7.knowledge.read' AND rp.deleted_flag=false", Integer.class, tenant()))
                .isEqualTo(name.equals("platform-admin-ee") ? 0 : 1);
    }

    @ParameterizedTest
    @ValueSource(strings={"platform-admin-ee","d7-knowledge-wiki","ai-employees"})
    void adoptionAndReceiverUpgradeKeepClaimsAndCustomerChanges(String name) throws Exception {
        var source = legacy(name); var installed = seedLegacy(source);
        jdbc.update("UPDATE ab_plugin_resource SET user_modified=true,ownership_type='user_claimed' WHERE tenant_id=? AND plugin_pid=?", tenant(), installed.getPluginPid());
        if (name.equals("ai-employees")) jdbc.update("UPDATE ab_agent_definition SET system_prompt='customer retained prompt',employee_id=714 WHERE tenant_id=? AND agent_code='dex'", tenant());
        else jdbc.update("UPDATE ab_menu SET name='customer retained menu' WHERE tenant_id=? AND code=?", tenant(), name.equals("platform-admin-ee") ? "admin_entitlements" : "d7_knowledge_wiki");
        if (name.equals("d7-knowledge-wiki")) jdbc.update("UPDATE ab_role_permission SET deleted_flag=true WHERE tenant_id=?", tenant());
        JsonNode before = liveState(source); JsonNode tracking = trackingState(installed.getPluginPid());
        var target = receiver(name); var adopted = install(target); target.setVersion("2.0.0"); install(target);
        assertThat(liveState(source)).isEqualTo(before);
        if (name.equals("d7-knowledge-wiki")) assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_role_permission WHERE tenant_id=? AND deleted_flag=false",Integer.class,tenant())).isZero();
        assertThat(trackingWithoutOwner(adopted.getPluginPid(), source)).isEqualTo(tracking);
        assertThat(lifecycle.uninstall(source.getPluginId(), false).isSuccess()).isTrue();
        assertThat(liveState(source)).isEqualTo(before);
    }

    @Test
    void unknownResourceAndUnknownProtectionAbortAllAdoptionWrites() throws Exception {
        var source = legacy("ai-employees"); var installed = seedLegacy(source);
        jdbc.update("UPDATE ab_plugin_resource SET user_modified=NULL WHERE tenant_id=? AND resource_code='dex'", tenant());
        var before = liveState(source);
        assertThatThrownBy(() -> install(receiver("ai-employees"))).hasMessageContaining("unknown replacement");
        assertThat(liveState(source)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_plugin_resource WHERE tenant_id=? AND plugin_pid=?", Integer.class, tenant(), installed.getPluginPid())).isEqualTo(4);
        jdbc.update("UPDATE ab_plugin_resource SET user_modified=false,resource_code='unknown_extra' WHERE tenant_id=? AND resource_code='dex'", tenant());
        assertThatThrownBy(() -> install(receiver("ai-employees"))).hasMessageContaining("tracking is missing");
        assertThat(liveState(source)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings={"platform-admin-ee","d7-knowledge-wiki","ai-employees"})
    void adoptionAndExitCannotChangeAnotherTenantsSamePackage(String name) throws Exception {
        var source=legacy(name);seedLegacy(source);long first=tenant();
        isolatedTenantAndPlatformPrerequisites();long other=tenant();var otherInstall=seedLegacy(source);
        var otherState=liveState(source);var otherTracking=trackingState(otherInstall.getPluginPid());
        MetaContext.setContext(first,getTestUser().getId(),getTestUser().getPid(),"First tenant reviewer");
        install(receiver(name));assertThat(lifecycle.uninstall(source.getPluginId(),false).isSuccess()).isTrue();
        MetaContext.setContext(other,getTestUser().getId(),getTestUser().getPid(),"Other tenant reviewer");
        assertThat(liveState(source)).isEqualTo(otherState);
        assertThat(trackingState(otherInstall.getPluginPid())).isEqualTo(otherTracking);
        assertThatThrownBy(() -> lifecycle.uninstall(source.getPluginId(),false)).hasMessageContaining("receiver");
    }

    @Test
    void explicitOverwriteCannotReplaceCustomerResourcesDuringAdoption() throws Exception {
        var source=legacy("ai-employees"); seedLegacy(source); var before=liveState(source);
        var request=new ImportRequest(); request.setConflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE);
        assertThatThrownBy(() -> imports.executeFromManifest(receiver("ai-employees"),request)).hasMessageContaining("OVERWRITE_SAFE");
        assertThat(liveState(source)).isEqualTo(before);
    }

    @Test
    void missingTrackingCannotBePresentedAsSuccessfulAdoption() throws Exception {
        var source=legacy("ai-employees");seedLegacy(source);var before=liveState(source);
        jdbc.update("DELETE FROM ab_plugin_resource WHERE tenant_id=? AND resource_type='agent_definition' AND resource_code='dex'",tenant());
        assertThatThrownBy(() -> install(receiver("ai-employees"))).hasMessageContaining("tracking is missing");
        assertThat(liveState(source)).isEqualTo(before);
        assertThatThrownBy(() -> lifecycle.uninstall(source.getPluginId(),false)).hasMessageContaining("tracking is missing");
    }

    @Test
    void inconsistentLiveOwnerRejectsAdoptionWithoutChangingContent() throws Exception {
        var source=legacy("platform-admin-ee");seedLegacy(source);
        jdbc.update("UPDATE ab_menu SET plugin_pid=NULL WHERE tenant_id=? AND code='admin_entitlements'",tenant());
        var before=liveState(source);
        assertThatThrownBy(() -> install(receiver("platform-admin-ee"))).hasMessageContaining("identity/owner changed");
        assertThat(liveState(source)).isEqualTo(before);
    }

    @Test
    void freshReceiversDoNotCreateAiEmployeesAndTemplatesRequireExplicitSelection() throws Exception {
        var receiver = json.readValue(java.nio.file.Files.readString(java.nio.file.Path.of("../plugins/core-aurabot/plugin.json")),PluginManifestExtended.class);
        install(receiver);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_agent_definition WHERE tenant_id=?",Integer.class,tenant())).isZero();
        var request=new ImportRequest(); request.setConflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE_SAFE);
        request.setAgentTemplateCodes(List.of("aria"));
        assertThat(imports.executeFromManifest(receiver,request).isSuccess()).isTrue();
        assertThat(jdbc.queryForMap("SELECT agent_code,status,model,employee_id,system_user_id FROM ab_agent_definition WHERE tenant_id=?",tenant()))
                .containsEntry("agent_code","aria").containsEntry("status","disabled").containsEntry("model",null).containsEntry("employee_id",null).containsEntry("system_user_id",null);
        request.setAgentTemplateCodes(List.of("unknown"));
        assertThatThrownBy(() -> imports.executeFromManifest(receiver("ai-employees"),request)).hasMessageContaining("Unknown");
    }

    private Long tenant() { return MetaContext.getCurrentTenantId(); }
    private PluginManifestExtended legacy(String name) throws Exception {
        try(var input=getClass().getResourceAsStream("/plugin-replacements/legacy-declarations.json")) {
            for(var node:json.readTree(input)) if(node.path("pluginId").asText().equals("com.auraboot."+name)) return json.treeToValue(node,PluginManifestExtended.class);
        }
        throw new AssertionError(name);
    }
    private ImportExecuteResult seedLegacy(PluginManifestExtended source) {
        doNothing().when(replacements).requireImportable(source.getPluginId());
        try { return install(source); } finally { reset(replacements); }
    }
    private PluginManifestExtended receiver(String name) {
        return PluginManifestExtended.builder().pluginId(name.equals("platform-admin-ee") ? "com.auraboot.platform-admin" : "com.auraboot.core-aurabot")
                .namespace("receiver").version("1.1.0").build();
    }
    private ImportExecuteResult install(PluginManifestExtended manifest) {
        var request=new ImportRequest();request.setConflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE_SAFE);request.setValidateReferences(false);
        var result=imports.executeFromManifest(manifest,request);assertThat(result.isSuccess()).isTrue();return result;
    }
    private JsonNode trackingState(String pid) throws Exception {
        return json.readTree(jdbc.queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(r)-'plugin_pid' ORDER BY resource_type,resource_code),'[]'::jsonb)::text FROM ab_plugin_resource r WHERE tenant_id=? AND plugin_pid=?",String.class,tenant(),pid));
    }
    private JsonNode trackingWithoutOwner(String pid,PluginManifestExtended source) throws Exception {
        var all=trackingState(pid);var result=json.createArrayNode();Set<String>codes=codes(source);
        for(var row:all) if(codes.contains(row.path("resource_code").asText()))result.add(row);
        return result;
    }
    private Set<String> codes(PluginManifestExtended source) {
        Set<String>codes=new HashSet<>();
        if(source.getMenus()!=null)source.getMenus().forEach(x->codes.add(x.getCode()));
        if(source.getPermissions()!=null)source.getPermissions().forEach(x->codes.add(x.getCode()));
        if(source.getRoles()!=null)source.getRoles().forEach(x->codes.add(x.getCode()));
        if(source.getAgentDefinitions()!=null)source.getAgentDefinitions().forEach(x->codes.add(x.getAgentCode()));
        return codes;
    }
    private JsonNode liveState(PluginManifestExtended source) throws Exception {
        var result=json.createArrayNode();
        for(String table:List.of("ab_menu","ab_permission","ab_role","ab_agent_definition")) {
            String column=table.equals("ab_agent_definition")?"agent_code":"code";
            for(String code:new TreeSet<>(codes(source))) {
                var rows=jdbc.queryForList("SELECT (to_jsonb(r)-'plugin_pid')::text AS state FROM "+table+" r WHERE tenant_id=? AND "+column+"=? AND deleted_flag=false",tenant(),code);
                for(var row:rows) result.add(json.readTree(row.get("state").toString()));
            }
        }
        return result;
    }
    private Set<String> pids(JsonNode state) {Set<String>pids=new HashSet<>();for(var row:state)pids.add(row.path("pid").asText());return pids;}
    private Set<String> resourcePids(PluginManifestExtended source) throws Exception {return pids(liveState(source));}
}
