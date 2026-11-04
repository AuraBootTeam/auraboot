package com.auraboot.framework.permission;

import com.auraboot.framework.application.security.AdminRoleChecker;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.integration.PlatformAdministrationFixture;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.RoleService;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/** Real database legacy-corruption denials and current reserved-role authoring invariants. */
@Transactional(propagation = Propagation.NEVER)
class ReservedPlatformRoleIT extends BaseIntegrationTest {
    @Autowired RoleService roles;
    @Autowired AdminRoleChecker administrators;
    @Autowired JdbcTemplate jdbc;
    private final List<Long> ownedRoles = new ArrayList<>();
    private Long user, member, copyPermission;
    private PlatformAdministrationFixture platform;

    @BeforeEach void actor() {
        user = IdWorker.getId(); member = IdWorker.getId(); String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_user (id,pid,is_enabled,deleted_flag) VALUES (?,?,TRUE,FALSE)", user, pid);
        jdbc.update("INSERT INTO ab_tenant_member (id,pid,tenant_id,user_id,status,deleted_flag) VALUES (?,?,?,?,'active',FALSE)",
                member, UniqueIdGenerator.generate(), testTenant.getId(), user);
        MetaContext.setContext(testTenant.getId(), user, pid, "reserved-role-test"); MetaContext.setMemberId(member);
    }
    @AfterEach void cleanup() {
        for (Long role : ownedRoles) {
            jdbc.update("DELETE FROM ab_role_data_scope WHERE role_id=?", role);
            jdbc.update("DELETE FROM ab_role_permission WHERE role_id=?", role);
            jdbc.update("DELETE FROM ab_user_role WHERE role_id=?", role);
            jdbc.update("DELETE FROM ab_role WHERE id=?", role);
        }
        if (platform != null) platform.close();
        jdbc.update("DELETE FROM ab_tenant_member WHERE id=?", member);
        jdbc.update("DELETE FROM ab_user WHERE id=?", user);
        if (copyPermission != null) jdbc.update("DELETE FROM ab_permission WHERE id=?", copyPermission);
        applyTestMetaContext();
    }
    private Role ordinary() {
        Role role = new Role(); role.setTenantId(testTenant.getId()); role.setCode("reserved_" + UniqueIdGenerator.generate()); role.setName("Reserved role fixture");
        role = roles.createRole(role); ownedRoles.add(role.getId()); return role;
    }
    @Test void businessTenantCannotCreateRenameMoveOrCopyIntoPlatformAdministration() {
        Role input = new Role(); input.setTenantId(testTenant.getId()); input.setCode("platform_admin"); input.setScopeType("global"); input.setName("Forbidden");
        assertThatThrownBy(() -> roles.createRole(input)).isInstanceOf(AccessDeniedException.class);
        Role original = ordinary();
        Role rename = new Role(); rename.setId(original.getId()); rename.setCode("platform_admin"); rename.setScopeType("global");
        assertThatThrownBy(() -> roles.updateRole(rename)).isInstanceOf(AccessDeniedException.class);
        Role move = new Role(); move.setId(original.getId()); move.setTenantId(1L);
        assertThatThrownBy(() -> roles.updateRole(move)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> roles.copyRole(original.getId(), "Forbidden copy", "platform_admin")).isInstanceOf(AccessDeniedException.class);
        var stored = jdbc.queryForMap("SELECT code,tenant_id FROM ab_role WHERE id=?", original.getId());
        assertThat(stored.get("code")).isEqualTo(original.getCode());
        assertThat(stored.get("tenant_id")).isEqualTo(testTenant.getId());
        assertThat(administrators.hasRole(testTenant.getId(), user, "platform_admin")).isFalse();
    }
    @Test void anExistingBusinessTenantGlobalRoleCannotGrantPlatformQualification() {
        Role legacy = ordinary();
        jdbc.update("UPDATE ab_role SET code='platform_admin',scope_type='global' WHERE id=?", legacy.getId());
        jdbc.update("INSERT INTO ab_user_role (id,pid,tenant_id,member_id,role_id,status,deleted_flag) VALUES (?,?,?,?,?,'active',FALSE)",
                IdWorker.getId(), UniqueIdGenerator.generate(), testTenant.getId(), member, legacy.getId());
        assertThat(administrators.hasRole(testTenant.getId(), user, "platform_admin")).isFalse();
        assertThat(administrators.hasRole(1L, user, "platform_admin")).isFalse();
    }
    @Test void legitimateSystemTenantGlobalRoleWorksAcrossCurrentBusinessTenantsAndRevokesImmediately() {
        platform = PlatformAdministrationFixture.grant(jdbc, user);
        assertThat(administrators.hasRole(testTenant.getId(), user, "platform_admin")).isTrue();
        jdbc.update("UPDATE ab_user_role SET status='inactive' WHERE id=?", platform.assignmentId());
        assertThat(administrators.hasRole(testTenant.getId(), user, "platform_admin")).isFalse();
        jdbc.update("UPDATE ab_user_role SET status='active' WHERE id=?", platform.assignmentId());
        assertThat(administrators.hasRole(testTenant.getId(), user, "platform_admin")).isTrue();
    }
    @ParameterizedTest
    @ValueSource(strings={"role-tenant", "role-global-template", "scope", "assignment-tenant", "future", "expired"})
    void malformedOrIneffectiveSystemGrantsCannotRetainPlatformQualification(String state) {
        platform = PlatformAdministrationFixture.grant(jdbc, user);
        assertThat(administrators.hasRole(testTenant.getId(), user, "platform_admin")).isTrue();
        switch (state) {
            case "role-tenant" -> jdbc.update("UPDATE ab_role SET tenant_id=? WHERE id=?", testTenant.getId(), platform.roleId());
            case "role-global-template" -> jdbc.update("UPDATE ab_role SET tenant_id=NULL WHERE id=?", platform.roleId());
            case "scope" -> jdbc.update("UPDATE ab_role SET scope_type='tenant' WHERE id=?", platform.roleId());
            case "assignment-tenant" -> jdbc.update("UPDATE ab_user_role SET tenant_id=? WHERE id=?", testTenant.getId(), platform.assignmentId());
            case "future" -> jdbc.update("UPDATE ab_user_role SET effective_date=CURRENT_DATE+1 WHERE id=?", platform.assignmentId());
            case "expired" -> jdbc.update("UPDATE ab_user_role SET expiry_date=CURRENT_DATE-1 WHERE id=?", platform.assignmentId());
            default -> throw new IllegalArgumentException(state);
        }
        assertThat(administrators.hasRole(testTenant.getId(), user, "platform_admin")).as(state).isFalse();
    }
    @Test void permissionCopyCannotTargetTheSystemTenantByNumericIdentity() {
        Role source = ordinary(); platform = PlatformAdministrationFixture.grant(jdbc, user);
        copyPermission = jdbc.queryForObject("""
                INSERT INTO ab_permission (pid,tenant_id,code,name,resource_type,status,deleted_flag)
                VALUES (?,?,?,'Copy isolation fixture','API','active',FALSE) RETURNING id
                """, Long.class, UniqueIdGenerator.generate(), testTenant.getId(), "reserved_copy_" + user);
        var grants = applicationContext.getBean(com.auraboot.framework.permission.service.RolePermissionService.class);
        assertThat(grants.assignPermissionsToRole(source.getId(), List.of(copyPermission))).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_role_permission WHERE role_id=? AND permission_id=?", Long.class, source.getId(), copyPermission)).isEqualTo(1L);
        Long before = jdbc.queryForObject("SELECT count(*) FROM ab_role_permission WHERE role_id=?", Long.class, platform.roleId());
        assertThatThrownBy(() -> applicationContext.getBean(com.auraboot.framework.permission.service.RolePermissionService.class)
                .copyRolePermissions(source.getId(), platform.roleId())).isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_role_permission WHERE role_id=?", Long.class, platform.roleId())).isEqualTo(before);
    }

