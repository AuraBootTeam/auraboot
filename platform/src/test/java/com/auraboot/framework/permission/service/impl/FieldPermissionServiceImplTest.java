package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.engine.model.FieldPermissionSet;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FieldPermissionServiceImpl}.
 */
@ExtendWith(MockitoExtension.class)
class FieldPermissionServiceImplTest {

    @Mock
    private MetaModelService metaModelService;

    @Mock
    private UserRoleService userRoleService;

    @Mock
    private RoleService roleService;

    @InjectMocks
    private FieldPermissionServiceImpl service;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(100L, 1L, "user-pid", "tester");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private FieldDefinition field(String code, Map<String, Object> extraProps) {
        FieldDefinition f = new FieldDefinition();
        f.setCode(code);
        f.setExtraProps(extraProps);
        return f;
    }

    @Test
    void returnsAllAllowedWhenModelHasNoFields() {
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(1L, 100L)).thenReturn(List.of());
        when(metaModelService.getModelFields("model.user")).thenReturn(List.of());

        FieldPermissionSet result = service.getFieldPermissions(1L, "model.user");

        assertThat(result.viewableFields()).isEmpty();
        assertThat(result.editableFields()).isEmpty();
        assertThat(result.hiddenFields()).isEmpty();
    }

    @Test
    void treatsFieldWithoutPermissionAsFullyAccessible() {
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(1L, 100L)).thenReturn(List.of());
        when(metaModelService.getModelFields("model.user")).thenReturn(List.of(
                field("name", null),
                field("email", new HashMap<>())));

        FieldPermissionSet result = service.getFieldPermissions(1L, "model.user");

        assertThat(result.viewableFields()).containsExactlyInAnyOrder("name", "email");
        assertThat(result.editableFields()).containsExactlyInAnyOrder("name", "email");
        assertThat(result.hiddenFields()).isEmpty();
    }

    @Test
    void hidesFieldWhenMemberRoleNotInViewList() {
        Role admin = new Role();
        admin.setId(7L);
        admin.setCode("admin");
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(1L, 100L)).thenReturn(List.of(7L));
        when(roleService.getById(7L)).thenReturn(admin);

        Map<String, Object> fp = Map.of(
                "view", List.of("sales_manager"),
                "edit", List.of("sales_manager"));
        Map<String, Object> extra = Map.of("fieldPermission", fp);

        when(metaModelService.getModelFields("model.user"))
                .thenReturn(List.of(field("salary", extra)));

        FieldPermissionSet result = service.getFieldPermissions(1L, "model.user");

        assertThat(result.hiddenFields()).containsExactly("salary");
        assertThat(result.viewableFields()).isEmpty();
        assertThat(result.editableFields()).isEmpty();
    }

    @Test
    void allowsViewButNotEditWhenOnlyInViewList() {
        Role admin = new Role();
        admin.setId(7L);
        admin.setCode("admin");
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(1L, 100L)).thenReturn(List.of(7L));
        when(roleService.getById(7L)).thenReturn(admin);

        Map<String, Object> fp = Map.of(
                "view", List.of("admin"),
                "edit", List.of("super_admin"));
        when(metaModelService.getModelFields("model.user"))
                .thenReturn(List.of(field("salary", Map.of("fieldPermission", fp))));

        FieldPermissionSet result = service.getFieldPermissions(1L, "model.user");

        assertThat(result.viewableFields()).containsExactly("salary");
        assertThat(result.editableFields()).isEmpty();
        assertThat(result.hiddenFields()).isEmpty();
    }

    @Test
    void unionsAccessAcrossMultipleRoles() {
        Role r1 = new Role();
        r1.setId(7L);
        r1.setCode("admin");
        Role r2 = new Role();
        r2.setId(8L);
        r2.setCode("manager");
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(1L, 100L)).thenReturn(List.of(7L, 8L));
        when(roleService.getById(7L)).thenReturn(r1);
        when(roleService.getById(8L)).thenReturn(r2);

        Map<String, Object> fp = Map.of(
                "view", List.of("manager"),
                "edit", List.of("admin"));
        when(metaModelService.getModelFields("model.user"))
                .thenReturn(List.of(field("salary", Map.of("fieldPermission", fp))));

        FieldPermissionSet result = service.getFieldPermissions(1L, "model.user");

        assertThat(result.viewableFields()).containsExactly("salary");
        assertThat(result.editableFields()).containsExactly("salary");
    }

    @Test
    void emptyAllowedListIsTreatedAsAllowed() {
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(1L, 100L)).thenReturn(List.of());
        Map<String, Object> fp = Map.of(
                "view", List.of(),
                "edit", List.of());
        when(metaModelService.getModelFields("model.user"))
                .thenReturn(List.of(field("name", Map.of("fieldPermission", fp))));

        FieldPermissionSet result = service.getFieldPermissions(1L, "model.user");

        assertThat(result.viewableFields()).containsExactly("name");
        assertThat(result.editableFields()).containsExactly("name");
    }

    @Test
    void skipsRolesWhenRoleServiceReturnsNull() {
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(1L, 100L)).thenReturn(List.of(7L));
        lenient().when(roleService.getById(anyLong())).thenReturn(null);

        Map<String, Object> fp = Map.of("view", List.of("admin"));
        when(metaModelService.getModelFields("model.user"))
                .thenReturn(List.of(field("salary", Map.of("fieldPermission", fp))));

        FieldPermissionSet result = service.getFieldPermissions(1L, "model.user");

        // No matching role → field hidden
        assertThat(result.hiddenFields()).containsExactly("salary");
    }
}
