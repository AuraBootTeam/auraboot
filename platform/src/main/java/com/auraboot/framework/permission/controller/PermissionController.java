package com.auraboot.framework.permission.controller;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.dto.PermissionBindRequest;
import com.auraboot.framework.permission.dto.PermissionCreateRequest;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.dto.PermissionReferenceDTO;
import com.auraboot.framework.permission.dto.PermissionTreeNodeDTO;
import com.auraboot.framework.permission.dto.PermissionUpdateRequest;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.service.PluginResourceTracker;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Permission Controller
 *
 * <p>REST API for Permission management.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /api/permissions - Create permission</li>
 *   <li>PUT /api/permissions/{id} - Update permission</li>
 *   <li>DELETE /api/permissions/{id} - Delete permission (soft delete)</li>
 *   <li>GET /api/permissions/{id} - Get permission by ID</li>
 *   <li>GET /api/permissions - List permissions</li>
 *   <li>GET /api/permissions/resource-type/{resourceType} - List by resource type</li>
 *   <li>POST /api/permissions/{id}/deprecate - Deprecate permission</li>
 *   <li>POST /api/permissions/{id}/archive - Archive permission</li>
 * </ul>
 *
 * @author AuraBoot Platform
 * @since V4
 */
@Slf4j
@RestController
@RequestMapping("/api/permissions")
@RequiredArgsConstructor
@Validated
@RequirePermission(MetaPermission.PERMISSION_MANAGE)
@Tag(name = "Permissions", description = "Permission management")
public class PermissionController {
    
    private final PermissionService permissionService;
    private final PluginResourceTracker pluginResourceTracker;
    
    /**
     * Get permission tree
     *
     * <p>Returns all active permissions organized as a hierarchical tree
     * using parent_id relationships. Root nodes have null parent_id.
     *
     * @return Tree of permissions
     */
    @GetMapping("/tree")
    public ApiResponse<List<PermissionTreeNodeDTO>> getPermissionTree() {
        log.debug("Building permission tree");

        List<PermissionDTO> allActive = permissionService.findAllActive();

        // Build tree from flat list using parent_id
        Map<Long, PermissionTreeNodeDTO> nodeMap = new LinkedHashMap<>();
        List<PermissionTreeNodeDTO> withParent = new ArrayList<>();
        List<PermissionTreeNodeDTO> orphans = new ArrayList<>();

        // First pass: create all nodes
        for (PermissionDTO dto : allActive) {
            nodeMap.put(dto.getId(), PermissionTreeNodeDTO.fromDTO(dto));
        }

        // Second pass: link children to parents
        for (PermissionDTO dto : allActive) {
            PermissionTreeNodeDTO node = nodeMap.get(dto.getId());
            if (dto.getParentId() != null && nodeMap.containsKey(dto.getParentId())) {
                nodeMap.get(dto.getParentId()).getChildren().add(node);
                withParent.add(node);
            } else {
                orphans.add(node);
            }
        }

        // If all orphans (no hierarchy), group by resourceCode for better UX
        if (withParent.isEmpty() && !orphans.isEmpty()) {
            Map<String, PermissionTreeNodeDTO> groups = new LinkedHashMap<>();
            long virtualId = -1;
            for (PermissionTreeNodeDTO node : orphans) {
                String groupKey = node.getModule() != null ? node.getModule() : "other";
                groups.computeIfAbsent(groupKey, k -> {
                    PermissionTreeNodeDTO group = new PermissionTreeNodeDTO();
                    group.setId(virtualId);
                    group.setPid("virtual-" + k);
                    group.setCode(k);
                    group.setName(k);
                    group.setType("group");
                    group.setStatus(StatusConstants.ACTIVE);
                    return group;
                }).getChildren().add(node);
                // Decrement for next group
            }
            // Fix IDs
            long id = -1;
            List<PermissionTreeNodeDTO> result = new ArrayList<>();
            for (PermissionTreeNodeDTO group : groups.values()) {
                group.setId(id--);
                result.add(group);
            }
            return ApiResponse.success(result);
        }

        return ApiResponse.success(orphans);
    }