    @Test void legacyFlagsCannotPermitDisablingOrDeletingTheSystemPlatformRole() {
        platform = PlatformAdministrationFixture.grant(jdbc, user);
        MetaContext.setCurrentTenantId(1L);
        assertThatThrownBy(() -> roles.disableRole(platform.roleId())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> roles.deleteRole(platform.roleId())).isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM ab_role WHERE id=?", String.class, platform.roleId())).isEqualTo("active");
    }

    @Test void systemDefinitionCanBeCreatedButItsReservedIdentityCannotBeRenamedOrRescoped() {
        platform = PlatformAdministrationFixture.grant(jdbc, user);
        MetaContext.setCurrentTenantId(1L);
        Role input = new Role(); input.setTenantId(1L); input.setCode("platform_admin"); input.setScopeType("global"); input.setName("Owned system definition");
        Role created = roles.createRole(input); ownedRoles.add(created.getId());
        assertThat(jdbc.queryForMap("SELECT tenant_id,scope_type FROM ab_role WHERE id=?", created.getId()))
                .containsEntry("tenant_id", 1L).containsEntry("scope_type", "global");
        Role rename = new Role(); rename.setId(created.getId()); rename.setCode("ordinary");
        assertThatThrownBy(() -> roles.updateRole(rename)).isInstanceOf(AccessDeniedException.class);
        Role scope = new Role(); scope.setId(created.getId()); scope.setScopeType("tenant");
        assertThatThrownBy(() -> roles.updateRole(scope)).isInstanceOf(AccessDeniedException.class);
    }
}
