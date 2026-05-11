package com.auraboot.framework.agent.crosstenant;

import com.auraboot.framework.application.security.AdminRoleChecker;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.util.PaginationSafetyUtils;
import com.auraboot.framework.permission.enums.RoleCodes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin REST surface for cross-tenant sub-agent grants.
 *
 * <p>Mounted under {@code /api/admin/**} so the
 * {@link com.auraboot.framework.application.security.AdminRoleInterceptor}
 * already gates inbound traffic to authenticated tenant admins. We then
 * additionally require the {@code platform_admin} role on top — per Q2 only
 * platform admins (members of {@code SYSTEM_TENANT_ID}) can grant cross-
 * tenant authority. Returning HTTP 200 with
 * {@code ApiResponse{code:403, message:"platform_admin required"}} matches
 * the project's uniform envelope contract.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET    /api/admin/cross-tenant-grants}      — list active grants (paged, default activeOnly=true)</li>
 *   <li>{@code POST   /api/admin/cross-tenant-grants}      — create a new grant</li>
 *   <li>{@code DELETE /api/admin/cross-tenant-grants/{id}} — revoke (sets revoked_at + revoked_by)</li>
 *   <li>{@code GET    /api/admin/cross-tenant-grants/{id}/audit} — paged audit rows for the grant</li>
 * </ul>
 *
 * <p>All write paths invalidate the {@link CrossTenantAclService} cache for
 * the affected key so a freshly-granted or freshly-revoked grant is visible
 * to in-flight call-sites without waiting for the 10s TTL.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/cross-tenant-grants")
@RequiredArgsConstructor
public class CrossTenantGrantController {

    private final JdbcTemplate jdbc;
    private final AdminRoleChecker adminRoleChecker;
    private final CrossTenantAclService crossTenantAclService;

    /**
     * Platform-admin guard. Returns null when the caller passes; otherwise
     * returns a non-null error envelope the caller must propagate verbatim.
     */
    private <T> ApiResponse<T> guardPlatformAdmin() {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        if (tenantId == null || userId == null) {
            return ApiResponse.error(403, "platform_admin required", null);
        }
        if (!adminRoleChecker.hasRole(tenantId, userId, RoleCodes.PLATFORM_ADMIN)) {
            log.warn("CrossTenantGrantController: rejected non-platform-admin (tenantId={}, userId={})",
                    tenantId, userId);
            return ApiResponse.error(403, "platform_admin required", null);
        }
        return null;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        ApiResponse<Map<String, Object>> denied = guardPlatformAdmin();
        if (denied != null) return denied;

        pageNum = PaginationSafetyUtils.pageNumber(pageNum);
        int limit = PaginationSafetyUtils.pageSize(pageSize, 200);
        int offset = PaginationSafetyUtils.offset(pageNum, limit, 200);

        String where = activeOnly
                ? "WHERE revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now())"
                : "";
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, parent_tenant_id, child_tenant_id, grant_type, granted_by, "
                        + " granted_at, expires_at, revoked_at, revoked_by, note "
                        + "FROM ab_cross_tenant_grant " + where
                        + " ORDER BY granted_at DESC LIMIT ? OFFSET ?",
                limit, offset);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_cross_tenant_grant " + where, Long.class);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("records", rows);
        body.put("total", total == null ? 0L : total);
        body.put("pageNum", pageNum);
        body.put("pageSize", limit);
        return ApiResponse.ok(body);
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@RequestBody CreateGrantRequest req) {
        ApiResponse<Map<String, Object>> denied = guardPlatformAdmin();
        if (denied != null) return denied;

        if (req.parentTenantId == null || req.childTenantId == null) {
            return ApiResponse.error(400, "parentTenantId and childTenantId are required", null);
        }
        String grantType = req.grantType == null || req.grantType.isBlank()
                ? CrossTenantGrantType.SPAWN_SUB_AGENT : req.grantType;
        Long grantedBy = MetaContext.getCurrentUserId();
        Timestamp expiresAt = req.expiresAt == null ? null : Timestamp.from(req.expiresAt);

        Long id;
        try {
            id = jdbc.queryForObject(
                    "INSERT INTO ab_cross_tenant_grant "
                            + "(parent_tenant_id, child_tenant_id, grant_type, "
                            + " granted_by, granted_at, expires_at, note) "
                            + "VALUES (?, ?, ?, ?, now(), ?, ?) RETURNING id",
                    Long.class,
                    req.parentTenantId, req.childTenantId, grantType,
                    grantedBy, expiresAt, req.note);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            // Partial unique index forbids two active grants for the same
            // (parent, child, type) tuple. Surface as 409.
            return ApiResponse.error(409,
                    "active grant already exists for this tenant pair", null);
        }
        crossTenantAclService.invalidate(req.parentTenantId, req.childTenantId, grantType);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("parentTenantId", req.parentTenantId);
        body.put("childTenantId", req.childTenantId);
        body.put("grantType", grantType);
        log.info("CrossTenantGrantController: created grant id={} ({} → {}, type={}, grantedBy={})",
                id, req.parentTenantId, req.childTenantId, grantType, grantedBy);
        return ApiResponse.ok(body);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> revoke(@PathVariable("id") Long id) {
        ApiResponse<Map<String, Object>> denied = guardPlatformAdmin();
        if (denied != null) return denied;

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT parent_tenant_id, child_tenant_id, grant_type, revoked_at "
                        + "FROM ab_cross_tenant_grant WHERE id = ?", id);
        if (rows.isEmpty()) {
            return ApiResponse.error(404, "grant not found", null);
        }
        Map<String, Object> row = rows.get(0);
        if (row.get("revoked_at") != null) {
            return ApiResponse.error(409, "grant already revoked", null);
        }
        Long revokedBy = MetaContext.getCurrentUserId();
        int updated = jdbc.update(
                "UPDATE ab_cross_tenant_grant "
                        + "SET revoked_at = now(), revoked_by = ? "
                        + "WHERE id = ? AND revoked_at IS NULL",
                revokedBy, id);
        if (updated == 0) {
            return ApiResponse.error(409, "grant already revoked", null);
        }
        Long parent = ((Number) row.get("parent_tenant_id")).longValue();
        Long child = ((Number) row.get("child_tenant_id")).longValue();
        String grantType = (String) row.get("grant_type");
        crossTenantAclService.invalidate(parent, child, grantType);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("revokedBy", revokedBy);
        log.info("CrossTenantGrantController: revoked grant id={} (revokedBy={})", id, revokedBy);
        return ApiResponse.ok(body);
    }

    @GetMapping("/{id}/audit")
    public ApiResponse<Map<String, Object>> audit(
            @PathVariable("id") Long id,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "50") int pageSize) {
        ApiResponse<Map<String, Object>> denied = guardPlatformAdmin();
        if (denied != null) return denied;

        // Audit rows for this grant id PLUS denied rows in the same tenant
        // pair (denied rows have grant_id = NULL but should still appear in
        // the drilldown of the corresponding grant). Read the grant's tenant
        // pair first, then filter audit by either grant_id=? OR (grant_id IS NULL
        // AND parent_tenant_id=? AND child_tenant_id=?).
        List<Map<String, Object>> grantRows = jdbc.queryForList(
                "SELECT parent_tenant_id, child_tenant_id "
                        + "FROM ab_cross_tenant_grant WHERE id = ?", id);
        if (grantRows.isEmpty()) {
            return ApiResponse.error(404, "grant not found", null);
        }
        Long parent = ((Number) grantRows.get(0).get("parent_tenant_id")).longValue();
        Long child = ((Number) grantRows.get(0).get("child_tenant_id")).longValue();

        pageNum = PaginationSafetyUtils.pageNumber(pageNum);
        int limit = PaginationSafetyUtils.pageSize(pageSize, 500);
        int offset = PaginationSafetyUtils.offset(pageNum, limit, 500);

        List<Map<String, Object>> auditRows = jdbc.queryForList(
                "SELECT id, grant_id, parent_tenant_id, child_tenant_id, "
                        + " parent_run_pid, child_run_pid, decision, spawn_at, error_message "
                        + "FROM ab_cross_tenant_spawn_audit "
                        + "WHERE (grant_id = ?) "
                        + "   OR (grant_id IS NULL AND parent_tenant_id = ? AND child_tenant_id = ?) "
                        + "ORDER BY spawn_at DESC LIMIT ? OFFSET ?",
                id, parent, child, limit, offset);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_cross_tenant_spawn_audit "
                        + "WHERE (grant_id = ?) "
                        + "   OR (grant_id IS NULL AND parent_tenant_id = ? AND child_tenant_id = ?)",
                Long.class, id, parent, child);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("records", auditRows);
        body.put("total", total == null ? 0L : total);
        body.put("pageNum", pageNum);
        body.put("pageSize", limit);
        return ApiResponse.ok(body);
    }

    /** Request DTO for {@link #create(CreateGrantRequest)}. */
    public static class CreateGrantRequest {
        public Long parentTenantId;
        public Long childTenantId;
        public String grantType;
        public Instant expiresAt;
        public String note;
    }
}
