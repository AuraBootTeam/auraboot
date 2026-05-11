package com.auraboot.framework.plugin.controller;

import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.plugin.pf4j.AuraPluginManager;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import com.auraboot.framework.plugin.pf4j.PluginExtensionRegistryBridge;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.PluginDescriptor;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for plugin hot-loading operations.
 *
 * Endpoints:
 * - POST   /api/plugins/hotload/upload         - Upload and hot-load a JAR plugin
 * - POST   /api/plugins/hotload/{pluginId}/reload - Reload a plugin
 * - DELETE /api/plugins/hotload/{pluginId}     - Unload a plugin
 * - GET    /api/plugins/hotload                - List all loaded plugins
 * - GET    /api/plugins/hotload/{pluginId}     - Get plugin details
 * - GET    /api/plugins/hotload/extensions     - Get extension statistics
 */
@Slf4j
@RestController
@RequestMapping("/api/plugins/hotload")
@RequiredArgsConstructor
@RequirePermission(MetaPermission.PLUGIN_MANAGE)
public class PluginHotloadController {

    private final AuraPluginManager pluginManager;
    private final ExtensionRegistry extensionRegistry;
    private final PluginExtensionRegistryBridge pluginExtensionRegistryBridge;

    /**
     * Upload and hot-load a plugin JAR.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<HotloadResult> uploadPlugin(@RequestParam("file") MultipartFile file) {
        log.info("Uploading plugin JAR: {} ({} bytes)", file.getOriginalFilename(), file.getSize());

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    HotloadResult.failure("Empty file", "The uploaded file is empty"));
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.endsWith(".jar")) {
            return ResponseEntity.badRequest().body(
                    HotloadResult.failure("Invalid file type", "Only JAR files are accepted"));
        }

        try {
            // Save file to plugins directory
            Path targetPath = pluginManager.getPluginsRoot().resolve(filename);
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Saved plugin JAR to: {}", targetPath);

            // Hot-load the plugin
            String pluginId = pluginManager.hotLoadPlugin(targetPath);
            if (pluginId == null) {
                Files.deleteIfExists(targetPath);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                        HotloadResult.failure("Load failed", "Failed to load the plugin"));
            }

            refreshPluginRegistries(pluginId);

            // Get plugin info
            PluginWrapper wrapper = pluginManager.getPluginWrapper(pluginId);
            PluginInfo info = toPluginInfo(wrapper);

            log.info("Successfully hot-loaded plugin: {}", pluginId);
            return ResponseEntity.ok(HotloadResult.success("upload", pluginId, info));

        } catch (IOException e) {
            log.error("Failed to save plugin file", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    HotloadResult.failure("IO error", e.getMessage()));
        }
    }

    /**
     * Reload a plugin.
     */
    @PostMapping("/{pluginId}/reload")
    public ResponseEntity<HotloadResult> reloadPlugin(@PathVariable String pluginId) {
        log.info("Reloading plugin: {}", pluginId);

        if (!pluginManager.isPluginLoaded(pluginId)) {
            return ResponseEntity.notFound().build();
        }

        boolean success = pluginManager.hotReloadPlugin(pluginId);
        if (!success) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    HotloadResult.failure("Reload failed", "Failed to reload the plugin"));
        }

        refreshPluginRegistries(pluginId);

        PluginWrapper wrapper = pluginManager.getPluginWrapper(pluginId);
        PluginInfo info = toPluginInfo(wrapper);

        log.info("Successfully reloaded plugin: {}", pluginId);
        return ResponseEntity.ok(HotloadResult.success("reload", pluginId, info));
    }

    /**
     * Unload a plugin.
     */
    @DeleteMapping("/{pluginId}")
    public ResponseEntity<HotloadResult> unloadPlugin(
            @PathVariable String pluginId,
            @RequestParam(defaultValue = "false") boolean deleteJar) {
        log.info("Unloading plugin: {} (deleteJar: {})", pluginId, deleteJar);

        PluginWrapper wrapper = pluginManager.getPluginWrapper(pluginId);
        if (wrapper == null) {
            return ResponseEntity.notFound().build();
        }

        Path pluginPath = wrapper.getPluginPath();
        boolean success = pluginManager.hotUnloadPlugin(pluginId);
        if (!success) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    HotloadResult.failure("Unload failed", "Failed to unload the plugin"));
        }

        // Remove from extension cache
        extensionRegistry.removePluginFromCache(pluginId);

        // Optionally delete the JAR file
        if (deleteJar) {
            try {
                Files.deleteIfExists(pluginPath);
                log.info("Deleted plugin JAR: {}", pluginPath);
            } catch (IOException e) {
                log.warn("Failed to delete plugin JAR: {}", pluginPath, e);
            }
        }

        log.info("Successfully unloaded plugin: {}", pluginId);
        return ResponseEntity.ok(HotloadResult.success("unload", pluginId, null));
    }

    /**
     * List all loaded plugins.
     */
    @GetMapping
    public ResponseEntity<PluginListResponse> listPlugins() {
        List<PluginInfo> plugins = pluginManager.getAllPlugins().stream()
                .map(this::toPluginInfo)
                .collect(Collectors.toList());

        return ResponseEntity.ok(new PluginListResponse(
                plugins.size(),
                (int) plugins.stream().filter(p -> "started".equals(p.state())).count(),
                plugins));
    }

    /**
     * Get plugin details.
     */
    @GetMapping("/{pluginId}")
    public ResponseEntity<PluginInfo> getPlugin(@PathVariable String pluginId) {
        PluginWrapper wrapper = pluginManager.getPluginWrapper(pluginId);
        if (wrapper == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(toPluginInfo(wrapper));
    }

    /**
     * Start a stopped plugin.
     */
    @PostMapping("/{pluginId}/start")
    public ResponseEntity<HotloadResult> startPlugin(@PathVariable String pluginId) {
        log.info("Starting plugin: {}", pluginId);

        PluginWrapper wrapper = pluginManager.getPluginWrapper(pluginId);
        if (wrapper == null) {
            return ResponseEntity.notFound().build();
        }

        if (wrapper.getPluginState() == PluginState.STARTED) {
            return ResponseEntity.ok(HotloadResult.success("start", pluginId, toPluginInfo(wrapper)));
        }

        PluginState state = pluginManager.startPlugin(pluginId);
        if (state != PluginState.STARTED) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    HotloadResult.failure("Start failed", "Failed to start the plugin, state: " + state));
        }

        refreshPluginRegistries(pluginId);
        return ResponseEntity.ok(HotloadResult.success("start", pluginId, toPluginInfo(wrapper)));
    }

    /**
     * Stop a running plugin.
     */
    @PostMapping("/{pluginId}/stop")
    public ResponseEntity<HotloadResult> stopPlugin(@PathVariable String pluginId) {
        log.info("Stopping plugin: {}", pluginId);

        PluginWrapper wrapper = pluginManager.getPluginWrapper(pluginId);
        if (wrapper == null) {
            return ResponseEntity.notFound().build();
        }

        if (wrapper.getPluginState() == PluginState.STOPPED) {
            return ResponseEntity.ok(HotloadResult.success("stop", pluginId, toPluginInfo(wrapper)));
        }

        PluginState state = pluginManager.stopPlugin(pluginId);
        if (state != PluginState.STOPPED) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    HotloadResult.failure("Stop failed", "Failed to stop the plugin, state: " + state));
        }

        extensionRegistry.removePluginFromCache(pluginId);
        return ResponseEntity.ok(HotloadResult.success("stop", pluginId, toPluginInfo(wrapper)));
    }

    /**
     * Get extension statistics.
     */
    @GetMapping("/extensions")
    public ResponseEntity<ExtensionStats> getExtensionStats() {
        Map<String, Object> stats = extensionRegistry.getStatistics();
        Map<String, List<String>> keys = extensionRegistry.getRegisteredKeys();

        return ResponseEntity.ok(new ExtensionStats(stats, keys));
    }

    /**
     * Get plugin manager statistics.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("pluginManager", pluginManager.getStatistics());
        stats.put("extensions", extensionRegistry.getStatistics());
        return ResponseEntity.ok(stats);
    }

    // ========== Helper Methods ==========

    private PluginInfo toPluginInfo(PluginWrapper wrapper) {
        PluginDescriptor descriptor = wrapper.getDescriptor();
        return new PluginInfo(
                wrapper.getPluginId(),
                descriptor.getVersion(),
                descriptor.getPluginDescription(),
                descriptor.getProvider(),
                descriptor.getLicense(),
                descriptor.getRequires(),
                wrapper.getPluginState().name(),
                wrapper.getPluginPath().toString(),
                wrapper.getPluginClassLoader().getClass().getSimpleName()
        );
    }

    private void refreshPluginRegistries(String pluginId) {
        extensionRegistry.refreshPluginCache(pluginId);
        PluginExtensionRegistryBridge.BridgeResult result =
                pluginExtensionRegistryBridge.bridgePluginCommandHandlers();
        log.info("Plugin registries refreshed after plugin {}: {} command handlers registered, {} skipped",
                pluginId, result.registered(), result.skipped());
    }

    // ========== Response DTOs ==========

    public record HotloadResult(
            boolean success,
            String operation,
            String pluginId,
            String error,
            String errorDetail,
            PluginInfo plugin,
            Instant timestamp
    ) {
        public static HotloadResult success(String operation, String pluginId, PluginInfo plugin) {
            return new HotloadResult(true, operation, pluginId, null, null, plugin, Instant.now());
        }

        public static HotloadResult failure(String error, String errorDetail) {
            return new HotloadResult(false, null, null, error, errorDetail, null, Instant.now());
        }
    }

    public record PluginInfo(
            String pluginId,
            String version,
            String description,
            String provider,
            String license,
            String requires,
            String state,
            String path,
            String classLoader
    ) {}

    public record PluginListResponse(
            int total,
            int started,
            List<PluginInfo> plugins
    ) {}

    public record ExtensionStats(
            Map<String, Object> counts,
            Map<String, List<String>> registeredKeys
    ) {}
}
