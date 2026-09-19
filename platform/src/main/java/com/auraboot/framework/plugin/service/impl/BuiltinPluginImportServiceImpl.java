package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.dto.imports.ImportExecuteResult;
import com.auraboot.framework.plugin.dto.imports.ImportPreviewResult;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.service.BuiltinPluginImportService;
import com.auraboot.framework.plugin.service.PluginImportService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Imports built-in plugins during tenant bootstrap.
 *
 * <p>Built-in plugins are located in the project's {@code plugins/} directory.
 * The base directory is auto-detected ({@code platform/../plugins} when running
 * via {@code gradlew bootRun}) or configured via {@code aura.builtin-plugins.dir}.
 *
 * <p>Phase 3 uses a 2-profile split (see {@link BuiltinPluginImportService}
 * javadoc): {@link Profile#CORE} (always imported) vs
 * {@link Profile#DEMO} (opt-in platform showcases). The
 * {@code includeDemoPlugins} flag is used only by explicit repair/admin flows.
 * The first-install {@code /api/bootstrap/setup} path is intentionally minimal;
 * reset/init scripts import plugin profiles through {@code scripts/import-plugins.sh}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuiltinPluginImportServiceImpl implements BuiltinPluginImportService {

    private final PluginImportService pluginImportService;
    private final PluginRecordMapper pluginRecordMapper;
    private final UserService userService;

    @Value("${aura.builtin-plugins.dir:}")
    private String builtinPluginsDir;

    /**
     * Deployment-declared product plugins imported for every NEW tenant after the
     * core profile, in the listed order. Comma-separated entries; each entry is a
     * plugin source directory — absolute, or relative to the builtin plugins base
     * directory. Example:
     * {@code aura.tenant.product-plugins=/srv/aura-edu/plugin-aura/edu-core,/srv/aura-edu/plugin-aura/edu-engine}.
     */
    @Value("${aura.tenant.product-plugins:}")
    private String tenantProductPlugins;

    /** Profile classifier for built-in plugins. */
    enum Profile {
        /** Always imported — required by every deployment. */
        CORE,
        /** Opt-in showcase / demo plugins — gated by {@code AURABOOT_DEMO_SEED}. */
        DEMO
    }

    /**
     * Catalogue of built-in plugins. Each entry corresponds to a subdirectory
     * under the plugins base directory. Plugins missing on disk are warned and
     * skipped at import time.
     */
    private static final List<BuiltinPlugin> BUILTIN_PLUGINS = List.of(
            // ── Core profile (always imported) ──────────────────────────
            // Order matters: dependencies first. platform-admin's menus
            // reference parents/permissions defined in core-meta, so it must
            // import before platform-admin or validation fails
            // ("Menu X references missing parent Y / missing permission Z").
            new BuiltinPlugin("core-meta",             "com.auraboot.core-meta",           Profile.CORE),
            new BuiltinPlugin("core-aurabot",          "com.auraboot.core-aurabot",        Profile.CORE),
            new BuiltinPlugin("page-manager",          "com.auraboot.page-manager",        Profile.CORE),
            new BuiltinPlugin("org-management",        "com.auraboot.org-management",      Profile.CORE),
            new BuiltinPlugin("platform-admin",        "com.auraboot.platform-admin",      Profile.CORE),
            // ── Demo profile (only when includeDemoPlugins=true) ────────
            new BuiltinPlugin("showcase",              "com.auraboot.showcase",            Profile.DEMO),
            new BuiltinPlugin("agent-control-plane",   "com.auraboot.agent-control-plane", Profile.DEMO)
    );

    @Override
    public void importForTenant(Long tenantId, Long userId) {
        importForTenant(tenantId, userId, false);
    }

    @Override
    public void importForTenant(Long tenantId, Long userId, boolean includeDemoPlugins) {
        String baseDir = resolveBaseDir();
        if (baseDir == null) {
            log.warn("Cannot resolve built-in plugins directory, skipping import. "
                    + "Set aura.builtin-plugins.dir to configure.");
            return;
        }

        List<BuiltinPlugin> selected = selectPlugins(includeDemoPlugins);
        log.info("Importing built-in plugins for tenant {}: baseDir={}, includeDemo={}, plugins={}",
                tenantId, baseDir, includeDemoPlugins, selected.size());

        User importUser = userService.findByUserId(userId);
        if (importUser == null || importUser.getPid() == null || importUser.getPid().isBlank()) {
            throw new IllegalStateException(
                    "Built-in plugin import user is missing or has no pid: userId=" + userId);
        }

        // Setup MetaContext for the import
        MetaContext previousContext = MetaContext.exists() ? MetaContext.get() : null;
        MetaContext.setContext(tenantId, userId, importUser.getPid(), importUser.getEmail());

        try {
            for (BuiltinPlugin plugin : selected) {
                importPlugin(baseDir, plugin, tenantId);
            }
            for (Path productDir : productPluginDirectories(baseDir)) {
                importPluginDirectory(productDir.toString(), productDir.getFileName().toString(), tenantId);
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

    private List<BuiltinPlugin> selectPlugins(boolean includeDemoPlugins) {
        List<BuiltinPlugin> out = new ArrayList<>(BUILTIN_PLUGINS.size());
        for (BuiltinPlugin p : BUILTIN_PLUGINS) {
            if (p.profile == Profile.CORE || includeDemoPlugins) {
                out.add(p);
            }
        }
        return out;
    }

    private void importPlugin(String baseDir, BuiltinPlugin plugin, Long tenantId) {
        importPluginDirectory(baseDir + "/" + plugin.dirName, plugin.pluginId, tenantId);
    }

    private void importPluginDirectory(String pluginPath, String pluginLabel, Long tenantId) {
        if (!Files.isDirectory(Path.of(pluginPath))) {
            log.warn("Plugin directory not found, skipping: {}", pluginPath);
            return;
        }

        try {
            ImportPreviewResult preview = pluginImportService.parseDirectory(pluginPath);

            if (!preview.isValid()) {
                log.error("Plugin validation failed: {} - {}",
                        pluginLabel, preview.getErrors());
                return;
            }

            String pluginId = preview.getPluginId() != null ? preview.getPluginId() : pluginLabel;

            // Check if already imported with same version — skip if up-to-date
            PluginRecord existing = pluginRecordMapper.findByTenantAndPluginId(pluginId);
            if (existing != null) {
                String diskVersion = preview.getVersion();
                String dbVersion = existing.getVersion();
                if (diskVersion != null && diskVersion.equals(dbVersion)) {
                    log.info("Plugin already up-to-date (v{}), skipping: {}",
                            dbVersion, pluginId);
                    return;
                }
                log.info("Plugin version changed ({} -> {}), re-importing: {}",
                        dbVersion, diskVersion, pluginId);
            }

            ImportRequest request = new ImportRequest();

            ImportExecuteResult result = pluginImportService.execute(
                    preview.getImportId(), request);

            if (result.isSuccess()) {
                log.info("Plugin imported successfully: {} ({}ms)",
                        pluginId, result.getDurationMs());
            } else {
                log.error("Plugin import failed: {} - {}",
                        pluginId, result.getErrorMessage());
            }
        } catch (Exception e) {
            log.error("Error importing plugin: {}", pluginLabel, e);
        }
    }

    /**
     * Resolve {@code aura.tenant.product-plugins} to existing plugin source directories,
     * preserving the declared order. Entries that do not resolve to a directory are
     * warned and skipped — a deployment naming a product it did not ship must not
     * break tenant creation.
     */
    private List<Path> productPluginDirectories(String baseDir) {
        if (tenantProductPlugins == null || tenantProductPlugins.isBlank()) {
            return List.of();
        }
        List<Path> dirs = new ArrayList<>();
        for (String entry : tenantProductPlugins.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Path dir = Path.of(trimmed);
            if (!dir.isAbsolute()) {
                dir = Path.of(baseDir).resolve(trimmed);
            }
            dir = dir.normalize();
            if (!Files.isDirectory(dir)) {
                log.warn("Configured product plugin directory not found, skipping: {}", dir);
                continue;
            }
            dirs.add(dir);
        }
        return dirs;
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

    private record BuiltinPlugin(String dirName, String pluginId, Profile profile) {}
}
