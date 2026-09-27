package com.auraboot.framework.application.release;

import com.auraboot.framework.meta.constant.DslRegistry;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PageDefinitionShadowReadServiceTest {
    private final ApplicationDefinitionMapper definitions = mock(ApplicationDefinitionMapper.class);
    private final ApplicationDefinitionResolver resolver = mock(ApplicationDefinitionResolver.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final PageDefinitionShadowReadService service =
            new PageDefinitionShadowReadService(definitions, resolver, mapper, metrics);

    private ApplicationDefinitionMapper.ReleaseRow bound;
    private ApplicationDefinitionResolver.ResolvedDefinition shared;

    @BeforeEach
    void setUp() {
        bound = ApplicationDefinitionResolverTest.release();
        var release = new ApplicationDefinitionResolver.ReleaseSelection(
                bound.applicationId, bound.code, bound.releaseId, bound.releaseDigest,
                bound.sourceLockIdentity, bound.status, bound.bindingVersion);
        shared = new ApplicationDefinitionResolver.ResolvedDefinition(
                release, "edu-core", "sha256:" + "c".repeat(64),
                ApplicationDefinitionResolver.ResourceType.PAGE, "edu", "edu_home", null,
                mapper.valueToTree(sharedPage()));
        when(definitions.findBoundRelease(42L, "aura-edu")).thenReturn(bound);
        when(resolver.findUnique(42L, "aura-edu",
                ApplicationDefinitionResolver.ResourceType.PAGE, "edu_home", null)).thenReturn(shared);
    }

    @Test
    void reportsExactMatchForTheLiveLegacyBaselineAndBoundReleasePage() {
        var result = service.compare(42L, "aura-edu", "edu_home", legacyPage());

        assertEquals(PageDefinitionShadowReadService.Verdict.EXACT_MATCH, result.verdict());
        assertEquals(result.expectedDigest(), result.actualDigest());
        assertEquals(1.0, metrics.get("auraboot.application.definition.shadow.read.total")
                .tag("resource_type", "page").tag("verdict", "exact_match").counter().count());
    }

    @Test
    void reportsDriftWithoutChangingOrMutatingTheLegacyPage() {
        PageSchemaDTO legacy = legacyPage();
        legacy.setBlocks(new ArrayList<>(List.of(Map.of("id", "tenant-custom"))));

        var result = service.compare(42L, "aura-edu", "edu_home", legacy);

        assertEquals(PageDefinitionShadowReadService.Verdict.DRIFTED, result.verdict());
        assertEquals("tenant-custom", ((Map<?, ?>) legacy.getBlocks().getFirst()).get("id"));
    }

    @Test
    void reportsMissingWhenTheReleaseOwnsThePageButTheLegacyReadReturnedNothing() {
        var result = service.compare(42L, "aura-edu", "edu_home", null);

        assertEquals(PageDefinitionShadowReadService.Verdict.MISSING, result.verdict());
        assertNull(result.actualDigest());
    }

    @Test
    void skipsActiveBindingsAndPagesOutsideTheApplicationRelease() {
        bound.status = "active";
        var active = service.compare(42L, "aura-edu", "edu_home", legacyPage());
        assertEquals(PageDefinitionShadowReadService.Verdict.SKIPPED, active.verdict());
        verify(resolver, never()).findUnique(42L, "aura-edu",
                ApplicationDefinitionResolver.ResourceType.PAGE, "edu_home", null);

        bound.status = "shadow";
        when(resolver.findUnique(42L, "aura-edu",
                ApplicationDefinitionResolver.ResourceType.PAGE, "platform_settings", null)).thenReturn(null);
        var platform = service.compare(42L, "aura-edu", "platform_settings", legacyPage());
        assertEquals(PageDefinitionShadowReadService.Verdict.SKIPPED, platform.verdict());
        assertEquals("page-not-owned-by-application", platform.detail());
    }

    @Test
    void recordsResolverFailuresAsErrorsWithoutThrowingIntoTheLegacyRead() {
        when(resolver.findUnique(42L, "aura-edu",
                ApplicationDefinitionResolver.ResourceType.PAGE, "edu_home", null))
                .thenThrow(new IllegalArgumentException("definition bundle unavailable"));

        var result = service.compare(42L, "aura-edu", "edu_home", legacyPage());

        assertEquals(PageDefinitionShadowReadService.Verdict.ERROR, result.verdict());
        assertEquals("IllegalArgumentException:definition bundle unavailable", result.detail());
    }

    private static com.auraboot.framework.plugin.dto.imports.PageSchemaDTO sharedPage() {
        return com.auraboot.framework.plugin.dto.imports.PageSchemaDTO.builder()
                .pageKey("edu_home")
                .name("Education Home")
                .kind("list")
                .profile("admin")
                .layout(Map.of("mode", "standard"))
                .blocks(new ArrayList<>(List.of(Map.of("id", "table"))))
                .modelCode("xy_school")
                .schemaVersion(DslRegistry.PAGE_SCHEMA_CURRENT_VERSION)
                .isTemplate(false)
                .sortWeight(0)
                .extension(Map.of("owner", "edu"))
                .build();
    }

    private static PageSchemaDTO legacyPage() {
        PageSchemaDTO page = new PageSchemaDTO();
        page.setPageKey("edu_home");
        page.setName("Education Home");
        page.setKind("list");
        page.setProfile("admin");
        page.setLayout(Map.of("mode", "standard"));
        page.setBlocks(new ArrayList<>(List.of(Map.of("id", "table"))));
        page.setModelCode("xy_school");
        page.setSchemaVersion(DslRegistry.PAGE_SCHEMA_CURRENT_VERSION);
        page.setIsTemplate(false);
        page.setSortWeight(0);
        page.setExtension(Map.of("owner", "edu"));
        return page;
    }
}
