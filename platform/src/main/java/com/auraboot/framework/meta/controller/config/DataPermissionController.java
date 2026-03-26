package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.DataPermissionPolicyCreateRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.meta.entity.DataPermissionPolicy;
import com.auraboot.framework.meta.entity.DataPermissionRoleBinding;
import com.auraboot.framework.meta.service.DataPermissionPolicyService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for data permission policy management.
 * Provides CRUD operations for row-level security (RLS) and column-level masking policies.
 *
 * <p>Supported scope types for ROW policies:
 * ALL, SELF, DEPARTMENT, DEPARTMENT_TREE, PROJECT, CUSTOM.
 *
 * <p>Multiple ROW policies for the same user/model are combined with OR logic
 * (most permissive union). No policies = allow all (default permissive).
 *
 * @since 5.1.0
 */
@RestController
@RequestMapping("/api/meta/data-permissions")
@RequiredArgsConstructor
@RequirePermission(MetaPermission.META_PERMISSION_MANAGE)
public class DataPermissionController {

    private final DataPermissionPolicyService policyService;
    private final RoleMapper roleMapper;

    // ==================== Policy CRUD ====================

    /**
     * Create a new data permission policy.
     * POST /api/meta/data-permissions
     */
    @PostMapping
    public ApiResponse<DataPermissionPolicy> create(
            @Valid @RequestBody DataPermissionPolicyCreateRequest request) {
        DataPermissionPolicy policy = policyService.create(request);
        return ApiResponse.success(policy);
    }

    /**
     * Get a policy by pid.
     * GET /api/meta/data-permissions/{pid}
     */
    @GetMapping("/{pid}")
    public ApiResponse<DataPermissionPolicy> getByPid(@PathVariable String pid) {
        DataPermissionPolicy policy = policyService.getByPid(pid);
        if (policy == null) {
            return ApiResponse.error("Policy not found: " + pid);
        }
        return ApiResponse.success(policy);
    }

    /**
     * List policies. Optionally filter by modelCode.
     * GET /api/meta/data-permissions
     * GET /api/meta/data-permissions?modelCode=xxx
     */
    @GetMapping
    public ApiResponse<List<DataPermissionPolicy>> list(
            @RequestParam(required = false) String modelCode) {
        if (modelCode != null && !modelCode.isBlank()) {
            return ApiResponse.success(policyService.listByModelCode(modelCode));
        }
        return ApiResponse.success(policyService.listAll());
    }

    /**
     * Update a policy.
     * PUT /api/meta/data-permissions/{pid}
     */
    @PutMapping("/{pid}")
    public ApiResponse<DataPermissionPolicy> update(
            @PathVariable String pid,
            @Valid @RequestBody DataPermissionPolicyCreateRequest request) {
        DataPermissionPolicy policy = policyService.update(pid, request);
        return ApiResponse.success(policy);
    }

    /**
     * Delete a policy and its role bindings.
     * DELETE /api/meta/data-permissions/{pid}
     */
    @DeleteMapping("/{pid}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable String pid) {
        policyService.delete(pid);
        return ApiResponse.success(Map.of("success", true, "pid", pid));
    }

    // ==================== Enable / Disable ====================

    /**
     * Enable a policy.
     * PUT /api/meta/data-permissions/{pid}/enable
     */
    @PutMapping("/{pid}/enable")
    public ApiResponse<Map<String, Object>> enable(@PathVariable String pid) {
        policyService.enable(pid);
        return ApiResponse.success(Map.of("success", true, "pid", pid));
    }

    /**
     * Disable a policy.
     * PUT /api/meta/data-permissions/{pid}/disable
     */
    @PutMapping("/{pid}/disable")
    public ApiResponse<Map<String, Object>> disable(@PathVariable String pid) {
        policyService.disable(pid);
        return ApiResponse.success(Map.of("success", true, "pid", pid));
    }

    // ==================== Role Bindings ====================

    /**
     * List all role bindings for a policy.
     * GET /api/meta/data-permissions/{pid}/roles
     */
    @GetMapping("/{pid}/roles")
    public ApiResponse<List<DataPermissionRoleBinding>> listRoleBindings(@PathVariable String pid) {
        List<DataPermissionRoleBinding> bindings = policyService.listRoleBindings(pid);
        return ApiResponse.success(bindings);
    }

    /**
     * List role bindings in PaginationResult format for DSL sub-table compatibility.
     */
    @GetMapping("/{pid}/roles/list")
    public ApiResponse<PaginationResult<Map<String, Object>>> listRoleBindingsPaged(@PathVariable String pid) {
        List<DataPermissionRoleBinding> bindings = policyService.listRoleBindings(pid);
        List<Map<String, Object>> enriched = bindings.stream().map(b -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rolePid", b.getRolePid());
            Role role = roleMapper.findByPid(b.getRolePid());
            row.put("roleName", role != null ? role.getName() : b.getRolePid());
            row.put("roleCode", role != null ? role.getCode() : "");
            return row;
        }).toList();
        return ApiResponse.success(PaginationResult.of(enriched, (long) enriched.size(), 1, Math.max(enriched.size(), 1)));
    }

    /**
     * Bind a policy to a role.
     * POST /api/meta/data-permissions/{pid}/roles/{rolePid}
     */
    @PostMapping("/{pid}/roles/{rolePid}")
    public ApiResponse<Map<String, Object>> bindToRole(
            @PathVariable String pid, @PathVariable String rolePid) {
        policyService.bindToRole(pid, rolePid);
        return ApiResponse.success(Map.of("success", true, "policyPid", pid, "rolePid", rolePid));
    }

    /**
     * Unbind a policy from a role.
     * DELETE /api/meta/data-permissions/{pid}/roles/{rolePid}
     */
    @DeleteMapping("/{pid}/roles/{rolePid}")
    public ApiResponse<Map<String, Object>> unbindFromRole(
            @PathVariable String pid, @PathVariable String rolePid) {
        policyService.unbindFromRole(pid, rolePid);
        return ApiResponse.success(Map.of("success", true, "policyPid", pid, "rolePid", rolePid));
    }

    // ==================== Preview / Test ====================

    /**
     * Preview the SQL filter that would be generated for a given model/user.
     * Useful for testing and debugging data permission rules.
     *
     * GET /api/meta/data-permissions/preview?modelCode=xxx&userId=123
     */
    @GetMapping("/preview")
    public ApiResponse<Map<String, Object>> previewFilter(
            @RequestParam String modelCode,
            @RequestParam(required = false) Long userId) {
        if (userId == null) {
            userId = MetaContext.getCurrentUserId();
        }

        String rowFilter = policyService.previewRowFilter(modelCode, userId);
        List<DataPermissionPolicy> effectivePolicies = policyService.getEffectivePolicies(
                MetaContext.getCurrentTenantId(), modelCode, userId);

        return ApiResponse.success(Map.of(
                "modelCode", modelCode,
                "userId", userId,
                "rowFilter", rowFilter != null ? rowFilter : "",
                "effectivePolicies", effectivePolicies,
                "policyCount", effectivePolicies.size()
        ));
    }
}
