package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.meta.registry.CommandHandlerRegistry;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito unit tests for {@link PluginExtensionRegistryBridge}.
 */
@ExtendWith(MockitoExtension.class)
class PluginExtensionRegistryBridgeTest {

    @Mock private AuraPluginManager pluginManager;
    @Mock private CommandHandlerRegistry commandHandlerRegistry;

    @InjectMocks private PluginExtensionRegistryBridge bridge;

    private CommandHandlerExtension stubExtension(String code) {
        CommandHandlerExtension ext = org.mockito.Mockito.mock(CommandHandlerExtension.class);
        Set<String> supportedCodes = code == null || code.isBlank() ? Set.of() : Set.of(code);
        when(ext.getSupportedCommandTypes()).thenReturn(supportedCodes);
        return ext;
    }

    private CommandHandlerExtension stubExtension(String primaryCode, Set<String> supportedCodes) {
        CommandHandlerExtension ext = org.mockito.Mockito.mock(CommandHandlerExtension.class);
        when(ext.getSupportedCommandTypes()).thenReturn(supportedCodes);
        return ext;
    }

    @Test
    void registers_extension_with_unique_command_type() {
        CommandHandlerExtension ext = stubExtension("plugin.demo.run");
        when(pluginManager.getExtensionsOfType(CommandHandlerExtension.class)).thenReturn(List.of(ext));
        when(commandHandlerRegistry.isRegistered("plugin.demo.run")).thenReturn(false);

        PluginExtensionRegistryBridge.BridgeResult result = bridge.bridgePluginCommandHandlers();

        ArgumentCaptor<CommandHandlerRegistry.HandlerMeta> captor =
                ArgumentCaptor.forClass(CommandHandlerRegistry.HandlerMeta.class);
        verify(commandHandlerRegistry).register(captor.capture());
        CommandHandlerRegistry.HandlerMeta meta = captor.getValue();
        assertThat(meta.code()).isEqualTo("plugin.demo.run");
        assertThat(meta.source()).startsWith("plugin:");
        assertThat(result.registered()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(0);
    }

    @Test
    void skips_extension_with_blank_command_type() {
        CommandHandlerExtension blank = stubExtension("");
        CommandHandlerExtension nullCode = stubExtension(null);
        when(pluginManager.getExtensionsOfType(CommandHandlerExtension.class))
                .thenReturn(List.of(blank, nullCode));

        bridge.bridge();

        verify(commandHandlerRegistry, never()).register(any());
    }

    @Test
    void skips_when_code_already_registered() {
        CommandHandlerExtension ext = stubExtension("builtin.update");
        when(pluginManager.getExtensionsOfType(CommandHandlerExtension.class)).thenReturn(List.of(ext));
        when(commandHandlerRegistry.isRegistered("builtin.update")).thenReturn(true);

        bridge.bridge();

        verify(commandHandlerRegistry, never()).register(any());
    }

    @Test
    void registers_only_unregistered_among_mixed_inputs() {
        CommandHandlerExtension already = stubExtension("first.handler");
        CommandHandlerExtension fresh = stubExtension("second.handler");
        CommandHandlerExtension blank = stubExtension("   ");
        when(pluginManager.getExtensionsOfType(CommandHandlerExtension.class))
                .thenReturn(List.of(already, fresh, blank));
        when(commandHandlerRegistry.isRegistered("first.handler")).thenReturn(true);
        when(commandHandlerRegistry.isRegistered("second.handler")).thenReturn(false);

        bridge.bridge();

        verify(commandHandlerRegistry, times(1)).register(any());
    }

    @Test
    void registers_supported_command_type_aliases() {
        CommandHandlerExtension ext = stubExtension(
                "pe:allocate_inventory",
                Set.of("pe:allocate_inventory", "pe:hold_inventory"));
        when(pluginManager.getExtensionsOfType(CommandHandlerExtension.class)).thenReturn(List.of(ext));
        when(commandHandlerRegistry.isRegistered("pe:allocate_inventory")).thenReturn(false);
        when(commandHandlerRegistry.isRegistered("pe:hold_inventory")).thenReturn(false);

        PluginExtensionRegistryBridge.BridgeResult result = bridge.bridgePluginCommandHandlers();

        ArgumentCaptor<CommandHandlerRegistry.HandlerMeta> captor =
                ArgumentCaptor.forClass(CommandHandlerRegistry.HandlerMeta.class);
        verify(commandHandlerRegistry, times(2)).register(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(CommandHandlerRegistry.HandlerMeta::code)
                .containsExactlyInAnyOrder("pe:allocate_inventory", "pe:hold_inventory");
        assertThat(result.registered()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(0);
    }

    @Test
    void no_extensions_results_in_no_registrations() {
        when(pluginManager.getExtensionsOfType(CommandHandlerExtension.class)).thenReturn(List.of());

        bridge.bridge();

        verify(commandHandlerRegistry, never()).register(any());
    }
}
