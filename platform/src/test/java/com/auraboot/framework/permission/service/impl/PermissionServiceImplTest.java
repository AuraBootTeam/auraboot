package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.application.exception.DuplicateException;
import com.auraboot.framework.application.exception.ResourceNotFoundException;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.converter.PermissionConverter;
import com.auraboot.framework.permission.dto.PermissionCreateRequest;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.dto.PermissionReferenceDTO;
import com.auraboot.framework.permission.dto.PermissionUpdateRequest;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PermissionServiceImpl}.
 */
@ExtendWith(MockitoExtension.class)
class PermissionServiceImplTest {

    @Mock
    private PermissionMapper permissionMapper;

    @Mock
    private PermissionConverter permissionConverter;

    @Mock
    private UserPermissionService userPermissionService;

    @Mock
    private RolePermissionMapper rolePermissionMapper;

    @Mock
    private RoleMapper roleMapper;

    @InjectMocks
    private PermissionServiceImpl service;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(100L, 1L, "u-pid", "tester");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private PermissionCreateRequest createRequest(String code) {
        PermissionCreateRequest req = new PermissionCreateRequest();
        req.setCode(code);
        req.setName("Read user");
        req.setResourceType("MODEL");
        req.setResourceCode("model.user");
        req.setAction("read");
        return req;
    }

    @Test
    void createValidatesEmptyCode() {
        PermissionCreateRequest req = createRequest("");

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("code");
    }

