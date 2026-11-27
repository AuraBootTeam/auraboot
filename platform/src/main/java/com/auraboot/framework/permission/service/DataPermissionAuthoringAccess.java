package com.auraboot.framework.permission.service;

import com.auraboot.framework.application.security.AdminRoleChecker;
import com.auraboot.framework.application.security.MachineDataAccessContext;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.openplatform.service.InstalledIntegrationExecution;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.enums.RoleCodes;
import com.auraboot.framework.permission.service.impl.PermissionSnapshotCache;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;

/** Administrative policy boundary independent of the calling transport. */
@Component
@RequiredArgsConstructor
public class DataPermissionAuthoringAccess {
    private final AdminRoleChecker administrators;
    private final UserPermissionService permissions;
    private final PermissionSnapshotCache snapshots;
    private final JdbcTemplate jdbc;

    public void requireRead() { require(false); }
    public void requireWrite() { require(true); }

    private void require(boolean writing) {
        if (MachineDataAccessContext.isActive() || InstalledIntegrationExecution.active()
                || !MetaContext.exists() || MetaContext.getCurrentTenantId()==null
                || MetaContext.getCurrentUserId()==null || MetaContext.getCurrentMemberId()==null) throw denied();
        Long tenant=MetaContext.getCurrentTenantId(),user=MetaContext.getCurrentUserId(),member=MetaContext.getCurrentMemberId();
        String query="""
                SELECT m.id FROM ab_tenant_member m JOIN ab_user u ON u.id=m.user_id
                JOIN ab_tenant t ON t.id=m.tenant_id
                WHERE m.id=? AND m.tenant_id=? AND m.user_id=? AND m.status='active' AND m.deleted_flag=FALSE
                  AND u.is_enabled=TRUE AND u.deleted_flag=FALSE AND t.status='active' AND t.deleted_flag=FALSE
                """;
        if(writing) query+=" FOR SHARE OF m,u";
        if(jdbc.queryForList(query,Long.class,member,tenant,user).size()!=1
                || !administrators.hasRole(tenant,user,RoleCodes.TENANT_ADMIN)
                || !snapshots.withFreshReads(()->permissions.hasPermission(user,MetaPermission.PERMISSION_MANAGE))) throw denied();
    }

    public void requireTenant(Long tenant) {
        if(!Objects.equals(tenant,MetaContext.getCurrentTenantId())) throw denied();
    }

    /** Resolve a user ID to a live local membership; never use a user ID as a member ID. */
    public MetaContext.Snapshot previewSubject(Long user) {
        var subjects=jdbc.query("""
                SELECT m.id,u.pid,u.user_name FROM ab_tenant_member m JOIN ab_user u ON u.id=m.user_id
                WHERE m.tenant_id=? AND m.user_id=? AND m.status='active' AND m.deleted_flag=FALSE
                  AND u.is_enabled=TRUE AND u.deleted_flag=FALSE
                """,(rs,index)->new MetaContext.Snapshot(MetaContext.getCurrentTenantId(),user,
                rs.getString("pid"),rs.getString("user_name"),Set.of(),rs.getLong("id"),
                MetaContext.snapshot().envId(),MetaContext.snapshot().otelTraceId(),null),MetaContext.getCurrentTenantId(),user);
        if(subjects.size()!=1) throw denied();
        return subjects.getFirst();
    }

    public void requireRole(String rolePid) {
        if(rolePid==null || jdbc.queryForList("SELECT id FROM ab_role WHERE tenant_id=? AND pid=? AND deleted_flag=FALSE",
                Long.class,MetaContext.getCurrentTenantId(),rolePid).size()!=1) throw denied();
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("Current tenant administrator and permission management authority required");
    }
}
