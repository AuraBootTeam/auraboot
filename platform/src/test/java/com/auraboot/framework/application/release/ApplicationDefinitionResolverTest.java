package com.auraboot.framework.application.release;

import com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PageSchemaDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApplicationDefinitionResolverTest {
    private final ApplicationDefinitionMapper definitions = mock(ApplicationDefinitionMapper.class);
    private final ApplicationDefinitionBundle bundle = mock(ApplicationDefinitionBundle.class);
    private final ApplicationDefinitionResolver resolver =
            new ApplicationDefinitionResolver(definitions, bundle, new ObjectMapper());

    @Test
    void resolvesOneDefinitionFromTheTenantsExactBoundRelease() {
        var release = release();
        var component = component("edu-core");
        when(definitions.findBoundRelease(42L, "aura-edu")).thenReturn(release);
        when(definitions.findDefinitionComponents(release.releaseId)).thenReturn(List.of(component));
        when(bundle.load(release.code, release.sourceLockIdentity, component)).thenReturn(manifest());

        var resolved = resolver.resolve(42L, "aura-edu", ApplicationDefinitionResolver.ResourceType.COMMAND,
                "edu", "edu:enroll", 7L);

        assertEquals(release.releaseId, resolved.release().releaseId());
        assertEquals("edu-core", resolved.componentKey());
        assertEquals("edu:enroll", resolved.definition().path("code").asText());
        assertEquals(7L, resolved.environmentId());
    }

    @Test
    void rejectsMissingAndAmbiguousDefinitionKeys() {
        var release = release();
        var first = component("edu-core");
        var second = component("edu-extension");
        when(definitions.findBoundRelease(42L, "aura-edu")).thenReturn(release);
        when(definitions.findDefinitionComponents(release.releaseId)).thenReturn(List.of(first, second));
        when(bundle.load(release.code, release.sourceLockIdentity, first)).thenReturn(manifest());
        when(bundle.load(release.code, release.sourceLockIdentity, second)).thenReturn(manifest());

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(42L, "aura-edu",
                ApplicationDefinitionResolver.ResourceType.COMMAND, "edu", "edu:missing", null));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(42L, "aura-edu",
                ApplicationDefinitionResolver.ResourceType.COMMAND, "edu", "edu:enroll", null));
    }

    @Test
    void findsAUniquePageByItsStablePageKeyAcrossComponents() {
        var release = release();
        var first = component("edu-core");
        var second = component("edu-extension");
        when(definitions.findBoundRelease(42L, "aura-edu")).thenReturn(release);
        when(definitions.findDefinitionComponents(release.releaseId)).thenReturn(List.of(first, second));
        when(bundle.load(release.code, release.sourceLockIdentity, first)).thenReturn(pageManifest("edu", "edu_home"));
        when(bundle.load(release.code, release.sourceLockIdentity, second)).thenReturn(pageManifest("ext", "edu_reports"));

        var resolved = resolver.findUnique(42L, "aura-edu",
                ApplicationDefinitionResolver.ResourceType.PAGE, "edu_reports", null);
        var absent = resolver.findUnique(42L, "aura-edu",
                ApplicationDefinitionResolver.ResourceType.PAGE, "platform_settings", null);

        assertEquals("edu-extension", resolved.componentKey());
        assertEquals("ext", resolved.namespace());
        assertEquals("edu_reports", resolved.definition().path("pageKey").asText());
        assertNull(absent);
    }

    @Test
    void rejectsAPageKeyOwnedByMoreThanOneReleaseComponent() {
        var release = release();
        var first = component("edu-core");
        var second = component("edu-extension");
        when(definitions.findBoundRelease(42L, "aura-edu")).thenReturn(release);
        when(definitions.findDefinitionComponents(release.releaseId)).thenReturn(List.of(first, second));
        when(bundle.load(release.code, release.sourceLockIdentity, first)).thenReturn(pageManifest("edu", "edu_home"));
        when(bundle.load(release.code, release.sourceLockIdentity, second)).thenReturn(pageManifest("ext", "edu_home"));

        assertThrows(IllegalArgumentException.class, () -> resolver.findUnique(42L, "aura-edu",
                ApplicationDefinitionResolver.ResourceType.PAGE, "edu_home", null));
    }

    static ApplicationDefinitionMapper.ReleaseRow release() {
        var row = new ApplicationDefinitionMapper.ReleaseRow();
        row.applicationId = 9L;
        row.code = "aura-edu";
        row.releaseId = "01K5ZZZZZZZZZZZZZZZZZZZZZZ";
        row.releaseDigest = "sha256:" + "b".repeat(64);
        row.sourceLockIdentity = "sha256:" + "a".repeat(64);
        row.status = "shadow";
        row.bindingVersion = 1L;
        return row;
    }

    static ApplicationDefinitionMapper.ComponentRow component(String key) {
        var row = new ApplicationDefinitionMapper.ComponentRow();
        row.componentKey = key;
        row.componentVersion = "1.2.3";
        row.componentDigest = "sha256:" + (key.equals("edu-core") ? "c" : "d").repeat(64);
        return row;
    }

    static PluginManifestExtended manifest() {
        var manifest = new PluginManifestExtended();
        manifest.setPluginId("com.auraboot.edu");
        manifest.setNamespace("edu");
        manifest.setVersion("1.2.3");
        manifest.setCommands(List.of(CommandDefinitionDTO.builder()
                .code("edu:enroll").displayName("Enroll").build()));
        return manifest;
    }

    private static PluginManifestExtended pageManifest(String namespace, String pageKey) {
        var manifest = new PluginManifestExtended();
        manifest.setPluginId("com.auraboot." + namespace);
        manifest.setNamespace(namespace);
        manifest.setVersion("1.2.3");
        manifest.setPages(List.of(PageSchemaDTO.builder()
                .pageKey(pageKey).name(pageKey).kind("list")
                .layout(java.util.Map.of("mode", "standard"))
                .blocks(List.of(java.util.Map.of("id", "table"))).build()));
        return manifest;
    }
}
