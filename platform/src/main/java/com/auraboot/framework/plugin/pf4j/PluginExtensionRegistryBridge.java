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
        int registered = 0;
        int skipped = 0;
        for (CommandHandlerExtension ext : pluginManager.getExtensionsOfType(CommandHandlerExtension.class)) {
            String code = ext.getCommandType();
            if (code == null || code.isBlank()) {
                log.warn("Skipping plugin extension {} — getCommandType() returned blank",
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
            commandHandlerRegistry.register(new CommandHandlerRegistry.HandlerMeta(
                    code, source, "Plugin command handler", null, null));
            registered++;
        }
        log.info("PluginExtensionRegistryBridge: registered {} plugin command handlers ({} skipped)",
                registered, skipped);
    }
}
