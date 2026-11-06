package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.meta.dto.PageSchemaDTO;
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
 * <p>Page files are <strong>fail-closed</strong> for the same reason as commands: a dropped page
 * leaves its menu entry pointing at an auto-created stub while the import reports success — the
 * 2026-11-06 orbit finding, where four new pages carrying a localized {@code description} object
 * (the field is a plain string on the import DTO) were silently skipped and the pages stayed
 * stubs behind a green import.</p>
 *
 * <p>Truly auxiliary resource files keep per-file resilience: one malformed file skips itself so
 * the rest of the plugin still loads (locked by
 * {@code PluginDirectoryLoaderTest#shouldSkipInvalidJsonAndLoadValidOnes}).</p>
 */
@Slf4j
final class PluginResourceParsePolicy {

    /**
     * Resource types whose files must never be silently dropped on a parse failure.
     */
    private static final Set<Class<?>> FAIL_CLOSED_TYPES = Set.of(CommandDefinitionDTO.class,
            PageSchemaDTO.class,
            com.auraboot.framework.plugin.dto.imports.PageSchemaDTO.class,
            com.auraboot.framework.openplatform.service.OpenApiResourceContract.class,
            com.auraboot.framework.openplatform.service.OpenApiQueryContract.class);

    private PluginResourceParsePolicy() {
    }

    static <T> List<T> requirePublicDefinitions(List<T> definitions, Class<T> clazz, String resource) {
        if (requiresFailClosed(clazz) && clazz != CommandDefinitionDTO.class
                && (definitions == null || definitions.stream().anyMatch(java.util.Objects::isNull))) {
            throw new PluginException("Public publication file " + resource + " requires non-null definitions");
        }
        return definitions;
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
            if (clazz == PageSchemaDTO.class || clazz == com.auraboot.framework.plugin.dto.imports.PageSchemaDTO.class) {
                throw new PluginException("Plugin page resource file " + resource + " failed to parse as List<"
                        + clazz.getSimpleName() + "> and pages must not be silently dropped (a dropped page leaves "
                        + "its menu pointing at an auto-created stub): " + cause.getMessage(), cause);
            }
            if (clazz != CommandDefinitionDTO.class) {
                throw new PluginException("Public publication resource file " + resource + " failed to parse as List<"
                        + clazz.getSimpleName() + ">; public contracts must not be silently dropped", cause);
            }
            throw new PluginException("Plugin command resource file " + resource + " failed to parse as List<"
                    + clazz.getSimpleName() + "> and commands must not be silently dropped: "
                    + cause.getMessage());
        }
        log.error("Failed to parse plugin resource file {} as List<{}> — skipping this file: {}",
                resource, clazz.getSimpleName(), cause.getMessage());
        return List.of();
    }
}
