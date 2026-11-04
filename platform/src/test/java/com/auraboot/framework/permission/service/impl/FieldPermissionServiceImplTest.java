package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.impl.DynamicDataQueryScope;
import com.auraboot.framework.permission.engine.model.FieldPermissionSet;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.service.MemberRoleContributor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for {@link FieldPermissionServiceImpl}.
 */
@ExtendWith(MockitoExtension.class)
class FieldPermissionServiceImplTest {

    static Stream<Object> malformedPermissions() {
        Map<String, Object> nullAction = new HashMap<>();
        nullAction.put("view", null);
        return Stream.of("admin", List.of("admin"), true,
                Map.of("view", "admin"), Map.of("edit", true),
                Map.of("view", List.of("admin", 7)), Map.of("view", List.of(" ")),
                Map.of("views", List.of("admin")), nullAction);
    }

    @ParameterizedTest
    @MethodSource("malformedPermissions")
    void rejectsMalformedPermissionBeforeResolvingRoles(Object permission) {
        when(metaModelService.getModelFields("model.user"))
                .thenReturn(List.of(field("salary", Map.of("fieldPermission", permission))));

        assertThatThrownBy(() -> service.getFieldPermissions(1L, "model.user"))
                .isInstanceOf(MetaServiceException.class)
                .hasMessage("Invalid field permission declaration for field: salary");
        verifyNoInteractions(roleMapper, contributor);
    }

    @Test
    void unrelatedExtensionDoesNotHideDynamicPermission() {
        when(metaModelService.getModelFields("model.user"))
                .thenReturn(List.of(field("salary", Map.of(
                        "extension", Map.of("helpText", "Salary"),
                        "dynamicProperties", Map.of("fieldPermission", Map.of("view", List.of("admin")))))));

        FieldPermissionSet result = service.getFieldPermissions(1L, "model.user");

        assertThat(result.hiddenFields()).containsExactly("salary");
        assertThat(result.viewableFields()).isEmpty();
        assertThat(result.editableFields()).isEmpty();
    }

    @Mock
    private MetaModelService metaModelService;

    @Mock
    private RoleMapper roleMapper;

    @Mock
    private MemberRoleContributor contributor;

    private FieldPermissionServiceImpl service;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(100L, 1L, "user-pid", "tester");
        MetaContext.setMemberId(1L);
        service = new FieldPermissionServiceImpl(metaModelService, roleMapper, List.of(contributor));
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
        when(metaModelService.getModelFields("model.user")).thenReturn(List.of());

        FieldPermissionSet result = service.getFieldPermissions(1L, "model.user");

