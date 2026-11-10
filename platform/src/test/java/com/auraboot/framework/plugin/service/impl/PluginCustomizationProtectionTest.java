package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.exception.PluginException;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.auraboot.framework.plugin.service.PluginResourceTracker;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.dashboard.dto.DashboardDTO;
import com.auraboot.framework.dashboard.service.DashboardService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PluginCustomizationProtectionTest {
    final PluginResourceMapper resources = mock(PluginResourceMapper.class);
    final PluginImportSupport support = new PluginImportSupport(null, new ObjectMapper(), resources,
            LoggerFactory.getLogger(getClass()));
    final DashboardService dashboards = mock(DashboardService.class);

    @AfterEach void clear() { MetaContext.clear(); }

    @Test void unknownModificationStateCannotBecomeAnUnmodifiedResource() {
        var failure = new IllegalStateException("lookup unavailable");
        when(resources.lockModificationState(42L, "page", "invoice")).thenThrow(failure);
        assertThatThrownBy(() -> support.shouldSkipForOverwriteSafe(42L,
                ImportRequest.ConflictStrategy.OVERWRITE_SAFE, ResourceType.PAGE, "invoice"))
                .isInstanceOf(PluginException.class).hasCause(failure);
    }

    @ParameterizedTest @EnumSource(value=ResourceType.class, names={"PAGE","DASHBOARD"})
    void safeOverwriteLocksAndPreservesCustomerChanges(ResourceType type) {
        var resource = new PluginResource(); resource.setUserModified(true);
        when(resources.lockModificationState(42L,type.code(),"invoice")).thenReturn(new PluginResourceMapper.ModificationState("tracked",true));
        assertThat(support.shouldSkipForOverwriteSafe(42L,ImportRequest.ConflictStrategy.OVERWRITE_SAFE,type,"invoice")).isTrue();
        verify(resources).lockModificationState(42L,type.code(),"invoice");
    }

    @Test void explicitOverwriteDoesNotPretendToReadProtection() {
        assertThat(support.shouldSkipForOverwriteSafe(42L,ImportRequest.ConflictStrategy.OVERWRITE,ResourceType.PAGE,"invoice")).isFalse();
        verifyNoInteractions(resources);
    }

    @Test void trackerUsesStoredLowercaseIdentityAndPropagatesWriteFailures() {
        MetaContext.setSystemTenantContext(42L);
        var resource = new PluginResource(); resource.setUserModified(false);
        when(resources.lockByTypeAndCode(42L,"dashboard","invoice")).thenReturn(resource);
        doThrow(new IllegalStateException("write unavailable")).when(resources).updateById(resource);
        assertThatThrownBy(() -> new PluginResourceTracker(resources).markAsUserModified(ResourceType.DASHBOARD,"invoice"))
                .isInstanceOf(PluginException.class).hasCauseInstanceOf(IllegalStateException.class);
        verify(resources,never()).findByTypeAndCode(any(),any(),any());
    }

    @ParameterizedTest @EnumSource(ImportRequest.ConflictStrategy.class)
    void dashboardsHonorTheSameFourStrategies(ImportRequest.ConflictStrategy strategy) {
        var dto = new DashboardDefinitionDTO(); dto.setCode("invoice"); dto.setTitle("New title");
        dto.setWidgets(List.of(Map.of("id","total","type","metric")));
        var existing = new DashboardDTO(); existing.setPid("board-pid"); existing.setCode("invoice");
        when(dashboards.findByCode("invoice")).thenReturn(existing);
        var tracked = new PluginResource(); tracked.setUserModified(true);
        when(resources.lockModificationState(42L,"dashboard","invoice")).thenReturn(new PluginResourceMapper.ModificationState("tracked",true));
        var importer = new PluginPageNavigationImporter(null,null,null,null,dashboards,null,null,
                new ObjectMapper(),null,null,new HashMap<>(),LoggerFactory.getLogger(getClass()),support);
        if (strategy == ImportRequest.ConflictStrategy.ERROR) {
            assertThatThrownBy(() -> importer.importDashboard(dto,"plugin","import",42L,strategy)).isInstanceOf(PluginException.class);
        } else {
            var result = importer.importDashboard(dto,"plugin","import",42L,strategy);
            assertThat(result.getResourceType()).isEqualTo("dashboard");
            assertThat(result.getAction()).isEqualTo(strategy == ImportRequest.ConflictStrategy.OVERWRITE ? "update" : "skip");
        }
        if (strategy == ImportRequest.ConflictStrategy.OVERWRITE) verify(dashboards).update(eq("board-pid"),any());
        else verify(dashboards,never()).update(any(),any());
    }
    @Test void skippedImportCannotResetTheBaselineOrModificationFlag() {
        var skipped=new PluginResource();skipped.setAction("skip");
        var service=mock(PluginImportServiceImpl.class,CALLS_REAL_METHODS);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"pluginResourceMapper",resources);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service,"saveOrUpdatePluginResource",skipped,42L);
        verifyNoInteractions(resources);
    }
    @Test void existingUntrackedContentMustBePreservedUntilExplicitOverwrite() {
        assertThat(support.shouldSkipExistingForOverwriteSafe(42L,ImportRequest.ConflictStrategy.OVERWRITE_SAFE,ResourceType.DASHBOARD,"invoice")).isTrue();
    }

    @ParameterizedTest @EnumSource(ImportRequest.ConflictStrategy.class)
    void pagesHonorTheSameFourStrategies(ImportRequest.ConflictStrategy strategy) {
        var pageService=mock(com.auraboot.framework.meta.service.PageSchemaService.class);
        var pageMapper=mock(com.auraboot.framework.meta.mapper.PageSchemaMapper.class);
        var facade=mock(PluginResourceImporterImpl.class);
        when(facade.checkPageExists(42L,"invoice_form")).thenReturn(true);
        var existing=new com.auraboot.framework.meta.dto.PageSchemaDTO();existing.setPid("page-pid");existing.setPageKey("invoice_form");
        when(pageService.findAnyByPageKey("invoice_form")).thenReturn(existing);
        var tracked=new PluginResource();tracked.setUserModified(true);
        when(resources.lockModificationState(42L,"page","invoice_form")).thenReturn(new PluginResourceMapper.ModificationState("tracked",true));
        var importer=new PluginPageNavigationImporter(facade,null,null,pageService,null,null,null,new ObjectMapper(),pageMapper,null,new HashMap<>(),LoggerFactory.getLogger(getClass()),support);
        var dto=new PageSchemaDTO();dto.setPageKey("invoice_form");dto.setKind("form");dto.setBlocks(List.of());
        if(strategy==ImportRequest.ConflictStrategy.ERROR) {
            assertThatThrownBy(() -> importer.importPage(dto,"plugin","import",42L,strategy,false)).isInstanceOf(PluginException.class);
        } else {
            var result=importer.importPage(dto,"plugin","import",42L,strategy,false);
            assertThat(result.getAction()).isEqualTo(strategy==ImportRequest.ConflictStrategy.OVERWRITE ? "update" : "skip");
        }
    }
    @Test void anEditedHistoricalStubMustNotBypassProtection() {
        var pageService=mock(com.auraboot.framework.meta.service.PageSchemaService.class);
        var facade=mock(PluginResourceImporterImpl.class);when(facade.checkPageExists(42L,"invoice_form")).thenReturn(true);
        var existing=new com.auraboot.framework.meta.dto.PageSchemaDTO();existing.setPid("page-pid");existing.setPageKey("invoice_form");existing.setRowVersion(2);existing.setExtension(Map.of("auto_created",true));
        when(pageService.findAnyByPageKey("invoice_form")).thenReturn(existing);
        var tracked=new PluginResource();tracked.setUserModified(true);when(resources.lockModificationState(42L,"page","invoice_form")).thenReturn(new PluginResourceMapper.ModificationState("tracked",true));
        var importer=new PluginPageNavigationImporter(facade,null,null,pageService,null,null,null,new ObjectMapper(),null,null,new HashMap<>(),LoggerFactory.getLogger(getClass()),support);
        var dto=new PageSchemaDTO();dto.setPageKey("invoice_form");dto.setKind("form");
        assertThat(importer.importPage(dto,"plugin","import",42L,ImportRequest.ConflictStrategy.OVERWRITE_SAFE,false).getAction()).isEqualTo("skip");
    }

    @Test void zeroRowMarkerUpdateMustAbortTheCustomerSave() {
        MetaContext.setSystemTenantContext(42L);
        var tracked=new PluginResource();tracked.setUserModified(false);
        when(resources.lockByTypeAndCode(42L,"page","invoice")).thenReturn(tracked);
        when(resources.updateById(tracked)).thenReturn(0);
        assertThatThrownBy(() -> new PluginResourceTracker(resources).markAsUserModified(ResourceType.PAGE,"invoice"))
                .isInstanceOf(PluginException.class);
    }

    @Test void aSuccessfulReadWithAnUnknownMarkerStillCannotAuthorizeOverwrite() {
        when(resources.lockModificationState(42L,"page","invoice")).thenReturn(new PluginResourceMapper.ModificationState("tracked",null));
        assertThatThrownBy(() -> support.shouldSkipExistingForOverwriteSafe(42L,ImportRequest.ConflictStrategy.OVERWRITE_SAFE,ResourceType.PAGE,"invoice"))
                .isInstanceOf(PluginException.class);
    }

    @Test void dashboardPreviewUsesActualExistenceAndProtectsUntrackedContent() {
        MetaContext.setContext(42L,7L,"reviewer","Reviewer");
        var importer = mock(PluginResourceImporter.class);
        when(importer.checkDashboardExists(42L,"invoice")).thenReturn(true);
        var manifest = new PluginManifestExtended();
        var dashboard = new DashboardDefinitionDTO();dashboard.setCode("invoice");dashboard.setTitle("Invoice");
        manifest.setDashboards(List.of(dashboard));
        var result = new ImportPreviewResult();
        new PluginImportChangePreview(resources,importer,null,null,null).generateChangePreview(manifest,result,null);
        var change=result.getChanges().get(ResourceType.DASHBOARD.name()).getFirst();
        assertThat(change.getAction()).isEqualTo(ResourceAction.UPDATE);
        assertThat(change.isUserModified()).isTrue();
        verify(resources).findModificationState(42L,"dashboard","invoice");
    }

    @Test void previewNamesChangesWithoutPromisingAnOverwriteAndKeepsItsWireContract() throws Exception {
        var result = ImportPreviewResult.builder().isUpgrade(true).build();
        var change = ImportPreviewResult.ResourceChange.builder().resourceType(ResourceType.PAGE).resourceCode("invoice")
                .action(ResourceAction.UPDATE).userModified(true).build();
        result.addChange(ResourceType.PAGE, change);
        new PluginImportChangePreview(resources, null, null, null, null).summarizeUserModifiedConflicts(result);
        assertThat(result.getWarnings()).singleElement().asString().contains("customer changes").doesNotContain("will be overwritten");
        var wire = new ObjectMapper().valueToTree(result);
        assertThat(wire.path("upgrade").asBoolean()).isTrue();
        assertThat(wire.path("actionCounts").path("UPDATE").asInt()).isEqualTo(1);
    }

    @Test void previewCannotHideUnknownProtectionState() {
        var failure=new IllegalStateException("preview unavailable");
        when(resources.findModificationState(42L,"page","invoice")).thenThrow(failure);
        var preview=new PluginImportChangePreview(resources,null,null,null,null);
        var change=ImportPreviewResult.ResourceChange.builder().action(ResourceAction.UPDATE).build();
        assertThatThrownBy(() -> preview.enrichWithUserModified(42L,ResourceType.PAGE,"invoice",change)).isSameAs(failure);
        doReturn(new PluginResourceMapper.ModificationState("tracked",null)).when(resources).findModificationState(42L,"page","invoice");
        assertThatThrownBy(() -> preview.enrichWithUserModified(42L,ResourceType.PAGE,"invoice",change)).isInstanceOf(PluginException.class);
    }

}