    /**
     * Create permission
     *
     * <p>Note: permissions are typically generated by the system,
     * not created manually. This endpoint is for special cases only.
     *
     * @param request Create request
     * @return Created permission
     */
    @PostMapping
    public ApiResponse<PermissionDTO> create(@Valid @RequestBody PermissionCreateRequest request) {
        log.info("Creating permission: code={}", request.getCode());
        
        PermissionDTO permission = permissionService.create(request);
        
        log.info("Permission created: id={}, code={}", permission.getId(), permission.getCode());
        
        return ApiResponse.success(permission);
    }
    
    /**
     * Update permission
     * 
     * @param id Permission ID
     * @param request Update request
     * @return Updated permission
     */
    @PutMapping("/{id}")
    public ApiResponse<PermissionDTO> update(
            @PathVariable @NotNull Long id,
            @Valid @RequestBody PermissionUpdateRequest request) {
        
        log.info("Updating permission: id={}", id);
        
        PermissionDTO permission = permissionService.update(id, request);
        pluginResourceTracker.markAsUserModified(ResourceType.PERMISSION, permission.getCode());

        log.info("Permission updated: id={}, code={}", permission.getId(), permission.getCode());

        return ApiResponse.success(permission);
    }
    
    /**
     * Delete permission (soft delete)
     * 
     * <p>Note: This is a soft delete. The permission will be marked as deleted
     * but not physically removed from the database.
     * 
     * @param id Permission ID
     * @return Success response
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable @NotNull Long id) {
        log.info("Deleting permission: id={}", id);

        PermissionDTO permission = permissionService.findById(id);
        if (permission != null) {
            pluginResourceTracker.markAsUserModified(ResourceType.PERMISSION, permission.getCode());
        }
        permissionService.delete(id);

        log.info("Permission deleted: id={}", id);

        return ApiResponse.success();
    }
    
    /**
     * Get permission by ID
     * 
     * @param id Permission ID
     * @return Permission details
     */
    @GetMapping("/{id}")
    public ApiResponse<PermissionDTO> getById(@PathVariable @NotNull Long id) {
        log.debug("Getting permission: id={}", id);
        
        PermissionDTO permission = permissionService.findById(id);
        
        return ApiResponse.success(permission);
    }
    
    /**
     * List permissions by resource type
     * 
     * @param resourceType Resource type (MODEL, PAGE, QUERY, etc.)
     * @return List of permissions
     */
    @GetMapping("/resource-type/{resourceType}")
    public ApiResponse<List<PermissionDTO>> listByResourceType(
            @PathVariable @NotNull String resourceType) {
        
        log.debug("Listing permissions by resource type: {}", resourceType);
        
        List<PermissionDTO> permissions = permissionService.findByResourceType(resourceType);
        
        return ApiResponse.success(permissions);
    }
    
    /**
     * Get user's permissions
     * 
     * <p>Returns all permissions assigned to the user through RBAC.
     * 
     * @param userId User ID
     * @return List of user's permissions
     */
    @GetMapping("/user/{userId}")
    public ApiResponse<List<PermissionDTO>> getUserPermissions(
            @PathVariable @NotNull Long userId) {
        
        log.debug("Getting user permissions: userId={}", userId);
        
        List<PermissionDTO> permissions = permissionService.findUserPermissions(userId);
        
        return ApiResponse.success(permissions);
    }
    
    /**
     * Deprecate permission
     * 
     * <p>Marks the permission as DEPRECATED with a 6-month transition period.
     * 
     * @param id Permission ID
     * @return Success response
     */
    @PostMapping("/{id}/deprecate")
    public ApiResponse<Void> deprecate(@PathVariable @NotNull Long id) {
        log.info("Deprecating permission: id={}", id);
        
        permissionService.deprecate(id);
        
        log.info("Permission deprecated: id={}", id);
        
        return ApiResponse.success();
    }
    
