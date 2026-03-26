package com.auraboot.framework.plugin.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.plugin.dto.imports.OwnershipType;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.dto.uninstall.*;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.service.PluginResourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for plugin uninstall and resource ownership management.
 */
@Slf4j
@RestController
@RequestMapping("/api/plugins")
@RequiredArgsConstructor
@Tag(name = "Plugin Uninstall", description = "Plugin uninstall and resource ownership management")
@RequirePermission(MetaPermission.PLUGIN_MANAGE)
public class PluginUninstallController {

    private final PluginResourceService resourceService;

    // ==================== Uninstall Preview & Execute ====================

    @GetMapping("/{pluginPid}/uninstall/preview")
    @Operation(summary = "Preview uninstall", description = "Get a preview of what will happen when uninstalling a plugin")
    public ResponseEntity<UninstallPreviewResult> getUninstallPreview(
            @Parameter(description = "Plugin PID")
            @PathVariable String pluginPid) {

        Long tenantId = MetaContext.getCurrentTenantId();
        log.info("Generating uninstall preview for plugin: {} (tenant: {})", pluginPid, tenantId);

        UninstallPreviewResult result = resourceService.generateUninstallPreview(pluginPid, tenantId);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{pluginPid}/uninstall")
    @Operation(summary = "Execute uninstall", description = "Execute plugin uninstall with user decisions for modified resources")
    public ResponseEntity<UninstallResult> executeUninstall(
            @Parameter(description = "Plugin PID")
            @PathVariable String pluginPid,
            @RequestBody UninstallRequest request) {

        Long tenantId = MetaContext.getCurrentTenantId();
        log.info("Executing uninstall for plugin: {} (tenant: {}, force: {})",
                pluginPid, tenantId, request.isForce());

        UninstallResult result = resourceService.executeUninstall(pluginPid, tenantId, request);

        if (result.isSuccess()) {
            log.info("Plugin {} uninstalled successfully: {} deleted, {} detached, {} kept",
                    pluginPid, result.getDeletedCount(), result.getDetachedCount(), result.getKeptCount());
        } else {
            log.error("Plugin {} uninstall failed: {}", pluginPid, result.getErrorMessage());
        }

        return ResponseEntity.ok(result);
    }

    // ==================== Resource Ownership Management ====================

    @GetMapping("/{pluginPid}/resources")
    @Operation(summary = "List plugin resources", description = "Get all resources created by a plugin")
    public ResponseEntity<List<PluginResource>> listPluginResources(
            @Parameter(description = "Plugin PID")
            @PathVariable String pluginPid) {

        List<PluginResource> resources = resourceService.findByPluginPid(pluginPid);
        return ResponseEntity.ok(resources);
    }

    @GetMapping("/{pluginPid}/resources/modified")
    @Operation(summary = "List modified resources", description = "Get all user-modified resources for a plugin")
    public ResponseEntity<List<PluginResource>> listModifiedResources(
            @Parameter(description = "Plugin PID")
            @PathVariable String pluginPid) {

        List<PluginResource> resources = resourceService.findModifiedResources(pluginPid);
        return ResponseEntity.ok(resources);
    }

    @GetMapping("/{pluginPid}/resources/claimed")
    @Operation(summary = "List claimed resources", description = "Get all user-claimed resources for a plugin")
    public ResponseEntity<List<PluginResource>> listClaimedResources(
            @Parameter(description = "Plugin PID")
            @PathVariable String pluginPid) {

        List<PluginResource> resources = resourceService.findUserClaimedResources(pluginPid);
        return ResponseEntity.ok(resources);
    }

    @GetMapping("/{pluginPid}/resources/stats")
    @Operation(summary = "Resource ownership statistics", description = "Get counts by ownership type for a plugin")
    public ResponseEntity<Map<OwnershipType, Integer>> getResourceStats(
            @Parameter(description = "Plugin PID")
            @PathVariable String pluginPid) {

        Map<OwnershipType, Integer> stats = resourceService.countByOwnershipType(pluginPid);
        return ResponseEntity.ok(stats);
    }

    // ==================== Single Resource Operations ====================

    @GetMapping("/resources/ownership")
    @Operation(summary = "Check resource ownership", description = "Check the ownership type of a specific resource")
    public ResponseEntity<ResourceOwnershipInfo> getResourceOwnership(
            @Parameter(description = "Resource type")
            @RequestParam ResourceType resourceType,
            @Parameter(description = "Resource code")
            @RequestParam String resourceCode) {

        Long tenantId = MetaContext.getCurrentTenantId();
        PluginResource resource = resourceService.findByTypeAndCode(tenantId, resourceType, resourceCode);

        if (resource == null) {
            return ResponseEntity.ok(ResourceOwnershipInfo.builder()
                    .resourceType(resourceType)
                    .resourceCode(resourceCode)
                    .managed(false)
                    .build());
        }

        return ResponseEntity.ok(ResourceOwnershipInfo.builder()
                .resourceType(resourceType)
                .resourceCode(resourceCode)
                .managed(true)
                .pluginPid(resource.getPluginPid())
                .ownershipType(resource.getOwnershipTypeEnum())
                .userModified(Boolean.TRUE.equals(resource.getUserModified()))
                .canModify(resource.allowsUserModification())
                .build());
    }

    @PostMapping("/resources/mark-modified")
    @Operation(summary = "Mark resource as modified", description = "Mark a resource as modified by user")
    public ResponseEntity<Void> markAsModified(
            @Parameter(description = "Resource type")
            @RequestParam ResourceType resourceType,
            @Parameter(description = "Resource code")
            @RequestParam String resourceCode) {

        Long tenantId = MetaContext.getCurrentTenantId();
        resourceService.markAsUserModified(tenantId, resourceType, resourceCode);
        log.info("Marked resource as modified: {} {} (tenant: {})", resourceType, resourceCode, tenantId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/resources/claim")
    @Operation(summary = "Claim resource ownership", description = "Transfer resource ownership to user (detach from plugin)")
    public ResponseEntity<Void> claimResource(
            @Parameter(description = "Resource type")
            @RequestParam ResourceType resourceType,
            @Parameter(description = "Resource code")
            @RequestParam String resourceCode) {

        Long tenantId = MetaContext.getCurrentTenantId();
        resourceService.claimByUser(tenantId, resourceType, resourceCode);
        log.info("User claimed resource: {} {} (tenant: {})", resourceType, resourceCode, tenantId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/resources/diff")
    @Operation(summary = "Get resource diff", description = "Get differences between import snapshot and current state")
    public ResponseEntity<List<ResourceDiff>> getResourceDiff(
            @Parameter(description = "Resource type")
            @RequestParam ResourceType resourceType,
            @Parameter(description = "Resource code")
            @RequestParam String resourceCode) {

        Long tenantId = MetaContext.getCurrentTenantId();
        List<ResourceDiff> diffs = resourceService.detectModifications(tenantId, resourceType, resourceCode);
        return ResponseEntity.ok(diffs);
    }

    // ==================== DTO for ownership info ====================

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ResourceOwnershipInfo {
        private ResourceType resourceType;
        private String resourceCode;
        private boolean managed;
        private String pluginPid;
        private OwnershipType ownershipType;
        private boolean userModified;
        private boolean canModify;
    }
}
