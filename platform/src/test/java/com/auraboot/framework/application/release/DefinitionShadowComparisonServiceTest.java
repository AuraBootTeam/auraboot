package com.auraboot.framework.application.release;

import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefinitionShadowComparisonServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ApplicationDefinitionMapper definitions = mock(ApplicationDefinitionMapper.class);
    private final ApplicationDefinitionResolver resolver = mock(ApplicationDefinitionResolver.class);
    private final DefinitionShadowComparisonService comparison =
            new DefinitionShadowComparisonService(definitions, resolver, objectMapper);
    private final PluginRecord plugin = PluginRecord.builder()
            .pid("01K60000000000000000000000").pluginId("com.auraboot.edu").version("1.2.3").build();

    @BeforeEach
    void release() {
        var selection = ApplicationDefinitionResolverTest.release();
        var component = new ApplicationDefinitionResolver.ComponentDefinitions("edu-core", "1.2.3",
                "sha256:" + "c".repeat(64), ApplicationDefinitionResolverTest.manifest());
        when(resolver.publishedStableRelease("aura-edu")).thenReturn(
                new ApplicationDefinitionResolver.ReleaseDefinitions(
                        new ApplicationDefinitionResolver.ReleaseSelection(selection.applicationId, selection.code,
                                selection.releaseId, selection.releaseDigest, selection.sourceLockIdentity,
                                "shadow", 0L), List.of(component)));
        when(resolver.resources(component.manifest(), ApplicationDefinitionResolver.ResourceType.COMMAND))
                .thenReturn(Map.of("edu:enroll", objectMapper.valueToTree(component.manifest().getCommands().getFirst())));
        for (var type : List.of(ApplicationDefinitionResolver.ResourceType.MODEL,
                ApplicationDefinitionResolver.ResourceType.FIELD,
                ApplicationDefinitionResolver.ResourceType.PERMISSION,
                ApplicationDefinitionResolver.ResourceType.MENU,
                ApplicationDefinitionResolver.ResourceType.PAGE)) {
            when(resolver.resources(component.manifest(), type)).thenReturn(Map.of());
        }
        when(definitions.findTenantPlugin(42L, "com.auraboot.edu")).thenReturn(plugin);
    }

    @Test
    void reportsExactMatchWhenVersionAndCanonicalImportSnapshotMatch() {
        var resource = resource(false);
        when(definitions.findComparableResources(42L, plugin.getPid())).thenReturn(List.of(resource));

        var report = comparison.comparePublishedStable(42L, "aura-edu");

        assertEquals(DefinitionShadowComparisonService.Classification.EXACT_MATCH, report.classification());
        assertEquals(1, report.comparedResources());
        assertTrue(report.differences().isEmpty());
    }

    @Test
    void distinguishesTenantDriftVersionMismatchAndMissingResources() {
        when(definitions.findComparableResources(42L, plugin.getPid())).thenReturn(List.of(resource(true)));
        assertEquals(DefinitionShadowComparisonService.Classification.DRIFTED,
                comparison.comparePublishedStable(42L, "aura-edu").classification());

        plugin.setVersion("2.0.0");
        assertEquals(DefinitionShadowComparisonService.Classification.VERSION_MISMATCH,
                comparison.comparePublishedStable(42L, "aura-edu").classification());

        plugin.setVersion("1.2.3");
        when(definitions.findComparableResources(42L, plugin.getPid())).thenReturn(List.of());
        assertEquals(DefinitionShadowComparisonService.Classification.MISSING,
                comparison.comparePublishedStable(42L, "aura-edu").classification());
    }

    private PluginResource resource(boolean modified) {
        return PluginResource.builder()
                .pluginPid(plugin.getPid())
                .resourceType("command")
                .resourceCode("edu:enroll")
                .importSnapshot(objectMapper.convertValue(
                        ApplicationDefinitionResolverTest.manifest().getCommands().getFirst(), Map.class))
                .userModified(modified)
                .build();
    }
}
