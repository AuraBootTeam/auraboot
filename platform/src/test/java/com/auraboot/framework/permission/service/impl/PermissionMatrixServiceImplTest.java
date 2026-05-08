package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.dto.PermissionGrantRequest;
import com.auraboot.framework.permission.dto.PermissionMatrixDTO;
import com.auraboot.framework.permission.entity.RoleDataScope;
import com.auraboot.framework.permission.service.DataScopeService;
import com.auraboot.framework.permission.service.PermissionPolicyService;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.permission.service.RolePermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PermissionMatrixServiceImpl}.
 */
@ExtendWith(MockitoExtension.class)
class PermissionMatrixServiceImplTest {

    @Mock
    private PermissionService permissionService;

    @Mock
    private RolePermissionService rolePermissionService;

    @Mock
    private DataScopeService dataScopeService;

    @Mock
    private PermissionPolicyService policyService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private PermissionMatrixServiceImpl service;

    private PermissionDTO permission(Long id, String code, Integer level, Long parentId,
                                     String resourceType, String resourceCode, String action) {
        PermissionDTO p = new PermissionDTO();
        p.setId(id);
        p.setPid("pid-" + id);
        p.setCode(code);
        p.setName(code);
        p.setLevel(level);
        p.setParentId(parentId);
        p.setResourceType(resourceType);
        p.setResourceCode(resourceCode);
        p.setAction(action);
        return p;
    }

    @Test
    void getMatrixBuildsHierarchyFromLevels() {
        PermissionDTO module = permission(1L, "model", 1, null, "MODEL", null, null);
        PermissionDTO resource = permission(2L, "model.user", 2, 1L, "MODEL", "model.user", null);
        PermissionDTO action = permission(3L, "model.user.read", 3, 2L, "MODEL", "model.user", "read");
        when(permissionService.findAllActive()).thenReturn(List.of(module, resource, action));

        PermissionMatrixDTO matrix = service.getMatrix(100L);

        assertThat(matrix.modules()).hasSize(1);
        assertThat(matrix.modules().get(0).resources()).hasSize(1);
        assertThat(matrix.modules().get(0).resources().get(0).actions()).hasSize(1);
        assertThat(matrix.modules().get(0).resources().get(0).actions().get(0).action()).isEqualTo("read");
    }

    @Test
    void getMatrixFallsBackToFlatGrouping() {
        // No level=1 or level=2 — falls back to grouping by resourceType + resourceCode
        PermissionDTO p1 = permission(10L, "model.user.read", null, null, "MODEL", "model.user", "read");
        PermissionDTO p2 = permission(11L, "model.user.create", null, null, "MODEL", "model.user", "create");
        when(permissionService.findAllActive()).thenReturn(List.of(p1, p2));

        PermissionMatrixDTO matrix = service.getMatrix(100L);

        assertThat(matrix.modules()).hasSize(1);
        assertThat(matrix.modules().get(0).moduleCode()).isEqualTo("MODEL");
        // Standard ordering: read before create
        assertThat(matrix.modules().get(0).resources().get(0).actions().get(0).action()).isEqualTo("read");
        assertThat(matrix.modules().get(0).resources().get(0).actions().get(1).action()).isEqualTo("create");
    }

    @Test
    void getMatrixForRoleMarksGrantedAndIncludesScope() {
        PermissionDTO module = permission(1L, "model", 1, null, "MODEL", null, null);
        PermissionDTO resource = permission(2L, "model.user", 2, 1L, "MODEL", "model.user", null);
        PermissionDTO action = permission(3L, "model.user.read", 3, 2L, "MODEL", "model.user", "read");
        when(permissionService.findAllActive()).thenReturn(List.of(module, resource, action));
        when(rolePermissionService.getPermissionIdsByRoleId(7L)).thenReturn(Set.of(3L));

        RoleDataScope scope = new RoleDataScope();
        scope.setResourceCode("model.user");
        scope.setActionCode("read");
        scope.setScopeType("dept");
        scope.setMergeStrategy("MAX");
        when(dataScopeService.getScopesByRole(100L, 7L)).thenReturn(List.of(scope));
        when(policyService.getPoliciesByRoleId(7L)).thenReturn(Map.of());

        PermissionMatrixDTO matrix = service.getMatrixForRole(100L, 7L);

        assertThat(matrix.modules().get(0).resources().get(0).actions().get(0).granted()).isTrue();
        assertThat(matrix.modules().get(0).resources().get(0).actions().get(0).scopeType()).isEqualTo("dept");
        assertThat(matrix.modules().get(0).resources().get(0).actions().get(0).mergeStrategy()).isEqualTo("MAX");
    }

    @Test
    void getMatrixForRoleHandlesNullActionWithoutNpe() {
        PermissionDTO p1 = permission(10L, "weird.x", null, null, "OTHER", "weird.x", null);
        when(permissionService.findAllActive()).thenReturn(List.of(p1));
        when(rolePermissionService.getPermissionIdsByRoleId(7L)).thenReturn(Set.of());
        when(dataScopeService.getScopesByRole(100L, 7L)).thenReturn(List.of());
        when(policyService.getPoliciesByRoleId(7L)).thenReturn(Map.of());

        PermissionMatrixDTO matrix = service.getMatrixForRole(100L, 7L);

        assertThat(matrix.modules()).isNotEmpty();
        assertThat(matrix.modules().get(0).resources().get(0).actions().get(0).action()).isEqualTo("unknown");
    }

    @Test
    void batchUpdateRolePermissionsHandlesEmptyAndNull() {
        service.batchUpdateRolePermissions(7L, null);
        service.batchUpdateRolePermissions(7L, List.of());

        verify(rolePermissionService, never()).assignPermissionsToRole(anyLong(), anyList());
        verify(rolePermissionService, never()).removePermission(anyLong(), anyLong());
    }

    @Test
    void batchUpdateRolePermissionsAssignsAndRevokes() {
        PermissionGrantRequest grant = new PermissionGrantRequest(50L, true);
        PermissionGrantRequest revoke = new PermissionGrantRequest(51L, false);

        service.batchUpdateRolePermissions(7L, List.of(grant, revoke));

        verify(rolePermissionService).assignPermissionsToRole(7L, List.of(50L));
        verify(rolePermissionService).removePermission(7L, 51L);
    }

    @Test
    void batchUpdateRolePermissionsSkipsAssignWhenNoneGranted() {
        PermissionGrantRequest revoke = new PermissionGrantRequest(51L, false);

        service.batchUpdateRolePermissions(7L, List.of(revoke));

        verify(rolePermissionService, never()).assignPermissionsToRole(anyLong(), anyList());
        verify(rolePermissionService).removePermission(7L, 51L);
    }
}
