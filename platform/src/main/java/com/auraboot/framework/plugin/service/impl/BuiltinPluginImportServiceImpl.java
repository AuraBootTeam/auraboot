package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.dto.imports.ImportExecuteResult;
import com.auraboot.framework.plugin.dto.imports.ImportPreviewResult;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.service.BuiltinPluginImportService;
import com.auraboot.framework.plugin.service.PluginImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Imports built-in plugins during tenant bootstrap.
 *
 * Built-in plugins are located in the project's plugins/ directory.
 * The base directory is auto-detected (platform/../plugins when running
 * via gradlew bootRun) or configured via aura.builtin-plugins.dir property.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuiltinPluginImportServiceImpl implements BuiltinPluginImportService {

    private final PluginImportService pluginImportService;
    private final PluginRecordMapper pluginRecordMapper;

    @Value("${aura.builtin-plugins.dir:}")
    private String builtinPluginsDir;

    /**
     * List of built-in plugin directory names to import.
     * Each entry corresponds to a subdirectory under the plugins base directory.
     */
    private static final List<BuiltinPlugin> BUILTIN_PLUGINS = List.of(
            new BuiltinPlugin("org-management", "com.auraboot.org-management"),
            new BuiltinPlugin("platform-admin", "com.auraboot.platform-admin")
    );

    @Override
    public void importForTenant(Long tenantId, Long userId) {
        String baseDir = resolveBaseDir();
        if (baseDir == null) {
            log.warn("Cannot resolve built-in plugins directory, skipping import. "
                    + "Set aura.builtin-plugins.dir to configure.");
            return;
        }

        log.info("Importing built-in plugins for tenant {}: baseDir={}", tenantId, baseDir);

        // Setup MetaContext for the import
        MetaContext previousContext = MetaContext.exists() ? MetaContext.get() : null;
        MetaContext.setContext(tenantId, userId, null, null);

        try {
            for (BuiltinPlugin plugin : BUILTIN_PLUGINS) {
                importPlugin(baseDir, plugin, tenantId);
            }
        } finally {
            MetaContext.clear();
            if (previousContext != null) {
                MetaContext.setContext(
                        previousContext.getTenantId(),
                        previousContext.getUserId(),
                        previousContext.getUserPid(),
                        previousContext.getUsername()
                );
            }
        }
    }

    private void importPlugin(String baseDir, BuiltinPlugin plugin, Long tenantId) {
        String pluginPath = baseDir + "/" + plugin.dirName;

        if (!Files.isDirectory(Path.of(pluginPath))) {
            log.warn("Built-in plugin directory not found: {}", pluginPath);
            return;
        }

        try {
            ImportPreviewResult preview = pluginImportService.parseDirectory(pluginPath);

            if (!preview.isValid()) {
                log.error("Built-in plugin validation failed: {} - {}",
                        plugin.pluginId, preview.getErrors());
                return;
            }

            // Check if already imported with same version — skip if up-to-date
            PluginRecord existing = pluginRecordMapper.findByTenantAndPluginId(plugin.pluginId);
            if (existing != null) {
                String diskVersion = preview.getVersion();
                String dbVersion = existing.getVersion();
                if (diskVersion != null && diskVersion.equals(dbVersion)) {
                    log.info("Built-in plugin already up-to-date (v{}), skipping: {}",
                            dbVersion, plugin.pluginId);
                    return;
                }
                log.info("Built-in plugin version changed ({} -> {}), re-importing: {}",
                        dbVersion, diskVersion, plugin.pluginId);
            }

            ImportRequest request = new ImportRequest();

            ImportExecuteResult result = pluginImportService.execute(
                    preview.getImportId(), request);

            if (result.isSuccess()) {
                log.info("Built-in plugin imported successfully: {} ({}ms)",
                        plugin.pluginId, result.getDurationMs());
            } else {
                log.error("Built-in plugin import failed: {} - {}",
                        plugin.pluginId, result.getErrorMessage());
            }
        } catch (Exception e) {
            log.error("Error importing built-in plugin: {}", plugin.pluginId, e);
        }
    }

    private String resolveBaseDir() {
        if (builtinPluginsDir != null && !builtinPluginsDir.isBlank()) {
            return builtinPluginsDir;
        }

        // Auto-detect: CWD is typically `platform/`, so `../plugins` is the plugins dir
        Path cwd = Path.of(System.getProperty("user.dir"));

        Path pluginsDir = cwd.resolve("../plugins").normalize();
        if (Files.isDirectory(pluginsDir)) {
            return pluginsDir.toString();
        }

        // Also try CWD/plugins (if running from project root)
        pluginsDir = cwd.resolve("plugins");
        if (Files.isDirectory(pluginsDir)) {
            return pluginsDir.toString();
        }

        return null;
    }

    private record BuiltinPlugin(String dirName, String pluginId) {}
}
