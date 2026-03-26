package com.auraboot.framework.meta.controller;

import com.auraboot.framework.application.annotation.CurrentUserId;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.entity.UserProjectBinding;
import com.auraboot.framework.meta.service.UserProjectBindingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for user-project binding (project membership).
 */
@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class UserProjectBindingController {

    private final UserProjectBindingService bindingService;

    /**
     * Add a member to a project.
     * Body: { "userId": 123, "bindingRole": "member" }
     */
    @PostMapping("/{projectPid}/members")
    public ApiResponse<Void> addMember(
            @PathVariable String projectPid,
            @RequestBody Map<String, Object> body,
            @CurrentUserId Long operatorId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = ((Number) body.get("userId")).longValue();
        String bindingRole = (String) body.getOrDefault("bindingRole", "member");
        bindingService.addMember(tenantId, userId, projectPid, bindingRole, operatorId);
        return ApiResponse.success(null);
    }

    /**
     * Remove a member from a project.
     */
    @DeleteMapping("/{projectPid}/members/{userId}")
    public ApiResponse<Void> removeMember(
            @PathVariable String projectPid,
            @PathVariable Long userId,
            @CurrentUserId Long operatorId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        bindingService.removeMember(tenantId, userId, projectPid);
        return ApiResponse.success(null);
    }

    /**
     * Get all members of a project.
     */
    @GetMapping("/{projectPid}/members")
    public ApiResponse<List<UserProjectBinding>> getProjectMembers(
            @PathVariable String projectPid,
            @CurrentUserId Long userId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<UserProjectBinding> members = bindingService.getProjectMembers(tenantId, projectPid);
        return ApiResponse.success(members);
    }

    /**
     * Get all projects the current user is bound to.
     */
    @GetMapping("/me/projects")
    public ApiResponse<List<UserProjectBinding>> getMyProjects(@CurrentUserId Long userId) {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<UserProjectBinding> projects = bindingService.getUserProjects(tenantId, userId);
        return ApiResponse.success(projects);
    }
}
