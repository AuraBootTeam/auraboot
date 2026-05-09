package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PermissionPolicyServiceImpl}.
 */
@ExtendWith(MockitoExtension.class)
class PermissionPolicyServiceImplTest {

    @Mock
    private RolePermissionMapper rolePermissionMapper;

    @Mock
    private PermissionMapper permissionMapper;

    @Mock
    private UserRoleService userRoleService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private PermissionPolicyServiceImpl service;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(100L, 1L, "u", "t");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void getEffectivePolicyReturnsNullWhenNoRoles() {
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(1L, 100L)).thenReturn(List.of());

        assertThat(service.getEffectivePolicy(1L, "model.user.update")).isNull();
        verify(permissionMapper, never()).findByCode(anyString());
    }

    @Test
    void getEffectivePolicyReturnsNullWhenPermissionMissing() {
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(1L, 100L)).thenReturn(List.of(7L));
        when(permissionMapper.findByCode("model.user.update")).thenReturn(null);

        assertThat(service.getEffectivePolicy(1L, "model.user.update")).isNull();
    }

    @Test
    void getEffectivePolicyReturnsSinglePolicyWhenOneRoleMatches() {
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(1L, 100L)).thenReturn(List.of(7L));

        Permission perm = new Permission();
        perm.setId(50L);
        when(permissionMapper.findByCode("model.user.update")).thenReturn(perm);

        RolePermission rp = new RolePermission();
        rp.setId(900L);
        when(rolePermissionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(rp);
        when(rolePermissionMapper.getConditionsById(900L)).thenReturn("{\"maxAmount\":1000}");

        Map<String, Object> result = service.getEffectivePolicy(1L, "model.user.update");

        assertThat(result).containsEntry("maxAmount", 1000);
    }

    @Test
    void getEffectivePolicyMergesMaxNumericAcrossRoles() {
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(1L, 100L)).thenReturn(List.of(7L, 8L));
        Permission perm = new Permission();
        perm.setId(50L);
        when(permissionMapper.findByCode("model.user.update")).thenReturn(perm);

        RolePermission rp1 = new RolePermission();
        rp1.setId(900L);
        RolePermission rp2 = new RolePermission();
        rp2.setId(901L);

        when(rolePermissionMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(rp1, rp2);
        when(rolePermissionMapper.getConditionsById(900L)).thenReturn("{\"maxAmount\":1000,\"minAmount\":50,\"approval\":false,\"regions\":[\"NA\"]}");
        when(rolePermissionMapper.getConditionsById(901L)).thenReturn("{\"maxAmount\":5000,\"minAmount\":100,\"approval\":true,\"regions\":[\"EU\"]}");

        Map<String, Object> result = service.getEffectivePolicy(1L, "model.user.update");

        assertThat(((Number) result.get("maxAmount")).doubleValue()).isEqualTo(5000d);
        assertThat(((Number) result.get("minAmount")).doubleValue()).isEqualTo(50d);
        assertThat(result.get("approval")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Object> regions = (List<Object>) result.get("regions");
        assertThat(regions).containsExactlyInAnyOrder("NA", "EU");
    }

    @Test
    void getEffectivePolicyReturnsNullWhenAllPoliciesEmpty() {
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(1L, 100L)).thenReturn(List.of(7L));
        Permission perm = new Permission();
        perm.setId(50L);
        when(permissionMapper.findByCode("model.user.update")).thenReturn(perm);

        when(rolePermissionMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenReturn(null); // no rp binding

        assertThat(service.getEffectivePolicy(1L, "model.user.update")).isNull();
    }

    @Test
    void getPolicySchemaReturnsNullWhenPermissionMissing() {
        when(permissionMapper.findByCode("model.user.update")).thenReturn(null);

        assertThat(service.getPolicySchema("model.user.update")).isNull();
    }

    @Test
    void getPolicySchemaReturnsParsedMap() {
        Permission perm = new Permission();
        perm.setPolicySchema("{\"maxAmount\":{\"type\":\"number\"}}");
        when(permissionMapper.findByCode("model.user.update")).thenReturn(perm);

        Map<String, Object> schema = service.getPolicySchema("model.user.update");

        assertThat(schema).containsKey("maxAmount");
    }

    @Test
    void setPolicyWritesJsonViaMapper() {
        RolePermission rp = new RolePermission();
        rp.setId(900L);
        when(rolePermissionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(rp);

        service.setPolicy(7L, 50L, Map.of("maxAmount", 1000));

        ArgumentCaptor<String> jsonCap = ArgumentCaptor.forClass(String.class);
        verify(rolePermissionMapper).updateConditionsById(eq(900L), jsonCap.capture());
        assertThat(jsonCap.getValue()).contains("\"maxAmount\":1000");
    }

    @Test
    void setPolicySkipsWhenNoBindingFound() {
        when(rolePermissionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.setPolicy(7L, 50L, Map.of("k", "v"));

        verify(rolePermissionMapper, never()).updateConditionsById(anyLong(), anyString());
    }

    @Test
    void getPolicyReturnsNullWhenConditionsBlank() {
        RolePermission rp = new RolePermission();
        rp.setId(900L);
        when(rolePermissionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(rp);
        when(rolePermissionMapper.getConditionsById(900L)).thenReturn("");

        assertThat(service.getPolicy(7L, 50L)).isNull();
    }

    @Test
    void getPoliciesByRoleIdReturnsEmptyMapWhenNoRows() {
        when(rolePermissionMapper.findConditionsByRoleId(7L)).thenReturn(List.of());

        assertThat(service.getPoliciesByRoleId(7L)).isEmpty();
    }

    @Test
    void getPoliciesByRoleIdParsesEachRow() {
        RolePermissionMapper.RolePermissionConditionsRow row = new RolePermissionMapper.RolePermissionConditionsRow();
        row.setPermissionId(50L);
        row.setConditionsJson("{\"maxAmount\":1000}");
        when(rolePermissionMapper.findConditionsByRoleId(7L)).thenReturn(List.of(row));

        Map<Long, Map<String, Object>> result = service.getPoliciesByRoleId(7L);

        assertThat(result).containsOnlyKeys(50L);
        assertThat(result.get(50L)).containsEntry("maxAmount", 1000);
    }

    @Test
    void getPoliciesByRoleIdSkipsBlankConditions() {
        RolePermissionMapper.RolePermissionConditionsRow row = new RolePermissionMapper.RolePermissionConditionsRow();
        row.setPermissionId(50L);
        row.setConditionsJson("");
        when(rolePermissionMapper.findConditionsByRoleId(7L)).thenReturn(List.of(row));

        assertThat(service.getPoliciesByRoleId(7L)).isEmpty();
    }
}
