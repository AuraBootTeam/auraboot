package com.auraboot.framework.integration;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.saas.executor.SystemTenantContextExecutor;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;

/** Owns an isolated System-tenant platform role and assignment, never an existing admin role. */
public final class PlatformAdministrationFixture implements AutoCloseable {
    private final JdbcTemplate jdbc;
    private final long tenant = SystemTenantContextExecutor.SYSTEM_TENANT_ID;
    private final long role = IdWorker.getId();
    private final long assignment = IdWorker.getId();
    private Long ownedMember;
    private boolean ownedTenant;
    private final boolean transactional = org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();

    private PlatformAdministrationFixture(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public static PlatformAdministrationFixture grant(JdbcTemplate jdbc, Long user) {
        var fixture = new PlatformAdministrationFixture(jdbc);
        try {
            fixture.ownedTenant = !jdbc.queryForList("""
                    INSERT INTO ab_tenant (id,pid,name,status,deleted_flag)
                    VALUES (?,?,'System','active',FALSE) ON CONFLICT (id) DO NOTHING RETURNING id
                    """, Long.class, fixture.tenant, UniqueIdGenerator.generate()).isEmpty();
            var members = jdbc.queryForList("SELECT id FROM ab_tenant_member WHERE tenant_id=? AND user_id=? AND deleted_flag=FALSE", Long.class, fixture.tenant, user);
            Long member;
            if (members.isEmpty()) {
                member = IdWorker.getId(); fixture.ownedMember = member;
                jdbc.update("INSERT INTO ab_tenant_member (id,pid,tenant_id,user_id,status,deleted_flag) VALUES (?,?,?,?,'active',FALSE)",
                        member, UniqueIdGenerator.generate(), fixture.tenant, user);
            } else { member = members.getFirst(); }
            jdbc.update("""
                    INSERT INTO ab_role (id,pid,tenant_id,code,name,scope_type,status,deleted_flag)
                    VALUES (?,?,?,'platform_admin','Owned platform test role','global','active',FALSE)
                    """, fixture.role, UniqueIdGenerator.generate(), fixture.tenant);
            jdbc.update("INSERT INTO ab_user_role (id,pid,tenant_id,member_id,role_id,status,deleted_flag) VALUES (?,?,?,?,?,'active',FALSE)",
                    fixture.assignment, UniqueIdGenerator.generate(), fixture.tenant, member, fixture.role);
            return fixture;
        } catch (RuntimeException failure) {
            fixture.close();
            throw failure;
        }
    }

    public long roleId() { return role; }
    public long assignmentId() { return assignment; }

    @Override public void close() {
        if (transactional && org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCompletion(int status) {
                            if (status == STATUS_COMMITTED) {
                                var cleanup = new org.springframework.transaction.support.TransactionTemplate(
                                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
                                cleanup.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                                cleanup.executeWithoutResult(ignored -> deleteOwnedRows());
                            }
                        }
                    });
            return;
        }
        deleteOwnedRows();
    }

    private void deleteOwnedRows() {
        jdbc.update("DELETE FROM ab_role_data_scope WHERE role_id=?", role);
        jdbc.update("DELETE FROM ab_role_permission WHERE role_id=?", role);
        jdbc.update("DELETE FROM ab_user_role WHERE id=? AND role_id=?", assignment, role);
        jdbc.update("DELETE FROM ab_role WHERE id=?", role);
        if (ownedMember != null) jdbc.update("DELETE FROM ab_tenant_member WHERE id=?", ownedMember);
        if (ownedTenant) jdbc.update("""
                DELETE FROM ab_tenant WHERE id=?
                AND NOT EXISTS (SELECT 1 FROM ab_role WHERE tenant_id=?)
                AND NOT EXISTS (SELECT 1 FROM ab_tenant_member WHERE tenant_id=?)
                """, tenant, tenant, tenant);
    }
}