    @Test
    void createValidatesEmptyName() {
        PermissionCreateRequest req = createRequest("model.user.read");
        req.setName("  ");

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void createValidatesEmptyResourceType() {
        PermissionCreateRequest req = createRequest("model.user.read");
        req.setResourceType("");

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createValidatesEmptyResourceCode() {
        PermissionCreateRequest req = createRequest("model.user.read");
        req.setResourceCode(null);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createValidatesEmptyAction() {
        PermissionCreateRequest req = createRequest("model.user.read");
        req.setAction(null);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createThrowsWhenCodeAlreadyExists() {
        PermissionCreateRequest req = createRequest("model.user.read");
        when(permissionMapper.countByCode(eq("model.user.read"), isNull())).thenReturn(1);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(DuplicateException.class);
    }

    @Test
    void createInsertsAndReturnsDTO() {
        PermissionCreateRequest req = createRequest("model.user.read");
        req.setPluginPid("plugin-1");
        Permission entity = new Permission();
        when(permissionConverter.toEntity(req)).thenReturn(entity);
        when(permissionMapper.countByCode(eq("model.user.read"), isNull())).thenReturn(0);
        PermissionDTO dto = new PermissionDTO();
        when(permissionConverter.toDTO(entity)).thenReturn(dto);

        PermissionDTO result = service.create(req);

        assertThat(result).isSameAs(dto);
        verify(permissionMapper).insert(entity);
        assertThat(entity.getStatus()).isEqualTo("active");
        assertThat(entity.getTenantId()).isEqualTo(100L);
        assertThat(entity.getPluginPid()).isEqualTo("plugin-1");
        assertThat(entity.getPid()).isNotBlank();
    }

    @Test
    void updateThrowsWhenPermissionMissing() {
        when(permissionMapper.selectById(50L)).thenReturn(null);

        assertThatThrownBy(() -> service.update(50L, new PermissionUpdateRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateThrowsWhenArchived() {
        Permission p = new Permission();
        p.setId(50L);
        p.setStatus("archived");
        when(permissionMapper.selectById(50L)).thenReturn(p);

        assertThatThrownBy(() -> service.update(50L, new PermissionUpdateRequest()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void updateAppliesChangesAndReturnsDTO() {
        Permission p = new Permission();
        p.setId(50L);
        p.setStatus("active");
        when(permissionMapper.selectById(50L)).thenReturn(p);
        PermissionDTO dto = new PermissionDTO();
        when(permissionConverter.toDTO(p)).thenReturn(dto);
        PermissionUpdateRequest req = new PermissionUpdateRequest();

        PermissionDTO result = service.update(50L, req);

        assertThat(result).isSameAs(dto);
        verify(permissionConverter).updateEntity(p, req);
        verify(permissionMapper).updateById(p);
    }

    @Test
    void deleteThrowsWhenNotFound() {
        when(permissionMapper.selectById(50L)).thenReturn(null);

        assertThatThrownBy(() -> service.delete(50L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteThrowsWhenChildrenExist() {
        Permission p = new Permission();
        p.setId(50L);
        when(permissionMapper.selectById(50L)).thenReturn(p);
        when(permissionMapper.findChildren(50L)).thenReturn(List.of(new Permission()));

        assertThatThrownBy(() -> service.delete(50L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deleteSoftDeletesViaUpdateWrapper() {
        Permission p = new Permission();
        p.setId(50L);
        p.setCode("model.user.read");
        when(permissionMapper.selectById(50L)).thenReturn(p);
        when(permissionMapper.findChildren(50L)).thenReturn(List.of());

        service.delete(50L);

        verify(permissionMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void findByIdThrowsWhenMissing() {
        when(permissionMapper.selectById(50L)).thenReturn(null);

        assertThatThrownBy(() -> service.findById(50L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void findByIdReturnsDTO() {
        Permission p = new Permission();
        when(permissionMapper.selectById(50L)).thenReturn(p);
        PermissionDTO dto = new PermissionDTO();
        when(permissionConverter.toDTO(p)).thenReturn(dto);

        assertThat(service.findById(50L)).isSameAs(dto);
    }

    @Test
    void findByCodeThrowsWhenMissing() {
        when(permissionMapper.findByCode("missing")).thenReturn(null);

        assertThatThrownBy(() -> service.findByCode("missing"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void findByResourceTypeReturnsList() {
        when(permissionMapper.findByResourceType("MODEL")).thenReturn(List.of(new Permission()));
        when(permissionConverter.toDTOList(anyList())).thenReturn(List.of(new PermissionDTO()));

        assertThat(service.findByResourceType("MODEL")).hasSize(1);
    }

    @Test
    void findByResourceReturnsList() {
        when(permissionMapper.findByResource("MODEL", "model.user")).thenReturn(List.of(new Permission()));
        when(permissionConverter.toDTOList(anyList())).thenReturn(List.of(new PermissionDTO()));

        assertThat(service.findByResource("MODEL", "model.user")).hasSize(1);
    }

    @Test
    void findAllActiveReturnsList() {
        when(permissionMapper.findByStatus("active")).thenReturn(List.of(new Permission()));
        when(permissionConverter.toDTOList(anyList())).thenReturn(List.of(new PermissionDTO()));

        assertThat(service.findAllActive()).hasSize(1);
    }

    @Test
    void deprecateThrowsWhenMissing() {
        when(permissionMapper.selectById(50L)).thenReturn(null);

        assertThatThrownBy(() -> service.deprecate(50L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deprecateThrowsWhenAlreadyDeprecated() {
        Permission p = new Permission();
        p.setId(50L);
        p.setStatus("deprecated");
        when(permissionMapper.selectById(50L)).thenReturn(p);

        assertThatThrownBy(() -> service.deprecate(50L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deprecateThrowsWhenChildrenExist() {
        Permission p = new Permission();
        p.setId(50L);
        p.setStatus("active");
        when(permissionMapper.selectById(50L)).thenReturn(p);
        when(permissionMapper.findChildren(50L)).thenReturn(List.of(new Permission()));

        assertThatThrownBy(() -> service.deprecate(50L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void deprecateUpdatesStatus() {
        Permission p = new Permission();
        p.setId(50L);
        p.setStatus("active");
        when(permissionMapper.selectById(50L)).thenReturn(p);
        when(permissionMapper.findChildren(50L)).thenReturn(List.of());

        service.deprecate(50L);

        verify(permissionMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void archiveThrowsWhenNotDeprecated() {
        Permission p = new Permission();
        p.setId(50L);
        p.setStatus("active");
        when(permissionMapper.selectById(50L)).thenReturn(p);

        assertThatThrownBy(() -> service.archive(50L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void archiveThrowsWhenWithinSixMonths() {
        Permission p = new Permission();
        p.setId(50L);
        p.setStatus("deprecated");
        p.setDeprecatedAt(Instant.now().minus(30, ChronoUnit.DAYS));
        when(permissionMapper.selectById(50L)).thenReturn(p);

        assertThatThrownBy(() -> service.archive(50L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void archiveSucceedsWhenSixMonthsPassed() {
        Permission p = new Permission();
        p.setId(50L);
        p.setStatus("deprecated");
        p.setDeprecatedAt(Instant.now().minus(200, ChronoUnit.DAYS));
        when(permissionMapper.selectById(50L)).thenReturn(p);

        service.archive(50L);

        verify(permissionMapper).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void findUserPermissionsReturnsEmptyWhenNoIds() {
        when(userPermissionService.getUserPermissionIds(1L)).thenReturn(Set.of());

        assertThat(service.findUserPermissions(1L)).isEmpty();
        verify(permissionMapper, never()).findByIds(anyList());
    }

    @Test
    void findUserPermissionsLoadsAndConverts() {
        when(userPermissionService.getUserPermissionIds(1L)).thenReturn(Set.of(50L));
        when(permissionMapper.findByIds(anyList())).thenReturn(List.of(new Permission()));
        when(permissionConverter.toDTOList(anyList())).thenReturn(List.of(new PermissionDTO()));

        assertThat(service.findUserPermissions(1L)).hasSize(1);
    }

    @Test
    void findRolePermissionsReturnsEmptyWhenNone() {
        when(rolePermissionMapper.findPermissionIdsByRole(7L)).thenReturn(Set.of());

        assertThat(service.findRolePermissions(7L)).isEmpty();
    }

    @Test
    void findRolePermissionsReturnsList() {
        when(rolePermissionMapper.findPermissionIdsByRole(7L)).thenReturn(Set.of(50L));
        when(permissionMapper.findByIds(anyList())).thenReturn(List.of(new Permission()));
        when(permissionConverter.toDTOList(anyList())).thenReturn(List.of(new PermissionDTO()));

        assertThat(service.findRolePermissions(7L)).hasSize(1);
    }

    @Test
    void bindToRoleThrowsWhenPermissionMissing() {
        when(permissionMapper.selectById(50L)).thenReturn(null);

        assertThatThrownBy(() -> service.bindToRole(7L, 50L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void bindToRoleSkipsWhenAlreadyBound() {
        Permission p = new Permission();
        p.setId(50L);
        when(permissionMapper.selectById(50L)).thenReturn(p);
        when(rolePermissionMapper.countByBinding(eq(7L), eq(50L), isNull())).thenReturn(1);

        service.bindToRole(7L, 50L);

        verify(rolePermissionMapper, never()).insert(any(RolePermission.class));
    }

    @Test
    void bindToRoleInsertsBinding() {
        Permission p = new Permission();
        p.setId(50L);
        when(permissionMapper.selectById(50L)).thenReturn(p);
        when(rolePermissionMapper.countByBinding(eq(7L), eq(50L), isNull())).thenReturn(0);

        service.bindToRole(7L, 50L);

        verify(rolePermissionMapper).insert(any(RolePermission.class));
    }

    @Test
    void unbindFromRoleThrowsWhenNoMatchingBinding() {
        when(rolePermissionMapper.findByRole(7L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.unbindFromRole(7L, 50L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void unbindFromRoleSoftDeletesAllMatching() {
        RolePermission rp1 = new RolePermission();
        rp1.setId(900L);
        rp1.setPermissionId(50L);
        RolePermission rp2 = new RolePermission();
        rp2.setId(901L);
        rp2.setPermissionId(99L); // unrelated
        when(rolePermissionMapper.findByRole(7L)).thenReturn(List.of(rp1, rp2));

        service.unbindFromRole(7L, 50L);

        verify(rolePermissionMapper).softDelete(900L);
        verify(rolePermissionMapper, never()).softDelete(901L);
    }

    @Test
    void findReferencesThrowsWhenPermissionMissing() {
        when(permissionMapper.selectById(50L)).thenReturn(null);

        assertThatThrownBy(() -> service.findReferences(50L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void findReferencesReturnsEmptyWhenNoBindings() {
        Permission p = new Permission();
        p.setId(50L);
        when(permissionMapper.selectById(50L)).thenReturn(p);
        when(rolePermissionMapper.findByPermission(50L)).thenReturn(List.of());

        assertThat(service.findReferences(50L)).isEmpty();
    }

    @Test
    void findReferencesPopulatesRoleInfo() {
        Permission p = new Permission();
        p.setId(50L);
        when(permissionMapper.selectById(50L)).thenReturn(p);

        RolePermission rp = new RolePermission();
        rp.setId(900L);
        rp.setRoleId(7L);
        rp.setGrantType("grant");
        rp.setStatus("active");
        rp.setPriority(0);
        rp.setCreatedAt(Instant.now());
        when(rolePermissionMapper.findByPermission(50L)).thenReturn(List.of(rp));

        Role role = new Role();
        role.setName("Admin");
        role.setCode("admin");
        when(roleMapper.selectById(7L)).thenReturn(role);

        List<PermissionReferenceDTO> refs = service.findReferences(50L);

        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).getRoleName()).isEqualTo("Admin");
        assertThat(refs.get(0).getRoleCode()).isEqualTo("admin");
    }

    @Test
    void findDeprecatedForArchiveDelegatesToMapper() {
        when(permissionMapper.findDeprecatedForArchive(any())).thenReturn(List.of(new Permission()));
        when(permissionConverter.toDTOList(anyList())).thenReturn(List.of(new PermissionDTO()));

        assertThat(service.findDeprecatedForArchive(6)).hasSize(1);
    }
}
