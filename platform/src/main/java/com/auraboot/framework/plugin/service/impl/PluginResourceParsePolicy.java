package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO;
import com.auraboot.framework.plugin.exception.PluginException;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;

/**
 * Shared failure policy for plugin resource files, applied identically by the
 * directory loader ({@link PluginDirectoryLoader}) and the ZIP import path
 * ({@link PluginImportServiceImpl#loadResourceListFromZip}).
 *
 * <p>Command files are <strong>fail-closed</strong>: a {@link CommandDefinitionDTO} file that
 * fails to deserialize must abort the plugin load/import instead of being skipped. A skipped
 * command file silently removes a user-visible business action while the import still reports
 * success — the crm_credit_hold.json incident, where object-style {@code inputFields} dropped
 * both credit hold commands behind an ERROR log line and a green import result.</p>
 *
 * <p>Auxiliary resource files keep per-file resilience: one malformed file skips itself so the
 * rest of the plugin still loads (locked by
 * {@code PluginDirectoryLoaderTest#shouldSkipInvalidJsonAndLoadValidOnes}).</p>
 */
@Slf4j
final class PluginResourceParsePolicy {

    /**
     * Resource types whose files must never be silently dropped on a parse failure.
     */
    private static final Set<Class<?>> FAIL_CLOSED_TYPES = Set.of(CommandDefinitionDTO.class);

    private PluginResourceParsePolicy() {
    }

    static boolean requiresFailClosed(Class<?> resourceType) {
        return FAIL_CLOSED_TYPES.contains(resourceType);
    }

    /**
     * Handle a resource file parse failure: throw for fail-closed resource types, otherwise log
     * and skip the file.
     *
     * @return an empty list when the caller should skip the file
     * @throws PluginException when the resource type is fail-closed
     */
    static <T> List<T> onResourceParseFailure(String resource, Class<T> clazz, Exception cause) {
        if (requiresFailClosed(clazz)) {
            throw new PluginException("Plugin command resource file " + resource + " failed to parse as List<"
                    + clazz.getSimpleName() + "> and commands must not be silently dropped: "
                    + cause.getMessage());
        }
        log.error("Failed to parse plugin resource file {} as List<{}> — skipping this file: {}",
                resource, clazz.getSimpleName(), cause.getMessage());
        return List.of();
    }
}
