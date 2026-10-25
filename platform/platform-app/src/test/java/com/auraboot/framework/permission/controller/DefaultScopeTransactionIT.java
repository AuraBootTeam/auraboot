package com.auraboot.framework.permission.controller;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.dto.RoleDefaultScopeRequest;
import com.auraboot.framework.permission.service.DataScopeService;
import com.auraboot.framework.permission.service.RolePermissionService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/** Real PostgreSQL writes exercise the controller transaction without an enclosing test transaction. */
@Transactional(propagation = Propagation.NEVER)
class DefaultScopeTransactionIT extends BaseIntegrationTest {
    @Autowired private PermissionMatrixController controller;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RolePermissionService grants;
    @Autowired private UserPermissionService permissions;
    @MockitoSpyBean private DataScopeService scopes;
    private Long roleId;
    private String rolePid;
    private String resource;

    @BeforeEach
    void fixture() {
        rolePid = UniqueIdGenerator.generate();
        resource = "default_scope_tx_" + rolePid.toLowerCase();
        roleId = IdWorker.getId();
        jdbc.update("""
                INSERT INTO ab_role (id,pid,tenant_id,code,name,type,status,deleted_flag,
                                     default_data_scope_type,created_at,updated_at)
                VALUES (?,?,?,?,?,'custom','active',false,'self',now(),now())
                """, roleId, rolePid, getTestTenant().getId(), resource, resource);
        for (String action : List.of("read", "update")) {
            Long permissionId = jdbc.queryForObject("""
                    INSERT INTO ab_permission (pid,tenant_id,code,name,resource_type,resource_code,
                                               action,status,deleted_flag,created_at,updated_at)
                    VALUES (?,?,?,?,'model',?,?,'active',false,now(),now()) RETURNING id
                    """, Long.class, UniqueIdGenerator.generate(), getTestTenant().getId(),
                    "model." + resource + "." + action, action, resource, action);
            grants.assignPermissionsToRole(roleId, List.of(permissionId));
            scopes.setScope(getTestTenant().getId(), roleId, resource, action, "self", "MIN");
        }
        permissions.evictPermissionDefinitions(getTestTenant().getId());
    }

    @AfterEach
    void cleanup() {
        reset(scopes);
        if (roleId != null) {
            jdbc.update("DELETE FROM ab_role_data_scope WHERE tenant_id=? AND role_id=?", getTestTenant().getId(), roleId);
            jdbc.update("DELETE FROM ab_role_permission WHERE tenant_id=? AND role_id=?", getTestTenant().getId(), roleId);
            jdbc.update("DELETE FROM ab_role WHERE tenant_id=? AND id=?", getTestTenant().getId(), roleId);
        }
        if (resource != null) {
            jdbc.update("DELETE FROM ab_permission WHERE tenant_id=? AND resource_code=?", getTestTenant().getId(), resource);
            permissions.evictPermissionDefinitions(getTestTenant().getId());
        }
    }

    private String persistedDefault() {
        return jdbc.queryForObject("SELECT default_data_scope_type FROM ab_role WHERE tenant_id=? AND id=?",
                String.class, getTestTenant().getId(), roleId);
    }

    private List<Map<String, Object>> persistedScopes() {
        return jdbc.queryForList("""
                SELECT action_code,scope_type,merge_strategy FROM ab_role_data_scope
                WHERE tenant_id=? AND role_id=? ORDER BY action_code
                """, getTestTenant().getId(), roleId);
    }

    @Test
    void successfulDefaultScopePersistsRoleAndEveryCurrentGrant() {
        controller.setDefaultScope(rolePid, new RoleDefaultScopeRequest("team"));
        assertThat(persistedDefault()).isEqualTo("team");
        assertThat(persistedScopes()).containsExactly(
                Map.of("action_code", "read", "scope_type", "team", "merge_strategy", "MAX"),
                Map.of("action_code", "update", "scope_type", "team", "merge_strategy", "MAX"));
    }

    @Test
    void failureAfterFirstRealScopeWriteRollsBackRoleAndPartialMaterialization() {
        List<Map<String, Object>> before = persistedScopes();
        AtomicInteger writes = new AtomicInteger();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            writes.incrementAndGet();
            assertThat(persistedDefault()).isEqualTo("team");
            assertThat(persistedScopes()).filteredOn(row -> "team".equals(row.get("scope_type"))).hasSize(1);
            throw new IllegalStateException("Injected failure after first real scope write");
        }).when(scopes).setScope(eq(getTestTenant().getId()), eq(roleId), eq(resource), anyString(), eq("team"), eq("MAX"));

        assertThatThrownBy(() -> controller.setDefaultScope(rolePid, new RoleDefaultScopeRequest("team")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Injected failure");
        assertThat(writes.get()).isEqualTo(1);
        assertThat(persistedDefault()).isEqualTo("self");
        assertThat(persistedScopes()).isEqualTo(before);
    }
}
