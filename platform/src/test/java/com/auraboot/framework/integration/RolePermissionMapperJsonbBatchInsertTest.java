package com.auraboot.framework.integration;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.permission.dto.PermissionCreateRequest;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.service.RoleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the deep-review finding DR-20260618-D3-jsonb-001.
 *
 * <p>{@link RolePermissionMapper#batchInsert} writes the JSONB {@code conditions} column. The
 * custom {@code @Insert} did not apply the entity's {@code @TableField} JacksonTypeHandler, so a
 * non-null {@code conditions} value (a {@code Map}) fell through to a default handler and failed
 * ("No hstore extension installed"); even once the handler is selected, the JDBC URL does not set
 * {@code stringtype=unspecified}, so the bound varchar must be cast with {@code ::jsonb}. The
 * single-row {@code @Update} already did this; this test pins both on {@code batchInsert}.
 *
 * <p>Existing tests only ever wrote {@code conditions = null}, so the latent bug was never exercised.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RolePermissionMapperJsonbBatchInsertTest extends BaseIntegrationTest {

    @Autowired
    private RolePermissionMapper rolePermissionMapper;

    @Autowired
    private RoleService roleService;

    @Autowired
    private PermissionService permissionService;

    @Test
    @DisplayName("RP-JSONB-01: batchInsert persists a non-null conditions value into the jsonb column")
    void batchInsertWithNonNullConditions() {
        String runId = String.valueOf(System.nanoTime());

        // ab_role_permission has FKs to ab_role / ab_permission — create real rows first.
        Role role = new Role();
        role.setPid(UniqueIdGenerator.generate());
        role.setName("JSONB Test Role " + runId);
        role.setCode("rp_jsonb_role_" + runId);
        role.setType("custom");
        role.setScopeType("tenant");
        role.setStatus("active");
        role.setTenantId(testTenant.getId());
        role.setIsDefault(false);
        role.setIsSystem(false);
        role.setDeletedFlag(false);
        role.setPriority(50);
        role.setCreatedAt(Instant.now());
        role.setUpdatedAt(Instant.now());
        Long roleId = roleService.createRole(role).getId();

        PermissionCreateRequest permReq = new PermissionCreateRequest();
        permReq.setCode("rp_jsonb_perm_" + runId);
        permReq.setName("JSONB Test Permission");
        permReq.setDescription("deep-review jsonb batchInsert regression");
        permReq.setResourceType("model");
        permReq.setResourceCode("rp_jsonb_model_" + runId);
        permReq.setAction("read");
        permReq.setSource("integration_test");
        PermissionDTO perm = permissionService.create(permReq);
        Long permissionId = perm.getId();

        RolePermission binding = new RolePermission();
        binding.setPid(UniqueIdGenerator.generate());
        binding.setTenantId(testTenant.getId());
        binding.setRoleId(roleId);
        binding.setPermissionId(permissionId);
        binding.setGrantType("grant");
        binding.setPriority(0);
        binding.setConditions(Map.of("region", "APAC", "level", 3)); // non-null JSON object
        binding.setStatus("active");
        binding.setDeletedFlag(false);
        binding.setCreatedAt(Instant.now());
        binding.setUpdatedAt(Instant.now());
        binding.setCreatedBy(testUser.getId());
        binding.setUpdatedBy(testUser.getId());

        // Before the typeHandler + ::jsonb fix this throws "No hstore extension installed" /
        // a varchar→jsonb type error on PostgreSQL.
        int affected = rolePermissionMapper.batchInsert(List.of(binding));
        assertThat(affected).isGreaterThanOrEqualTo(1);

        List<RolePermission> rows = rolePermissionMapper.findByRole(roleId);
        assertThat(rows).isNotEmpty();
        Object conditions = rows.get(0).getConditions();
        assertThat(conditions).isNotNull();
        assertThat(conditions.toString()).contains("APAC");
    }
}