        assertThat(result.viewableFields()).isEmpty();
        assertThat(result.editableFields()).isEmpty();
        assertThat(result.hiddenFields()).isEmpty();
        verifyNoInteractions(roleMapper, contributor);
    }

    @Test
    void reusesFieldPermissionsInsideDynamicDataQueryScope() {
        when(metaModelService.getModelFields("model.user"))
                .thenReturn(List.of(field("name", null)));

        try (DynamicDataQueryScope ignored = DynamicDataQueryScope.open()) {
            assertThat(service.getFieldPermissions(1L, "model.user").viewableFields())
                    .containsExactly("name");
            assertThat(service.getFieldPermissions(1L, "model.user").viewableFields())
                    .containsExactly("name");
        }

        verifyNoInteractions(roleMapper, contributor);
        verify(metaModelService, times(1)).getModelFields("model.user");
    }

    @Test
    void treatsFieldWithoutPermissionAsFullyAccessible() {
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
        when(roleMapper.findEffectiveFieldRoleCodes(100L, 1L, 1L, List.of())).thenReturn(List.of("admin"));

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
    void readsFieldPermissionFromNestedExtensionMap() {
        when(roleMapper.findEffectiveFieldRoleCodes(100L, 1L, 1L, List.of())).thenReturn(List.of("low_field_viewer"));

        Map<String, Object> fieldPermission = Map.of(
                "view", List.of("full_field_editor"),
                "edit", List.of("full_field_editor"));
        when(metaModelService.getModelFields("model.leave"))
                .thenReturn(List.of(field("leave_type", Map.of(
                        "extension", Map.of("fieldPermission", fieldPermission)))));

        FieldPermissionSet result = service.getFieldPermissions(1L, "model.leave");

        assertThat(result.hiddenFields()).containsExactly("leave_type");
        assertThat(result.viewableFields()).isEmpty();
        assertThat(result.editableFields()).isEmpty();
    }

    @Test
    void readsFieldPermissionFromDynamicPropertiesExtensionMap() {
        when(roleMapper.findEffectiveFieldRoleCodes(100L, 1L, 1L, List.of())).thenReturn(List.of("low_field_viewer"));

        Map<String, Object> fieldPermission = Map.of(
                "view", List.of("low_field_viewer"),
                "edit", List.of("full_field_editor"));
        when(metaModelService.getModelFields("model.leave"))
                .thenReturn(List.of(field("start_date", Map.of(
                        "dynamicProperties", Map.of(
                                "extension", Map.of("fieldPermission", fieldPermission))))));

        FieldPermissionSet result = service.getFieldPermissions(1L, "model.leave");

        assertThat(result.viewableFields()).containsExactly("start_date");
        assertThat(result.editableFields()).isEmpty();
        assertThat(result.hiddenFields()).isEmpty();
    }

    @Test
    void allowsViewButNotEditWhenOnlyInViewList() {
        when(roleMapper.findEffectiveFieldRoleCodes(100L, 1L, 1L, List.of())).thenReturn(List.of("admin"));

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
        when(roleMapper.findEffectiveFieldRoleCodes(100L, 1L, 1L, List.of())).thenReturn(List.of("admin", "manager"));

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
    void qualifiesContributedRolesThroughTheSameActorQuery() {
        when(metaModelService.getModelFields("model.user"))
                .thenReturn(List.of(field("salary", Map.of("fieldPermission", Map.of("view", List.of("team_viewer"))))));
        when(contributor.findRoleIds(1L, 100L)).thenReturn(java.util.Arrays.asList(null, -1L, 7L, 7L));
        when(roleMapper.findEffectiveFieldRoleCodes(100L, 1L, 1L, List.of(7L)))
                .thenReturn(List.of("team_viewer"));

        assertThat(service.getFieldPermissions(1L, "model.user").viewableFields()).containsExactly("salary");
    }

    @Test
    void currentUserChangesCannotReuseAnotherActorsFieldResult() {
        when(metaModelService.getModelFields("model.user"))
                .thenReturn(List.of(field("salary", Map.of("fieldPermission", Map.of("view", List.of("admin"))))));
        when(roleMapper.findEffectiveFieldRoleCodes(100L, 1L, 1L, List.of())).thenReturn(List.of("admin"));
        try (DynamicDataQueryScope ignored = DynamicDataQueryScope.open()) {
            assertThat(service.getFieldPermissions(1L, "model.user").viewableFields()).containsExactly("salary");
            MetaContext.setContext(100L, 2L, "other-user", "other");
            MetaContext.setMemberId(1L);
            assertThat(service.getFieldPermissions(1L, "model.user").hiddenFields()).containsExactly("salary");
        }
        verify(roleMapper).findEffectiveFieldRoleCodes(100L, 2L, 1L, List.of());
    }

    @Test
    void mismatchedCurrentMemberCannotReuseAnAllowedFieldResult() {
        when(metaModelService.getModelFields("model.user"))
                .thenReturn(List.of(field("salary", Map.of("fieldPermission", Map.of("view", List.of("admin"))))));
        when(roleMapper.findEffectiveFieldRoleCodes(100L, 1L, 1L, List.of())).thenReturn(List.of("admin"));
        try (DynamicDataQueryScope ignored = DynamicDataQueryScope.open()) {
            assertThat(service.getFieldPermissions(1L, "model.user").viewableFields()).containsExactly("salary");
            MetaContext.setMemberId(2L);
            assertThat(service.getFieldPermissions(1L, "model.user").hiddenFields()).containsExactly("salary");
            MetaContext.setMemberId(null);
            assertThat(service.getFieldPermissions(1L, "model.user").hiddenFields()).containsExactly("salary");
        }
        verify(roleMapper, times(1)).findEffectiveFieldRoleCodes(100L, 1L, 1L, List.of());
    }

    @Test
    void hidesFieldsWhenNoEffectiveRolesRemain() {
        when(roleMapper.findEffectiveFieldRoleCodes(100L, 1L, 1L, List.of())).thenReturn(List.of());

        Map<String, Object> fp = Map.of("view", List.of("admin"));
        when(metaModelService.getModelFields("model.user"))
                .thenReturn(List.of(field("salary", Map.of("fieldPermission", fp))));

        FieldPermissionSet result = service.getFieldPermissions(1L, "model.user");

        // No matching role → field hidden
        assertThat(result.hiddenFields()).containsExactly("salary");
    }
}
