package com.auraboot.framework.plugin.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.plugin.config.PlatformProperties;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.service.PlatformVersionChecker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST API for managing installed plugins (configuration plugins from database).
 */
@Slf4j
@RestController
@RequestMapping("/api/plugins")
@RequiredArgsConstructor
@Tag(name = "Plugin Management", description = "Manage installed configuration plugins")
public class PluginController {

    private final PluginRecordMapper pluginRecordMapper;
    private final PlatformVersionChecker platformVersionChecker;
    private final PlatformProperties platformProperties;

    /**
     * List all installed plugins for the current tenant.
     */
    @GetMapping
    @Operation(summary = "List installed plugins", description = "Get all installed plugins for the current tenant")
    @RequirePermission(MetaPermission.PLUGIN_READ)
    public ApiResponse<List<PluginDTO>> listPlugins() {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            return ApiResponse.success(List.of());
        }

        List<PluginRecord> plugins = pluginRecordMapper.findByTenant();
        List<PluginDTO> dtos = plugins.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());

        return ApiResponse.success(dtos);
    }

    /**
     * Get a specific plugin by PID.
     */
    @GetMapping("/{pid}")
    @Operation(summary = "Get plugin details", description = "Get plugin details by PID")
    @RequirePermission(MetaPermission.PLUGIN_READ)
    public ApiResponse<PluginDTO> getPlugin(@PathVariable String pid) {
        PluginRecord plugin = pluginRecordMapper.findByPid(pid);
        if (plugin == null) {
            return ApiResponse.error("Plugin not found");
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId != null && !tenantId.equals(plugin.getTenantId())) {
            return ApiResponse.error("Plugin not found");
        }

        return ApiResponse.success(toDTO(plugin));
    }

    /**
     * Enable a plugin.
     */
    @PostMapping("/{pid}/enable")
    @Operation(summary = "Enable plugin", description = "Enable an installed plugin")
    @RequirePermission(MetaPermission.PLUGIN_MANAGE)
    public ApiResponse<PluginDTO> enablePlugin(@PathVariable String pid) {
        PluginRecord plugin = pluginRecordMapper.findByPid(pid);
        if (plugin == null) {
            return ApiResponse.error("Plugin not found");
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId != null && !tenantId.equals(plugin.getTenantId())) {
            return ApiResponse.error("Plugin not found");
        }

        pluginRecordMapper.markAsEnabled(pid);
        plugin = pluginRecordMapper.findByPid(pid);

        log.info("Plugin enabled: {} ({})", plugin.getPluginId(), pid);
        return ApiResponse.success(toDTO(plugin));
    }

    /**
     * Disable a plugin.
     */
    @PostMapping("/{pid}/disable")
    @Operation(summary = "Disable plugin", description = "Disable an installed plugin")
    @RequirePermission(MetaPermission.PLUGIN_MANAGE)
    public ApiResponse<PluginDTO> disablePlugin(@PathVariable String pid) {
        PluginRecord plugin = pluginRecordMapper.findByPid(pid);
        if (plugin == null) {
            return ApiResponse.error("Plugin not found");
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId != null && !tenantId.equals(plugin.getTenantId())) {
            return ApiResponse.error("Plugin not found");
        }

        pluginRecordMapper.markAsDisabled(pid);
        plugin = pluginRecordMapper.findByPid(pid);

        log.info("Plugin disabled: {} ({})", plugin.getPluginId(), pid);
        return ApiResponse.success(toDTO(plugin));
    }

    /**
     * Get platform version compatibility matrix for all installed plugins.
     * Returns the current platform version and a compatibility summary per plugin.
     */
    @GetMapping("/version-check")
    @Operation(summary = "Plugin version compatibility matrix",
            description = "Check all installed plugins against the current platform version. "
                    + "Returns COMPATIBLE / WARN_OLDER / WARN_NEWER / INCOMPATIBLE per plugin.")
    @RequirePermission(MetaPermission.PLUGIN_READ)
    public ApiResponse<VersionCheckResult> checkVersionCompatibility() {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<PluginRecord> plugins = tenantId == null ? List.of() : pluginRecordMapper.findByTenant();

        String platformVersion = platformProperties.getVersion();
        List<PluginCompatibilityDTO> results = plugins.stream()
                .map(p -> {
                    String minVer = p.getManifest() != null ? p.getManifest().getMinPlatformVersion() : null;
                    String maxVer = p.getManifest() != null ? p.getManifest().getMaxPlatformVersion() : null;
                    PlatformVersionChecker.CompatibilityResult check = platformVersionChecker.check(minVer, maxVer);
                    return new PluginCompatibilityDTO(
                            p.getPid(),
                            p.getPluginId(),
                            p.getVersion(),
                            p.getDisplayName(),
                            minVer,
                            maxVer,
                            check.status().name(),
                            check.message());
                })
                .collect(Collectors.toList());

        return ApiResponse.success(new VersionCheckResult(platformVersion, results));
    }

    private PluginDTO toDTO(PluginRecord plugin) {
        String minVer = plugin.getManifest() != null ? plugin.getManifest().getMinPlatformVersion() : null;
        String maxVer = plugin.getManifest() != null ? plugin.getManifest().getMaxPlatformVersion() : null;
        PlatformVersionChecker.CompatibilityResult check = platformVersionChecker.check(minVer, maxVer);
        return new PluginDTO(
                plugin.getPid(),
                plugin.getPluginId(),
                plugin.getNamespace(),
                plugin.getVersion(),
                plugin.getDisplayName(),
                plugin.getDescription(),
                plugin.getAuthor(),
                mapStatus(plugin.getStatus()),
                plugin.getInstalledAt(),
                plugin.getUpdatedAt(),
                minVer,
                maxVer,
                check.status().name(),
                check.message()
        );
    }

    /**
     * Map database status to frontend expected status.
     * Database: installed, enabled, disabled, failed (lowercase)
     * Frontend expects: installed, active, stopped, error
     */
    private String mapStatus(String status) {
        if (status == null) {
            return "installed";
        }
        return switch (status.toLowerCase()) {
            case "enabled" -> "active";
            case "disabled" -> "stopped";
            case "failed" -> "error";
            default -> status;
        };
    }

    /**
     * DTO for plugin information, including version compatibility result.
     */
    public record PluginDTO(
            String pid,
            String pluginId,
            String namespace,
            String version,
            String displayName,
            String description,
            String author,
            String status,
            Instant installedAt,
            Instant updatedAt,
            String minPlatformVersion,
            String maxPlatformVersion,
            String compatibilityStatus,
            String compatibilityMessage
    ) {}

    /**
     * DTO for a single plugin's compatibility check result.
     */
    public record PluginCompatibilityDTO(
            String pid,
            String pluginId,
            String version,
            String displayName,
            String minPlatformVersion,
            String maxPlatformVersion,
            String compatibilityStatus,
            String compatibilityMessage
    ) {}

    /**
     * Result of the version compatibility matrix check.
     */
    public record VersionCheckResult(
            String platformVersion,
            List<PluginCompatibilityDTO> plugins
    ) {}
}
