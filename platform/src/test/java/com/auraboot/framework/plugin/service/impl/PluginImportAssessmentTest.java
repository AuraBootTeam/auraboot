package com.auraboot.framework.plugin.service.impl;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.entity.*;
import com.auraboot.framework.plugin.mapper.*;
import com.auraboot.framework.plugin.validation.*;
import com.auraboot.framework.plugin.exception.PluginException;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Reference verification evaluates tenant state and preserves all dangling edges. */
class PluginImportAssessmentTest {
    final PluginRecordMapper plugins=mock(PluginRecordMapper.class);
    final PluginResourceMapper snapshots=mock(PluginResourceMapper.class);
    final PluginResourceImporter resources=mock(PluginResourceImporter.class);
    final PluginValidationPipeline pipeline=mock(PluginValidationPipeline.class);
    final CommandDefinitionMapper commands=mock(CommandDefinitionMapper.class);
    final PluginImportAssessment.FindDanglingCommandModelRefsOperation commandRefs=mock(PluginImportAssessment.FindDanglingCommandModelRefsOperation.class);
    final PluginImportAssessment.FindDanglingMenuParentRefsOperation menuRefs=mock(PluginImportAssessment.FindDanglingMenuParentRefsOperation.class);
    final PluginImportAssessment.FindDanglingPermissionRefsOperation permissionRefs=mock(PluginImportAssessment.FindDanglingPermissionRefsOperation.class);
    final PluginImportAssessment assessment=new PluginImportAssessment(plugins,snapshots,resources,pipeline,commands,new ObjectMapper(),commandRefs,menuRefs,permissionRefs);
    @BeforeEach void context() { MetaContext.setContext(42L,43L,"u-43","tester"); }
    @AfterEach void clear() { MetaContext.clear(); }
    PluginResource snapshot(String code,Map<String,Object> data) { return PluginResource.builder().resourceCode(code).importSnapshot(data).build(); }
    @Test void integrityChecksCurrentCommandsAndSnapshotMenuRolePoliciesAgainstTenantResources() {
        var cmd=new CommandDefinition(); cmd.setCode("invoice_create"); cmd.setModelCode("invoice");
        when(commands.selectList(any())).thenReturn(List.of(cmd)); when(resources.checkModelExists(42L,"invoice")).thenReturn(true);
        when(snapshots.findByTenantAndType(42L,"menu")).thenReturn(List.of(snapshot("invoice",Map.of("code","invoice","parentCode","finance","permissionCode","invoice.read"))));
        when(snapshots.findByTenantAndType(42L,"role")).thenReturn(List.of(snapshot("operator",Map.of("code","operator","permissions",List.of("invoice.write"),"permissionPolicies",List.of(Map.of("permissionCode","invoice.approve"))))));
        when(resources.checkMenuExists(42L,"invoice")).thenReturn(true); when(resources.checkPermissionExists(42L,"invoice.read")).thenReturn(true);
        when(commandRefs.execute(anyList(),anySet())).thenReturn(List.of("command-gap")); when(menuRefs.execute(anyList(),anySet())).thenReturn(List.of("parent-gap")); when(permissionRefs.execute(anyList(),anyList(),anySet())).thenReturn(List.of("permission-gap"));
        assertThat(assessment.verifyImportReferenceIntegrity()).containsExactly("command-gap","parent-gap","permission-gap");
        verify(commandRefs).execute(argThat(list -> list.size()==1 && list.get(0).getCode().equals("invoice_create")),eq(Set.of("invoice")));
        verify(menuRefs).execute(anyList(),eq(Set.of("invoice"))); verify(permissionRefs).execute(anyList(),anyList(),eq(Set.of("invoice.read")));
        verify(resources).checkPermissionExists(42L,"invoice.write"); verify(resources).checkPermissionExists(42L,"invoice.approve");
        verify(commands).selectList(argThat(wrapper -> wrapper.getSqlSegment().contains("tenant_id") && wrapper.getSqlSegment().contains("is_current") && wrapper.getSqlSegment().contains("deleted_flag")));
    }
    @Test void missingTenantFailsBeforeReferenceQueries() {
        MetaContext.clear(); assertThatThrownBy(assessment::verifyImportReferenceIntegrity).isInstanceOf(IllegalStateException.class); verifyNoInteractions(commands,snapshots,resources);
    }
    @Test void snapshotsDeduplicateLatestValidResourceAndIgnoreMalformedHistoricRows() {
        when(snapshots.findByTenantAndType(42L,"menu")).thenReturn(Arrays.asList(null,new PluginResource(),snapshot("invoice",Map.of("code","invoice","name","old")),snapshot("bad",Map.of("code",List.of("bad"))),snapshot("invoice",Map.of("code","invoice","name","new"))));
        assertThat(assessment.loadImportedResourceSnapshots(42L,ResourceType.MENU,MenuDefinitionDTO.class)).extracting(MenuDefinitionDTO::getName).containsExactly("new");
    }
    @Test void validationContextContainsExternalCapabilitiesAndExplicitReferencePolicy() throws Exception {
        var m=new ObjectMapper().readValue("""
            {"pluginId":"billing","namespace":"billing","commands":[{"code":"create","modelCode":"external_invoice"}],
             "modelFieldBindings":[{"modelCode":"external_invoice","fieldCode":"external_amount"}],
             "menus":[{"code":"invoices","permissionCode":"external.read"}],
             "requires":[{"type":"model","code":"external_invoice"},{"type":"command","code":"external_create"},{"type":"query","code":"external_query"}]}
            """,PluginManifestExtended.class);
        when(resources.checkModelExists(42L,"external_invoice")).thenReturn(true); when(resources.checkFieldExists(42L,"external_amount")).thenReturn(true);
        when(resources.checkPermissionExists(42L,"external.read")).thenReturn(true); when(resources.checkCommandExists(42L,"external_create")).thenReturn(true); when(resources.checkNamedQueryExists(42L,"external_query")).thenReturn(true);
        assessment.runValidationPipeline(m,true,true);
        verify(pipeline).validate(argThat(c -> c.getValidateReferences() && c.getDeferReferenceValidation() && c.getInstalledModelCodes().equals(Set.of("external_invoice")) && c.getInstalledFieldCodes().equals(Set.of("external_amount")) && c.getInstalledPermissionCodes().equals(Set.of("external.read")) && c.getInstalledCommandCodes().equals(Set.of("external_create")) && c.getInstalledNamedQueryCodes().equals(Set.of("external_query"))));
    }

