package com.auraboot.framework.integration;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Explicit real-DB grants for one fixture actor; no administrator role or production registration. */
public final class NativePermissionFixture implements AutoCloseable {
    private final JdbcTemplate jdbc;
    private final UserPermissionService permissions;
    private final Long tenant;
    private final Long user;
    private final Long role = IdWorker.getId();
    private final String rolePid = UniqueIdGenerator.generate();
    private final Long assignment = IdWorker.getId();
    private final String assignmentPid = UniqueIdGenerator.generate();
    private final Map<Long, String> createdDefinitions = new LinkedHashMap<>();
    private final Map<String, Long> grants = new LinkedHashMap<>();
    private boolean closed;

    public NativePermissionFixture(JdbcTemplate jdbc, UserPermissionService permissions) {
        this.jdbc = jdbc;
        this.permissions = permissions;
        tenant = MetaContext.getCurrentTenantId();
        user = MetaContext.getCurrentUserId();
        Long member = MetaContext.getCurrentMemberId();
        if (tenant == null || user == null || member == null || MetaContext.getCurrentUserPid() == null
                || jdbc.queryForList("SELECT id FROM ab_tenant_member WHERE id=? AND tenant_id=? AND user_id=? "
                        + "AND status='active' AND deleted_flag=FALSE", Long.class, member, tenant, user).size() != 1)
            throw new IllegalStateException("Fixture actor must have its actual active tenant membership");
        jdbc.update("INSERT INTO ab_role (id,pid,tenant_id,code,name,scope_type,status,deleted_flag) "
                        + "VALUES (?,?,?,?,?,'tenant','active',FALSE)",
                role, rolePid, tenant, "fixture_" + rolePid.toLowerCase(), "Owned native fixture grants");
        try {
            jdbc.update("INSERT INTO ab_user_role (id,pid,tenant_id,member_id,role_id,status,deleted_flag) "
                            + "VALUES (?,?,?,?,?,'active',FALSE)", assignment, assignmentPid, tenant, member, role);
            refresh();
        } catch (RuntimeException failure) {
            try { close(); } catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    public void grantModel(String model, String... actions) {
        for (String action : actions) grant("model." + model + "." + action, "model", model, action);
    }

    public void grant(String code, String type, String resource, String action) {
        if (closed) throw new IllegalStateException("Fixture grant owner is closed");
        if (grants.containsKey(code)) return;
        var existing = jdbc.queryForList("SELECT id,resource_type,resource_code,action,status FROM ab_permission "
                + "WHERE tenant_id=? AND code=? AND deleted_flag=FALSE", tenant, code);
        Long permission;
        if (existing.isEmpty()) {
            permission = IdWorker.getId();
            String pid = UniqueIdGenerator.generate();
            jdbc.update("INSERT INTO ab_permission (id,pid,tenant_id,code,name,resource_type,resource_code,action,source,status,deleted_flag,created_at,updated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,'test','active',FALSE,NOW(),NOW())",
                    permission, pid, tenant, code, "Owned fixture " + code, type, resource, action);
            createdDefinitions.put(permission, pid);
        } else {
            if (existing.size() != 1 || !Objects.equals(existing.getFirst().get("resource_type"), type)
                    || !Objects.equals(existing.getFirst().get("status"), "active")
                    || !Objects.equals(existing.getFirst().get("resource_code"), resource)
                    || !Objects.equals(existing.getFirst().get("action"), action))
                throw new IllegalStateException("Existing fixture permission has a different resource boundary: " + code);
            permission = ((Number) existing.getFirst().get("id")).longValue();
        }
        jdbc.update("INSERT INTO ab_role_permission (id,pid,tenant_id,role_id,permission_id,grant_type,status,deleted_flag,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,'grant','active',FALSE,NOW(),NOW())",
                IdWorker.getId(), UniqueIdGenerator.generate(), tenant, role, permission);
        grants.put(code, permission);
        refresh();
    }

    private void refresh() {
        permissions.evictPermissionDefinitions(tenant);
        permissions.evictRoleUsers(tenant, role);
        permissions.evictUserPermissions(tenant, user);
    }

    @Override public void close() {
        if (closed) return;
        jdbc.update("DELETE FROM ab_role_permission WHERE tenant_id=? AND role_id=?", tenant, role);
        jdbc.update("DELETE FROM ab_user_role WHERE tenant_id=? AND id=? AND pid=?", tenant, assignment, assignmentPid);
        jdbc.update("DELETE FROM ab_role WHERE tenant_id=? AND id=? AND pid=?", tenant, role, rolePid);
        for (var definition : createdDefinitions.entrySet()) {
            jdbc.update("DELETE FROM ab_permission p WHERE p.tenant_id=? AND p.id=? AND p.pid=? "
                            + "AND NOT EXISTS (SELECT 1 FROM ab_role_permission r WHERE r.permission_id=p.id)",
                    tenant, definition.getKey(), definition.getValue());
        }
        closed = true;
        refresh();
    }
}
