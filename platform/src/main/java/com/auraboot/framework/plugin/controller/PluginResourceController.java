package com.auraboot.framework.plugin.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.plugin.dto.PluginResourceOwner;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.service.PluginResourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for querying plugin resource ownership information.
 * Used by frontend to display managed-resource badges and protection banners.
 */
@RestController
@RequestMapping("/api/plugins/resources")
@RequiredArgsConstructor
@Tag(name = "Plugin Resources", description = "Query resource ownership for protection hints")
public class PluginResourceController {

    private final PluginResourceService pluginResourceService;

    /**
     * Query the owner of a single resource.
     */
    @GetMapping("/owner")
    @Operation(summary = "Get resource owner", description = "Check if a resource is managed by a plugin")
    @RequirePermission(MetaPermission.PLUGIN_READ)
    public ApiResponse<ResourceOwnerDTO> getResourceOwner(
            @RequestParam String resourceType,
            @RequestParam String resourceCode) {

        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            return ApiResponse.success(ResourceOwnerDTO.unmanaged());
        }

        ResourceType type;
        try {
            type = ResourceType.fromCode(resourceType);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error("Invalid resource type: " + resourceType);
        }

        PluginResourceOwner owner = pluginResourceService.findResourceOwner(tenantId, type, resourceCode);
        if (owner == null) {
            return ApiResponse.success(ResourceOwnerDTO.unmanaged());
        }

        return ApiResponse.success(toOwnerDTO(owner));
    }

    /**
     * Batch query resource owners. Used by list pages to show managed badges.
     */
    @PostMapping("/owners")
    @Operation(summary = "Batch get resource owners", description = "Check ownership for multiple resources at once")
    @RequirePermission(MetaPermission.PLUGIN_READ)
    public ApiResponse<Map<String, ResourceOwnerDTO>> batchGetResourceOwners(
            @RequestBody BatchOwnerRequest request) {

        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null || request.resources == null || request.resources.isEmpty()) {
            return ApiResponse.success(Map.of());
        }

        Map<String, ResourceOwnerDTO> results = new HashMap<>();

        for (ResourceRef ref : request.resources) {
            String key = ref.type + ":" + ref.code;
            try {
                ResourceType type = ResourceType.fromCode(ref.type);
                PluginResourceOwner owner = pluginResourceService.findResourceOwner(tenantId, type, ref.code);
                if (owner != null) {
                    results.put(key, toOwnerDTO(owner));
                } else {
                    results.put(key, ResourceOwnerDTO.unmanaged());
                }
            } catch (IllegalArgumentException e) {
                results.put(key, ResourceOwnerDTO.unmanaged());
            }
        }

        return ApiResponse.success(results);
    }

    /**
     * Export a plugin's imported config, grouped by resource type, for the DSL
     * reconciler's `aura dsl pull`. Returns each resource's importSnapshot — the
     * manifest DTO captured at import time — which is re-importable and lets the
     * CLI adopt a running instance's config as a local baseline.
    */
    @GetMapping("/export")
    @Operation(
            summary = "Export a plugin's imported config",
            description = "Resource importSnapshots grouped by type, for `aura dsl pull`")
    @RequirePermission(MetaPermission.PLUGIN_READ)
    public ApiResponse<Map<String, List<Map<String, Object>>>> exportPluginConfig(
            @RequestParam String pluginId) {

        return ApiResponse.success(pluginResourceService.exportPluginConfig(pluginId));
    }

    private ResourceOwnerDTO toOwnerDTO(PluginResourceOwner owner) {
        return new ResourceOwnerDTO(
                true,
                owner.pluginId(),
                owner.pluginName(),
                owner.pluginVersion(),
                owner.ownershipType(),
                owner.userModified(),
                owner.userModifiedAt(),
                owner.importedAt(),
                owner.ownershipType() != null
                        ? ("user_claimed".equals(owner.ownershipType()) ? 0 : 1)
                        : 1
        );
    }

    // ==================== Request/Response DTOs ====================

    public record ResourceOwnerDTO(
            boolean managed,
            String pluginId,
            String pluginName,
            String pluginVersion,
            String ownershipType,
            boolean userModified,
            Instant userModifiedAt,
            Instant importedAt,
            int protectionLevel
    ) {
        static ResourceOwnerDTO unmanaged() {
            return new ResourceOwnerDTO(false, null, null, null, null, false, null, null, 0);
        }
    }

    public record BatchOwnerRequest(List<ResourceRef> resources) {}

    public record ResourceRef(String type, String code) {}
}
