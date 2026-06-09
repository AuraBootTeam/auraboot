package com.auraboot.framework.plugin.controller;

import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import com.auraboot.framework.plugin.pf4j.BackgroundComponentRegistry;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.auraboot.framework.plugin.pf4j.PluginExtensionRegistryBridge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PluginHotloadControllerTest {

    @TempDir
    Path pluginsDir;

    @Mock private AuraPluginManager pluginManager;
    @Mock private ExtensionRegistry extensionRegistry;
    @Mock private PluginExtensionRegistryBridge pluginExtensionRegistryBridge;
    @Mock private BackgroundComponentRegistry backgroundComponentRegistry;
    @Mock private PluginWrapper pluginWrapper;
    @Mock private PluginDescriptor pluginDescriptor;

    private PluginHotloadController controller;

    @BeforeEach
    void setUp() {
        controller = new PluginHotloadController(
                pluginManager,
                extensionRegistry,
                pluginExtensionRegistryBridge,
                backgroundComponentRegistry);
    }

    @Test
    void uploadPlugin_bridgesCommandHandlerRegistryAfterHotload() {
        Path jarPath = pluginsDir.resolve("demo.jar");
        when(pluginManager.getPluginsRoot()).thenReturn(pluginsDir);
        when(pluginManager.hotLoadPlugin(jarPath)).thenReturn("demo-plugin");
        when(pluginManager.getPluginWrapper("demo-plugin")).thenReturn(pluginWrapper);
        when(pluginWrapper.getPluginId()).thenReturn("demo-plugin");
        when(pluginWrapper.getDescriptor()).thenReturn(pluginDescriptor);
        when(pluginWrapper.getPluginState()).thenReturn(PluginState.STARTED);
        when(pluginWrapper.getPluginPath()).thenReturn(jarPath);
        when(pluginWrapper.getPluginClassLoader()).thenReturn(getClass().getClassLoader());
        when(pluginDescriptor.getVersion()).thenReturn("1.0.0");
        when(pluginDescriptor.getPluginDescription()).thenReturn("Demo plugin");
        when(pluginDescriptor.getProvider()).thenReturn("AuraBoot");
        when(pluginDescriptor.getLicense()).thenReturn("Commercial");
        when(pluginDescriptor.getRequires()).thenReturn("*");
        when(pluginExtensionRegistryBridge.bridgePluginCommandHandlers())
                .thenReturn(new PluginExtensionRegistryBridge.BridgeResult(1, 0));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "demo.jar",
                "application/java-archive",
                new byte[] {1, 2, 3});

        var response = controller.uploadPlugin(file);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isTrue();
        verify(extensionRegistry).refreshPluginCache("demo-plugin");
        verify(pluginExtensionRegistryBridge).bridgePluginCommandHandlers();
        verify(backgroundComponentRegistry).register("demo-plugin");
    }

    @Test
    void uploadPlugin_unloadsExistingBackgroundComponentsBeforeReplacingJar() throws Exception {
        Path jarPath = pluginsDir.resolve("demo.jar");
        Files.write(jarPath, new byte[] {9, 9, 9});
        when(pluginManager.getPluginsRoot()).thenReturn(pluginsDir);
        when(pluginManager.isPluginLoaded("demo-plugin")).thenReturn(true);
        when(pluginManager.hotUnloadPlugin("demo-plugin")).thenReturn(true);
        when(pluginManager.hotLoadPlugin(jarPath)).thenReturn("demo-plugin");
        when(pluginManager.getPluginWrapper("demo-plugin")).thenReturn(pluginWrapper);
        when(pluginWrapper.getPluginId()).thenReturn("demo-plugin");
        when(pluginWrapper.getDescriptor()).thenReturn(pluginDescriptor);
        when(pluginWrapper.getPluginState()).thenReturn(PluginState.STARTED);
        when(pluginWrapper.getPluginPath()).thenReturn(jarPath);
        when(pluginWrapper.getPluginClassLoader()).thenReturn(getClass().getClassLoader());
        when(pluginDescriptor.getVersion()).thenReturn("1.0.0");
        when(pluginDescriptor.getPluginDescription()).thenReturn("Demo plugin");
        when(pluginDescriptor.getProvider()).thenReturn("AuraBoot");
        when(pluginDescriptor.getLicense()).thenReturn("Commercial");
        when(pluginDescriptor.getRequires()).thenReturn("*");
        when(pluginExtensionRegistryBridge.bridgePluginCommandHandlers())
                .thenReturn(new PluginExtensionRegistryBridge.BridgeResult(1, 0));

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "demo.jar",
                "application/java-archive",
                jarBytes("demo-plugin"));

        var response = controller.uploadPlugin(file);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isTrue();
        assertThat(Files.size(jarPath)).isGreaterThan(3);

        InOrder order = inOrder(backgroundComponentRegistry, extensionRegistry, pluginManager);
        order.verify(backgroundComponentRegistry).unregister("demo-plugin");
        order.verify(extensionRegistry).removePluginFromCache("demo-plugin");
        order.verify(pluginManager).hotUnloadPlugin("demo-plugin");
        order.verify(pluginManager).hotLoadPlugin(jarPath);
        verify(backgroundComponentRegistry).register("demo-plugin");
    }

    @Test
    void reloadPlugin_restartsBackgroundComponentsAroundHotReload() {
        Path jarPath = pluginsDir.resolve("demo.jar");
        when(pluginManager.isPluginLoaded("demo-plugin")).thenReturn(true);
        when(pluginManager.hotReloadPlugin("demo-plugin")).thenReturn(true);
        when(pluginManager.getPluginWrapper("demo-plugin")).thenReturn(pluginWrapper);
        when(pluginWrapper.getPluginId()).thenReturn("demo-plugin");
        when(pluginWrapper.getDescriptor()).thenReturn(pluginDescriptor);
        when(pluginWrapper.getPluginState()).thenReturn(PluginState.STARTED);
        when(pluginWrapper.getPluginPath()).thenReturn(jarPath);
        when(pluginWrapper.getPluginClassLoader()).thenReturn(getClass().getClassLoader());
        when(pluginDescriptor.getVersion()).thenReturn("1.0.0");
        when(pluginDescriptor.getPluginDescription()).thenReturn("Demo plugin");
        when(pluginDescriptor.getProvider()).thenReturn("AuraBoot");
        when(pluginDescriptor.getLicense()).thenReturn("Commercial");
        when(pluginDescriptor.getRequires()).thenReturn("*");
        when(pluginExtensionRegistryBridge.bridgePluginCommandHandlers())
                .thenReturn(new PluginExtensionRegistryBridge.BridgeResult(1, 0));

        var response = controller.reloadPlugin("demo-plugin");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isTrue();
        verify(backgroundComponentRegistry).unregister("demo-plugin");
        verify(extensionRegistry).refreshPluginCache("demo-plugin");
        verify(pluginExtensionRegistryBridge).bridgePluginCommandHandlers();
        verify(backgroundComponentRegistry).register("demo-plugin");
    }

    private static byte[] jarBytes(String pluginId) throws Exception {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("Plugin-Id", pluginId);
        attributes.putValue("Plugin-Version", "1.0.0");
        attributes.putValue("Plugin-Class", "com.example.Plugin");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream ignored = new JarOutputStream(out, manifest)) {
            // A manifest-only JAR is sufficient for upload lifecycle tests.
        }
        return out.toByteArray();
    }
}
