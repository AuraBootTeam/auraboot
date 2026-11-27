package com.auraboot.framework.integration;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;

/** Owns only the administrator role/assignment rows created for one integration test. */
public final class TenantAdministrationFixture implements AutoCloseable {
    private final JdbcTemplate jdbc;
    private final Long tenant;
    private Long createdRole;
    private Long createdAssignment;

    private TenantAdministrationFixture(JdbcTemplate jdbc, Long tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    public static TenantAdministrationFixture grant(JdbcTemplate jdbc, Long tenant, Long member) {
        var fixture = new TenantAdministrationFixture(jdbc, tenant);
        var roles = jdbc.queryForList("SELECT id FROM ab_role WHERE tenant_id=? AND code='tenant_admin' AND deleted_flag=FALSE", Long.class, tenant);
        Long role;
        if (roles.isEmpty()) {
            role = IdWorker.getId();
            jdbc.update("""
                    INSERT INTO ab_role (id,pid,tenant_id,code,name,status,deleted_flag)
                    VALUES (?,?,?,'tenant_admin','Test tenant administration','active',FALSE)
                    """, role, UniqueIdGenerator.generate(), tenant);
            fixture.createdRole = role;
        } else {
            role = roles.getFirst();
        }
        var assignments = jdbc.queryForList("SELECT id FROM ab_user_role WHERE tenant_id=? AND member_id=? AND role_id=? AND deleted_flag=FALSE", Long.class, tenant, member, role);
        if (assignments.isEmpty()) {
            fixture.createdAssignment = IdWorker.getId();
            jdbc.update("""
                    INSERT INTO ab_user_role (id,pid,tenant_id,member_id,role_id,status,deleted_flag)
                    VALUES (?,?,?,?,?,'active',FALSE)
                    """, fixture.createdAssignment, UniqueIdGenerator.generate(), tenant, member, role);
        }
        return fixture;
    }

    @Override public void close() {
        if (createdAssignment != null) {
            jdbc.update("DELETE FROM ab_user_role WHERE tenant_id=? AND id=?", tenant, createdAssignment);
            createdAssignment = null;
        }
        if (createdRole != null) {
            jdbc.update("DELETE FROM ab_role WHERE tenant_id=? AND id=?", tenant, createdRole);
            createdRole = null;
        }
    }
}
