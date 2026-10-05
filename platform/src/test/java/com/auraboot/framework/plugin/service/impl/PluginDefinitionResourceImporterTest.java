package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.exception.PluginException;
import com.auraboot.framework.automation.dto.*;
import com.auraboot.framework.automation.service.AutomationService;
import com.auraboot.framework.meta.template.generator.DocumentCommandGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Checks definition import accounting, snapshots and lifecycle decisions at typed ports. */
class PluginDefinitionResourceImporterTest {
    final PluginResourceImporter resources = mock(PluginResourceImporter.class);
    final DocumentCommandGenerator generator = mock(DocumentCommandGenerator.class);
    final AutomationService automations = mock(AutomationService.class);
    final PluginDefinitionResourceImporter.SaveOrUpdatePluginResourceOperation save = mock(PluginDefinitionResourceImporter.SaveOrUpdatePluginResourceOperation.class);
    final PluginDefinitionResourceImporter.CaptureImportSnapshotOperation snapshot = mock(PluginDefinitionResourceImporter.CaptureImportSnapshotOperation.class);
    final PluginDefinitionResourceImporter importer = new PluginDefinitionResourceImporter(resources,generator,automations,save,snapshot);
    final ObjectMapper json = new ObjectMapper();
    @ParameterizedTest @ValueSource(strings = {"CREATE", "UPDATE", "SKIP", "NULL"})
    void everyDefinitionTypeHonorsResourceOutcomeAndTenantBoundAccounting(String action) throws Exception {
        var manifest = json.readValue("""
            {"dicts":[{"code":"status"}],"fields":[{"code":"amount","dataType":"number"}],
             "models":[{"code":"invoice"}],"modelFieldBindings":[{"modelCode":"invoice","fieldCode":"amount"}],
             "commands":[{"code":"invoice_create","modelCode":"invoice","commandType":"CREATE"}],
             "bindingRules":[{"commandCode":"invoice_create","ruleType":"VALIDATION"}],
             "pages":[{"pageKey":"invoice_list","kind":"list","layout":{"type":"grid"},"blocks":[{"type":"table"}]}],
             "dashboards":[{"code":"invoice_board","title":"Invoices","widgets":[{"id":"amount","type":"metric"}]}],
             "namedQueries":[{"code":"invoice_query","fromSql":"ab_invoice","fields":[{"fieldCode":"amount","columnExpr":"amount","dataType":"number"}]}],
             "agentDefinitions":[{"agentCode":"invoice_agent","name":"Invoices"}]}
            """,PluginManifestExtended.class);
        var resource = new PluginResource(); resource.setAction("NULL".equals(action) ? "CREATE" : action); resource.setResourcePid("r-42");
        doReturn("NULL".equals(action) ? null : resource).when(resources).importDict(any(),eq("p"),eq("i"),eq(42L),any());
        doReturn("NULL".equals(action) ? null : resource).when(resources).importField(any(),eq("p"),eq("i"),eq(42L),any(),any());
        doReturn("NULL".equals(action) ? null : resource).when(resources).importModel(any(),eq("p"),eq("i"),eq(42L),any(),any());
        doReturn("NULL".equals(action) ? null : resource).when(resources).importModelFieldBinding(any(),eq("p"),eq("i"),eq(42L),any());
        doReturn("NULL".equals(action) ? null : resource).when(resources).importCommand(any(),eq("p"),eq("i"),eq(42L),any(),any());
        doReturn("NULL".equals(action) ? null : resource).when(resources).importBindingRule(any(),eq("p"),eq("i"),eq(42L),any());
        doReturn("NULL".equals(action) ? null : resource).when(resources).importPage(any(),eq("p"),eq("i"),eq(42L),any(),any());
        doReturn("NULL".equals(action) ? null : resource).when(resources).importDashboard(any(),eq("p"),eq("i"),eq(42L),any());
        doReturn("NULL".equals(action) ? null : resource).when(resources).importNamedQuery(any(),eq("p"),eq("i"),eq(42L),any());
        doReturn("NULL".equals(action) ? null : resource).when(resources).importAgentDefinition(any(),eq("p"),eq("i"),eq(42L),any());
        var request = new ImportRequest(); var result = new ImportExecuteResult();
        importer.importDicts(manifest,request,result,"p","i",42L); importer.importFields(manifest,request,result,"p","i",42L);
        var models = importer.importModels(manifest,request,result,"p","i",42L);
        importer.importModelFieldBindings(manifest,request,result,"p","i",42L); importer.importCommands(manifest,request,result,"p","i",42L);
        importer.importBindingRules(manifest,request,result,"p","i",42L); importer.importPages(manifest,request,result,"p","i",42L);
        importer.importDashboards(manifest,request,result,"p","i",42L); importer.importNamedQueries(manifest,request,result,"p","i",42L);
        importer.importAgentDefinitions(manifest,request,result,"p","i",42L);
        assertThat(models).isEqualTo(Set.of("SKIP","NULL").contains(action) ? List.of() : List.of("invoice"));
        assertThat(result.getTotalResourceCount()).isEqualTo("NULL".equals(action) ? 0 : 10);
        verify(save,times("NULL".equals(action) ? 0 : 10)).execute(resource,42L);
        verify(snapshot,times("NULL".equals(action) ? 0 : 10)).execute(eq(resource),any());
        if (!"NULL".equals(action)) {
            assertThat(result.getResourceCounts().get("PAGE")).containsEntry(action,2);
            assertThat(result.getCreatedResources().get("PAGE")).containsExactly("r-42","r-42");
            assertThat(result.getResourceCounts().get("MODEL_FIELD_BINDING")).containsEntry(action,1);
        }
        assertThat(manifest.getNamedQueries().get(0).getStatus()).isEqualTo("published");
    }
    @Test void invalidDefinitionsNeverReachPersistenceAndMalformedPagesFailExplicitly() throws Exception {
        var m=json.readValue("""
            {"dicts":[{}],"fields":[{}],"models":[{}],"modelFieldBindings":[{}],"commands":[{}],"bindingRules":[{}],
             "dashboards":[{}],"namedQueries":[{}],"agentDefinitions":[{}],"automations":[{}],"pages":[{"pageKey":"broken"}]}
            """,PluginManifestExtended.class);
        var r=new ImportExecuteResult(); var q=new ImportRequest();
        importer.importDicts(m,q,r,"p","i",42L); importer.importFields(m,q,r,"p","i",42L);
        assertThat(importer.importModels(m,q,r,"p","i",42L)).isEmpty();
        importer.importModelFieldBindings(m,q,r,"p","i",42L); importer.importCommands(m,q,r,"p","i",42L);
        importer.importBindingRules(m,q,r,"p","i",42L); importer.importDashboards(m,q,r,"p","i",42L);
        importer.importNamedQueries(m,q,r,"p","i",42L); importer.importAgentDefinitions(m,q,r,"p","i",42L); importer.importAutomations(m);
        assertThatThrownBy(() -> importer.importPages(m,q,r,"p","i",42L)).isInstanceOf(PluginException.class).hasMessageContaining("broken");
        verifyNoInteractions(resources,save,snapshot,automations); assertThat(r.getTotalResourceCount()).isZero();
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void automationReimportPreservesIdentityAndAllTriggerActionConfiguration(boolean exists) {
        var seed=AutomationDefinitionDTO.builder().automationKey("notify_invoice").name("Invoice notification").modelCode("invoice")
                .triggerType("EVENT").triggerCondition("amount > 0").description("Finance notification").enabled(false)
                .flowConfig(Map.of("mode","serial")).actions(List.of()).build();
        var existing=new AutomationDTO(); existing.setPid("a-42"); existing.setName(seed.getName());
        when(automations.getByModelCode("invoice")).thenReturn(exists ? List.of(existing) : List.of());
        var m=new PluginManifestExtended(); m.setAutomations(List.of(seed)); importer.importAutomations(m);
        if (exists) {
            verify(automations).update(eq("a-42"),argThat(q -> !q.getEnabled() && q.getTriggerCondition().equals("amount > 0") && q.getFlowConfig().equals(Map.of("mode","serial"))));
            verify(automations,never()).create(any());
        } else {
            verify(automations).create(argThat(q -> q.getModelCode().equals("invoice") && !q.getEnabled() && q.getName().equals(seed.getName()) && q.getActions().isEmpty()));
            verify(automations,never()).update(anyString(),any());
        }
    }
    @Test void generatedDocumentCommandsRespectPluginOverridesAndCrossModelDeduplication() {
        var model=ModelDefinitionDTO.builder().code("invoice").modelCategory("document").extension(Map.of("documentConfig",Map.of("statusField","status"))).build();
        var override=CommandDefinitionDTO.builder().code("invoice_create").build();
        var generated=CommandDefinitionDTO.builder().code("invoice_approve").build();
        var manifest=new PluginManifestExtended(); manifest.setModels(List.of(model,model)); manifest.setCommands(new ArrayList<>(List.of(override)));
        when(generator.generateCommands(eq(model),any())).thenReturn(List.of(override,generated));
        importer.generateDocumentTemplateCommands(manifest);
        assertThat(manifest.getCommands()).containsExactly(override,generated);
        manifest.setCommands(null); importer.generateDocumentTemplateCommands(manifest);
        assertThat(manifest.getCommands()).containsExactly(override,generated);
    }
}
