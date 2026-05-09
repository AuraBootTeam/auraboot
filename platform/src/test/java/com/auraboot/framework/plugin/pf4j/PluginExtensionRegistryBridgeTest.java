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
        when(ext.getCommandType()).thenReturn(code);
        return ext;
    }

    @Test
    void registers_extension_with_unique_command_type() {
        CommandHandlerExtension ext = stubExtension("plugin.demo.run");
        when(pluginManager.getExtensionsOfType(CommandHandlerExtension.class)).thenReturn(List.of(ext));
        when(commandHandlerRegistry.isRegistered("plugin.demo.run")).thenReturn(false);

        bridge.bridge();

        ArgumentCaptor<CommandHandlerRegistry.HandlerMeta> captor =
                ArgumentCaptor.forClass(CommandHandlerRegistry.HandlerMeta.class);
        verify(commandHandlerRegistry).register(captor.capture());
        CommandHandlerRegistry.HandlerMeta meta = captor.getValue();
        assertThat(meta.code()).isEqualTo("plugin.demo.run");
        assertThat(meta.source()).startsWith("plugin:");
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
    void no_extensions_results_in_no_registrations() {
        when(pluginManager.getExtensionsOfType(CommandHandlerExtension.class)).thenReturn(List.of());

        bridge.bridge();

        verify(commandHandlerRegistry, never()).register(any());
    }
}
