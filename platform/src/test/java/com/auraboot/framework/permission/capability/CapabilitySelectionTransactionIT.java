package com.auraboot.framework.permission.capability;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.permission.service.RolePermissionService;
import com.auraboot.framework.plugin.dto.imports.CapabilityDefinitionDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL writes prove that grant and revoke share the service transaction. */
@Transactional(propagation = Propagation.NEVER)
class CapabilitySelectionTransactionIT extends BaseIntegrationTest {
    @Autowired private CapabilityViewService capabilities;
    @Autowired private CapabilityRegistryService registry;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private RolePermissionService grants;
    private Long roleId;
    private Long oldId;
    private Long newId;
    private String prefix;

    @BeforeEach void fixture() {
        prefix = "cap_tx_" + UniqueIdGenerator.generate().toLowerCase();
        roleId = jdbc.queryForObject("""
                INSERT INTO ab_role (id,pid,tenant_id,code,name,type,status,deleted_flag,created_at,updated_at)
                VALUES (?,?,?,?,?,'custom','active',false,now(),now()) RETURNING id
                """, Long.class, com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), UniqueIdGenerator.generate(), getTestTenant().getId(), prefix, prefix);
        oldId = permission("old");
        newId = permission("new");
        for (String suffix : List.of("old", "new")) registry.saveDefinition(
                CapabilityDefinitionDTO.builder().code(prefix + "." + suffix).group("Transaction fixture")
                        .includes(List.of(prefix + "." + suffix + ".read")).build());
        grants.assignPermissionsToRole(roleId, List.of(oldId));
    }
    private Long permission(String suffix) {
        return jdbc.queryForObject("""
                INSERT INTO ab_permission (pid,tenant_id,code,name,resource_type,status,deleted_flag,created_at,updated_at)
                VALUES (?,?,?,?,'API','active',false,now(),now()) RETURNING id
                """, Long.class, UniqueIdGenerator.generate(), getTestTenant().getId(),
                prefix + "." + suffix + ".read", suffix);
    }
    private List<Long> persisted() {
        return jdbc.queryForList("SELECT permission_id FROM ab_role_permission WHERE role_id=? AND deleted_flag=false ORDER BY permission_id", Long.class, roleId);
    }
    @AfterEach void cleanup() {
        reset(grants);
        if (roleId != null) {
            jdbc.update("DELETE FROM ab_role_permission WHERE role_id=?", roleId);
            jdbc.update("DELETE FROM ab_role WHERE id=?", roleId);
        }
        if (prefix != null) {
            jdbc.update("DELETE FROM ab_permission_capability WHERE tenant_id=? AND code LIKE ?", getTestTenant().getId(), prefix + ".%");
            jdbc.update("DELETE FROM ab_permission WHERE tenant_id=? AND code LIKE ?", getTestTenant().getId(), prefix + ".%");
        }
    }
    @Test void successfulSelectionPersistsGrantAndRevoke() {
        capabilities.applyCapabilitySelection(roleId, Set.of(prefix + ".new"));
        assertThat(persisted()).containsExactly(newId);
    }
    @Test void failureAfterBothRealWritesRollsBackTheWholeSelection() {
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            assertThat(persisted()).containsExactly(newId);
            throw new IllegalStateException("Injected failure after real grant and revoke");
        }).when(grants).removePermissionsFromRoleByPids(eq(roleId), anyList());
        assertThatThrownBy(() -> capabilities.applyCapabilitySelection(roleId, Set.of(prefix + ".new")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Injected failure");
        assertThat(persisted()).containsExactly(oldId);
    }
}