    /**
     * Archive permission
     * 
     * <p>Permanently archives the permission. This should only be done
     * after the 6-month deprecation period.
     * 
     * @param id Permission ID
     * @return Success response
     */
    @PostMapping("/{id}/archive")
    public ApiResponse<Void> archive(@PathVariable @NotNull Long id) {
        log.info("Archiving permission: id={}", id);
        
        permissionService.archive(id);
        
        log.info("Permission archived: id={}", id);
        
        return ApiResponse.success();
    }
    
    /**
     * Get permissions by model code
     * 
     * <p>Returns all permissions for a specific model (resource_type=MODEL, resource_code=modelCode).
     * 
     * @param modelCode Model code
     * @return List of permissions
     */
    @GetMapping("/model/{modelCode}")
    public ApiResponse<List<PermissionDTO>> getModelPermissions(
            @PathVariable @NotNull String modelCode) {
        
        log.debug("Getting model permissions: modelCode={}", modelCode);
        
        List<PermissionDTO> permissions = permissionService.findByResource("model", modelCode);
        
        return ApiResponse.success(permissions);
    }
    
    /**
     * Get permissions by role
     * 
     * <p>Returns all permissions bound to a specific role.
     * 
     * @param roleId Role ID
     * @return List of permissions
     */
    @GetMapping("/role/{roleId}")
    public ApiResponse<List<PermissionDTO>> getRolePermissions(
            @PathVariable @NotNull Long roleId) {
        
        log.debug("Getting role permissions: roleId={}", roleId);
        
        List<PermissionDTO> permissions = permissionService.findRolePermissions(roleId);
        
        return ApiResponse.success(permissions);
    }
    
    /**
     * Bind permission to role
     * 
     * <p>Creates a GRANT binding between a role and a permission.
     * 
     * @param roleId Role ID
     * @param request Bind request containing permissionId
     * @return Success response
     */
    @PostMapping("/role/{roleId}/bind")
    public ApiResponse<Void> bindPermissionToRole(
            @PathVariable @NotNull Long roleId,
            @Valid @RequestBody PermissionBindRequest request) {
        
        log.info("Binding permission to role: roleId={}, permissionId={}",
            roleId, request.getPermissionId());
        
        permissionService.bindToRole(roleId, request.getPermissionId());
        
        log.info("Permission bound to role: roleId={}, permissionId={}",
            roleId, request.getPermissionId());
        
        return ApiResponse.success();
    }
    
    /**
     * Unbind permission from role
     * 
     * <p>Removes the binding between a role and a permission.
     * 
     * @param roleId Role ID
     * @param request Unbind request containing permissionId
     * @return Success response
     */
    @PostMapping("/role/{roleId}/unbind")
    public ApiResponse<Void> unbindPermissionFromRole(
            @PathVariable @NotNull Long roleId,
            @Valid @RequestBody PermissionBindRequest request) {
        
        log.info("Unbinding permission from role: roleId={}, permissionId={}",
            roleId, request.getPermissionId());
        
        permissionService.unbindFromRole(roleId, request.getPermissionId());
        
        log.info("Permission unbound from role: roleId={}, permissionId={}",
            roleId, request.getPermissionId());
        
        return ApiResponse.success();
    }
    
    /**
     * Get permission references
     * 
     * <p>Returns all roles that reference this permission.
     * 
     * @param permissionId Permission ID
     * @return List of role permission bindings
     */
    @GetMapping("/{permissionId}/references")
    public ApiResponse<List<PermissionReferenceDTO>> getPermissionReferences(
            @PathVariable @NotNull Long permissionId) {
        
        log.debug("Getting permission references: permissionId={}", permissionId);
        
        List<PermissionReferenceDTO> references = permissionService.findReferences(permissionId);
        
        return ApiResponse.success(references);
    }
}
