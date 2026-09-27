package com.auraboot.framework.application.release;

import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApplicationPageDefinitionReadServiceTest {
    private final ApplicationDefinitionMapper definitions = mock(ApplicationDefinitionMapper.class);
    private final ApplicationDefinitionResolver resolver = mock(ApplicationDefinitionResolver.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ApplicationPageDefinitionReadService service =
            new ApplicationPageDefinitionReadService(definitions, resolver, mapper);

    private ApplicationDefinitionMapper.ReleaseRow bound;

    @BeforeEach
    void setUp() {
        bound = ApplicationDefinitionResolverTest.release();
        bound.status = "active";
        when(definitions.findBoundRelease(42L, "aura-edu")).thenReturn(bound);
    }

    @Test
    void materializesAnActiveReleasePageWithoutInventingATenantLocalPid() {
        when(resolver.lookupUnique(42L, "aura-edu",
                ApplicationDefinitionResolver.ResourceType.PAGE, "edu_home", null))
                .thenReturn(lookup("active", sharedPage()));

        var result = service.resolve(42L, "aura-edu", "edu_home", null);

        assertThat(result.isReleasePrimary()).isTrue();
        PageSchemaDTO page = result.page();
        assertThat(page.getPid()).isNull();
        assertThat(page.getPageKey()).isEqualTo("edu_home");
        assertThat(page.getRecordSource()).containsEntry("endpoint", "/api/edu/{recordPid}");
        assertThat(page.getRuntime().source()).isEqualTo("APPLICATION_RELEASE");
        assertThat(page.getRuntime().releasePid()).isEqualTo(bound.releaseId);
        assertThat(page.getRuntime().channelVersion()).isEqualTo(bound.bindingVersion);
        assertThat(page.getRuntime().snapshotChecksum()).matches("sha256:[0-9a-f]{64}");
        assertThat(page.getRuntime().cacheKey()).contains("sha256:" + "c".repeat(64));
    }

    @Test
    void keepsShadowBindingsOnTheLegacyReadWithoutResolvingTheBundle() {
        bound.status = "shadow";

        var result = service.resolve(42L, "aura-edu", "edu_home", new PageSchema());

        assertThat(result.source()).isEqualTo(ApplicationPageDefinitionReadService.Source.LEGACY);
        verify(resolver, never()).lookupUnique(42L, "aura-edu",
                ApplicationDefinitionResolver.ResourceType.PAGE, "edu_home", null);
    }

    @Test
    void abandonsTheReleaseReadIfTheBindingChangedDuringResolution() {
        when(resolver.lookupUnique(42L, "aura-edu",
                ApplicationDefinitionResolver.ResourceType.PAGE, "edu_home", null))
                .thenReturn(lookup("shadow", sharedPage()));

        var result = service.resolve(42L, "aura-edu", "edu_home", new PageSchema());

        assertThat(result.source()).isEqualTo(ApplicationPageDefinitionReadService.Source.LEGACY);
    }

    @Test
    void failsWhenAnActiveReleaseOmitsAPageOwnedByOneOfItsPlugins() {
        PageSchema legacy = new PageSchema();
        legacy.setPluginPid("01K60000000000000000000001");
        when(resolver.lookupUnique(42L, "aura-edu",
                ApplicationDefinitionResolver.ResourceType.PAGE, "edu_home", null))
                .thenReturn(lookup("active", null));
        when(definitions.findTenantPluginByPid(42L, legacy.getPluginPid()))
                .thenReturn(PluginRecord.builder().pid(legacy.getPluginPid())
                        .pluginId("com.auraboot.edu").build());

        assertThatThrownBy(() -> service.resolve(42L, "aura-edu", "edu_home", legacy))
                .isInstanceOf(DataRetrievalFailureException.class)
                .hasMessageContaining("missing owned page: edu_home");
    }

    @Test
    void leavesPlatformAndUnrelatedPluginPagesOnTheirExistingReadPath() {
        when(resolver.lookupUnique(42L, "aura-edu",
                ApplicationDefinitionResolver.ResourceType.PAGE, "platform_settings", null))
                .thenReturn(lookup("active", null));
        PageSchema platform = new PageSchema();
        assertThat(service.resolve(42L, "aura-edu", "platform_settings", platform).source())
                .isEqualTo(ApplicationPageDefinitionReadService.Source.LEGACY);

        PageSchema unrelated = new PageSchema();
        unrelated.setPluginPid("01K60000000000000000000002");
        when(resolver.lookupUnique(42L, "aura-edu",
                ApplicationDefinitionResolver.ResourceType.PAGE, "crm_home", null))
                .thenReturn(lookup("active", null));
        when(definitions.findTenantPluginByPid(42L, unrelated.getPluginPid()))
                .thenReturn(PluginRecord.builder().pid(unrelated.getPluginPid())
                        .pluginId("com.auraboot.crm").build());

        assertThat(service.resolve(42L, "aura-edu", "crm_home", unrelated).source())
                .isEqualTo(ApplicationPageDefinitionReadService.Source.LEGACY);
    }

    private ApplicationDefinitionResolver.UniqueDefinitionLookup lookup(
            String status,
            com.auraboot.framework.plugin.dto.imports.PageSchemaDTO page) {
        var release = new ApplicationDefinitionResolver.ReleaseSelection(
                bound.applicationId, bound.code, bound.releaseId, bound.releaseDigest,
                bound.sourceLockIdentity, status, bound.bindingVersion);
        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setPluginId("com.auraboot.edu");
        manifest.setNamespace("edu");
        manifest.setVersion("1.2.3");
        manifest.setPages(page == null ? List.of() : List.of(page));
        var component = new ApplicationDefinitionResolver.ComponentDefinitions(
                "edu-core", "1.2.3", "sha256:" + "c".repeat(64), manifest);
        var definitions = new ApplicationDefinitionResolver.ReleaseDefinitions(release, List.of(component));
        var resolved = page == null ? null : new ApplicationDefinitionResolver.ResolvedDefinition(
                release, component.componentKey(), component.componentDigest(),
                ApplicationDefinitionResolver.ResourceType.PAGE, "edu", page.getPageKey(), null,
                mapper.valueToTree(page));
        return new ApplicationDefinitionResolver.UniqueDefinitionLookup(definitions, resolved);
    }

    private static com.auraboot.framework.plugin.dto.imports.PageSchemaDTO sharedPage() {
        return com.auraboot.framework.plugin.dto.imports.PageSchemaDTO.builder()
                .pageKey("edu_home")
                .name("Education Home")
                .kind("list")
                .profile("admin")
                .layout(Map.of("mode", "standard"))
                .blocks(List.of(Map.of("id", "table")))
                .modelCode("xy_school")
                .recordSource(Map.of("endpoint", "/api/edu/{recordPid}"))
                .extension(Map.of("owner", "edu"))
                .build();
    }
}
