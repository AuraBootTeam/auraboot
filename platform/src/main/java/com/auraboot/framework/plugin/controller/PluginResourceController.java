package com.auraboot.framework.plugin.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.service.PluginResourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

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
    private final PluginRecordMapper pluginRecordMapper;

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

        PluginResource resource = pluginResourceService.findByTypeAndCode(tenantId, type, resourceCode);
        if (resource == null || !resource.isManagedByPlugin()) {
            return ApiResponse.success(ResourceOwnerDTO.unmanaged());
        }

        return ApiResponse.success(toOwnerDTO(resource));
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
                PluginResource resource = pluginResourceService.findByTypeAndCode(tenantId, type, ref.code);
                if (resource != null && resource.isManagedByPlugin()) {
                    results.put(key, toOwnerDTO(resource));
                } else {
                    results.put(key, ResourceOwnerDTO.unmanaged());
                }
            } catch (IllegalArgumentException e) {
                results.put(key, ResourceOwnerDTO.unmanaged());
            }
        }

        return ApiResponse.success(results);
    }

    private ResourceOwnerDTO toOwnerDTO(PluginResource resource) {
        PluginRecord plugin = pluginRecordMapper.findByPid(resource.getPluginPid());
        String pluginName = plugin != null ? plugin.getDisplayName() : null;
        String pluginVersion = plugin != null ? plugin.getVersion() : null;
        String pluginId = plugin != null ? plugin.getPluginId() : null;

        return new ResourceOwnerDTO(
                true,
                pluginId,
                pluginName,
                pluginVersion,
                resource.getOwnershipType(),
                Boolean.TRUE.equals(resource.getUserModified()),
                resource.getUserModifiedAt(),
                resource.getCreatedAt(),
                resource.getOwnershipType() != null
                        ? ("user_claimed".equals(resource.getOwnershipType()) ? 0 : 1)
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
