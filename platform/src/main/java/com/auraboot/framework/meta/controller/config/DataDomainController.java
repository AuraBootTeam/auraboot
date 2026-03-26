package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.entity.DataDomain;
import com.auraboot.framework.meta.entity.UserDataDomain;
import com.auraboot.framework.meta.service.DataDomainService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for data domain management.
 *
 * <p>Provides CRUD operations for data domains and user-domain bindings.
 * Data domains enable business unit / subsidiary / factory data isolation
 * within a single tenant.
 *
 * @since 5.2.0
 */
@RestController
@RequestMapping("/api/data-domains")
@RequiredArgsConstructor
@RequirePermission(MetaPermission.META_PERMISSION_MANAGE)
public class DataDomainController {

    private final DataDomainService dataDomainService;

    // ==================== Domain CRUD ====================

    /**
     * List all data domains.
     * GET /api/data-domains
     */
    @GetMapping
    public ApiResponse<List<DataDomain>> list() {
        return ApiResponse.success(dataDomainService.listDomains());
    }

    /**
     * Get a data domain by ID.
     * GET /api/data-domains/{id}
     */
    @GetMapping("/{id}")
    public ApiResponse<DataDomain> get(@PathVariable Long id) {
        return ApiResponse.success(dataDomainService.getDomain(id));
    }

    /**
     * Create a new data domain.
     * POST /api/data-domains
     */
    @PostMapping
    public ApiResponse<DataDomain> create(@RequestBody DataDomain domain) {
        return ApiResponse.success(dataDomainService.createDomain(domain));
    }

    /**
     * Update a data domain.
     * PUT /api/data-domains/{id}
     */
    @PutMapping("/{id}")
    public ApiResponse<DataDomain> update(@PathVariable Long id, @RequestBody DataDomain domain) {
        return ApiResponse.success(dataDomainService.updateDomain(id, domain));
    }

    /**
     * Delete a data domain (soft delete).
     * DELETE /api/data-domains/{id}
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id) {
        dataDomainService.deleteDomain(id);
        return ApiResponse.success(Map.of("success", true, "id", id));
    }

    /**
     * Get child domains of a parent domain.
     * GET /api/data-domains/{id}/children
     */
    @GetMapping("/{id}/children")
    public ApiResponse<List<DataDomain>> children(@PathVariable Long id) {
        return ApiResponse.success(dataDomainService.getChildren(id));
    }

    // ==================== User-Domain Bindings ====================

    /**
     * Assign a user to a domain.
     * POST /api/data-domains/{id}/users
     * Body: { "userId": 123, "isPrimary": true }
     */
    @PostMapping("/{id}/users")
    public ApiResponse<UserDataDomain> assignUser(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body) {
        Long userId = ((Number) body.get("userId")).longValue();
        boolean isPrimary = Boolean.TRUE.equals(body.get("isPrimary"));
        return ApiResponse.success(dataDomainService.assignUser(id, userId, isPrimary));
    }

    /**
     * Remove a user from a domain.
     * DELETE /api/data-domains/{id}/users/{userId}
     */
    @DeleteMapping("/{id}/users/{userId}")
    public ApiResponse<Map<String, Object>> removeUser(
            @PathVariable Long id,
            @PathVariable Long userId) {
        dataDomainService.removeUser(id, userId);
        return ApiResponse.success(Map.of("success", true, "domainId", id, "userId", userId));
    }

    /**
     * Get all domains assigned to a user.
     * GET /api/data-domains/user/{userId}
     */
    @GetMapping("/user/{userId}")
    public ApiResponse<List<DataDomain>> getUserDomains(@PathVariable Long userId) {
        return ApiResponse.success(dataDomainService.getUserDomains(userId));
    }

    /**
     * Get all user IDs assigned to a domain.
     * GET /api/data-domains/{id}/user-ids
     */
    @GetMapping("/{id}/user-ids")
    public ApiResponse<List<Long>> getDomainUserIds(@PathVariable Long id) {
        return ApiResponse.success(dataDomainService.getDomainUserIds(id));
    }

    /**
     * Evict data domain caches.
     * POST /api/data-domains/evict-cache
     */
    @PostMapping("/evict-cache")
    public ApiResponse<Map<String, Object>> evictCache() {
        dataDomainService.evictCache();
        return ApiResponse.success(Map.of("success", true));
    }
}
