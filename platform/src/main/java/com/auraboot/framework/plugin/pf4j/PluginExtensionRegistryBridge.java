package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.meta.registry.CommandHandlerRegistry;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Bridges PF4J-loaded plugin extensions into the import-time
 * {@link CommandHandlerRegistry}.
 *
 * <p>Two registries co-exist for command handlers:
 * <ul>
 *   <li>{@link CommandHandlerRegistry} (this package's sibling under
 *       {@code meta/registry}) — consulted by {@code ExtensionValidator}
 *       at plugin import time to surface
 *       {@code [S-EXT-HANDLER] Command 'X' references unregistered handler}
 *       errors.
 *   <li>{@link ExtensionRegistry} (this package) — consulted at runtime by
 *       {@code HandlerPhase.execute} to dispatch to the actual
 *       {@code CommandHandlerExtension} bean.
 * </ul>
 *
 * <p>Without this bridge, the two registries drift: PF4J loads a plugin's
 * {@code @Extension CommandHandlerExtension} class at startup and runtime
 * dispatch works, but {@code ExtensionValidator} keeps rejecting the
 * plugin's {@code commands.json} because {@code CommandHandlerRegistry}
 * never heard about the handler — leading to the false-positive
 * {@code S-EXT-HANDLER} errors that the 2026-05-07 cleanup mistook for
 * unimplemented handlers.
 *
 * <p>Runs on {@link ApplicationReadyEvent} (after Spring + PF4J have
 * finished startup) with order {@link Ordered#LOWEST_PRECEDENCE} so it
 * fires after {@code DslRegistryInitializer} has populated platform
 * built-ins. The plugin handler entries are appended; built-ins are
 * untouched.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.LOWEST_PRECEDENCE)
public class PluginExtensionRegistryBridge {

    private final AuraPluginManager pluginManager;
    private final CommandHandlerRegistry commandHandlerRegistry;

    @EventListener(ApplicationReadyEvent.class)
    public void bridge() {
        bridgePluginCommandHandlers();
    }

    public BridgeResult bridgePluginCommandHandlers() {
        int registered = 0;
        int skipped = 0;
        for (CommandHandlerExtension ext : pluginManager.getExtensionsOfType(CommandHandlerExtension.class)) {
            String primaryCode = ext.getCommandType();
            var commandTypes = ext.getSupportedCommandTypes();
            if (commandTypes == null) {
                commandTypes = primaryCode == null ? Set.of() : Set.of(primaryCode);
            }
            if (commandTypes == null || commandTypes.isEmpty()) {
                log.warn("Skipping plugin extension {} — getSupportedCommandTypes() returned no command types",
                        ext.getClass().getName());
                skipped++;
                continue;
            }
            for (String code : commandTypes) {
                if (code == null || code.isBlank()) {
                    log.warn("Skipping plugin extension {} — supported command type was blank",
                            ext.getClass().getName());
                    skipped++;
                    continue;
                }
                if (commandHandlerRegistry.isRegistered(code)) {
                    // Platform built-in or another plugin already won — keep first registration.
                    log.debug("Plugin extension {} reuses already-registered code '{}'",
                            ext.getClass().getSimpleName(), code);
                    continue;
                }
                String source = "plugin:" + ext.getClass().getSimpleName();
                String description = code.equals(primaryCode)
                        ? "Plugin command handler"
                        : "Plugin command handler alias of " + primaryCode;
                commandHandlerRegistry.register(new CommandHandlerRegistry.HandlerMeta(
                        code, source, description, null, null));
                registered++;
            }
        }
        log.info("PluginExtensionRegistryBridge: registered {} plugin command handlers ({} skipped)",
                registered, skipped);
        return new BridgeResult(registered, skipped);
    }

    public record BridgeResult(int registered, int skipped) {}
}