    @Test void conflictPreviewDistinguishesSameOwnerOtherOwnerAndLookupFailures() {
        var m=new PluginManifestExtended(); m.setPluginId("billing");
        m.setModels(List.of(ModelDefinitionDTO.builder().code("own").build(),ModelDefinitionDTO.builder().code("foreign").build(),ModelDefinitionDTO.builder().code("legacy").build(),ModelDefinitionDTO.builder().code("stale").build()));
        when(snapshots.findByTypeAndCode(42L,"MODEL","own")).thenReturn(PluginResource.builder().pluginPid("p-own").build());
        when(snapshots.findByTypeAndCode(42L,"MODEL","foreign")).thenReturn(PluginResource.builder().pluginPid("p-foreign").build());
        when(snapshots.findByTypeAndCode(42L,"MODEL","legacy")).thenReturn(PluginResource.builder().pluginPid("p-legacy").build());
        when(snapshots.findByTypeAndCode(42L,"MODEL","stale")).thenThrow(new IllegalStateException("historic duplicates"));
        var own=new PluginRecord(); own.setPluginId("billing"); var foreign=new PluginRecord(); foreign.setPluginId("inventory");
        when(plugins.findByPid("p-own")).thenReturn(own); when(plugins.findByPid("p-foreign")).thenReturn(foreign);
        assertThat(assessment.checkConflicts(m)).extracting(ImportPreviewResult.ResourceConflict::getResourceCode,ImportPreviewResult.ResourceConflict::getOwnerPluginId).containsExactly(tuple("foreign","inventory"),tuple("legacy","p-legacy"));
    }
    @Test void dependencyAssessmentReportsMissingAndIncompatibleVersionsWithoutHidingSatisfiedOnes() {
        var m=new PluginManifestExtended(); m.setDependencies(List.of("missing",Map.of("pluginId","old","version",">=2.0.0"),Map.of("pluginId","ready","version","^2.0.0")));
        var old=new PluginRecord(); old.setVersion("1.9.0"); var ready=new PluginRecord(); ready.setVersion("2.1.0");
        when(plugins.findByTenantAndPluginId("old")).thenReturn(old); when(plugins.findByTenantAndPluginId("ready")).thenReturn(ready);
        var result=assessment.analyzeDependencies(m);
        assertThat(result.isSatisfied()).isFalse(); assertThat(result.getMissingDependencies()).containsExactly("Plugin: missing","Plugin: old requires >=2.0.0, installed: 1.9.0");
        assertThat(result.getPluginDependencies()).extracting(ImportPreviewResult.PluginDependency::isSatisfied).containsExactly(false,false,true);
    }
}
