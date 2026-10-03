package com.auraboot.framework.application.release;

import com.auraboot.framework.plugin.dto.imports.FieldDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ModelDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ModelFieldBindingDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApplicationRuntimeDefinitionCatalogTest {
    private final ApplicationDefinitionMapper definitions = mock(ApplicationDefinitionMapper.class);
    private final ApplicationDefinitionResolver resolver = mock(ApplicationDefinitionResolver.class);
    private final ApplicationRuntimeDefinitionCatalog catalog =
            new ApplicationRuntimeDefinitionCatalog(definitions, resolver, new ObjectMapper());
    private ApplicationDefinitionMapper.ReleaseRow bound;

    @BeforeEach
    void setUp() {
        bound = ApplicationDefinitionResolverTest.release();
        bound.status = "active";
        when(definitions.findBoundRelease(42L, "aura-edu")).thenReturn(bound);
        when(resolver.boundRelease(42L, "aura-edu")).thenReturn(release("active"));
    }

    @Test
    void materializesModelFieldsFromTheExactActiveRelease() {
        var model = catalog.findModel(42L, "aura-edu", "xy_student").orElseThrow();

        assertThat(model.getId()).isNull();
        assertThat(model.getCode()).isEqualTo("xy_student");
        assertThat(model.getTableName()).isEqualTo("mt_xy_student");
        assertThat(model.getStatus()).isEqualTo("published");
        assertThat(model.getFields()).extracting("code")
                .contains("pid", "tenant_id", "row_version", "xy_stu_name");
        assertThat(model.getFields().stream().filter(field -> "xy_stu_name".equals(field.getCode())).findFirst().orElseThrow())
                .satisfies(field -> {
                    assertThat(field.isRequired()).isTrue();
                    assertThat(field.isSearchable()).isTrue();
                    assertThat(field.getColumnName()).isEqualTo("xy_stu_name");
                    assertThat(field.getDisplayName()).isEqualTo("学生姓名");
                });
    }

    @Test
    void keepsShadowAndUnboundTenantsOnTheirExistingReadPath() {
        bound.status = "shadow";
        assertThat(catalog.findModel(42L, "aura-edu", "xy_student")).isEmpty();
        verify(resolver, never()).boundRelease(42L, "aura-edu");
    }

    @Test
    void leavesPlatformModelsOutsideTheApplicationReleaseOnTheLegacyPath() {
        assertThat(catalog.findModel(42L, "aura-edu", "platform_user")).isEmpty();
    }

    @Test
    void materializesCommandsWithoutTenantLocalIds() {
        var command = catalog.findCommand(42L, "aura-edu", "xy_student_create").orElseThrow();
        assertThat(command.getId()).isNull();
        assertThat(command.getPid()).isNull();
        assertThat(command.getModelCode()).isEqualTo("xy_student");
        assertThat(command.getType()).isEqualTo("create");
        assertThat(command.getExecutionConfig()).contains("autoSetFields");
        assertThat(command.getLocalizedDescriptions()).containsEntry("en-US", "Create a student");
        assertThat(catalog.commandsForModel(42L, "aura-edu", "xy_student"))
                .extracting("code").containsExactly("xy_student_create");
    }

    @Test
    void resolvesRolePermissionsAndBuildsMenuHierarchyFromStableCodes() {
        assertThat(catalog.permissionsForRoles(42L, "aura-edu", Set.of("xy_school_admin")))
                .containsExactlyInAnyOrder("xy.school.manage", "model.xy_student.read");
        assertThat(catalog.permissionCodes(42L, "aura-edu"))
                .containsExactlyInAnyOrder(
                        "xy.school.manage", "xy.petops.manage",
                        "model.xy_student.read", "model.xy_student.create", "model.xy_student.update",
                        "model.xy_student.delete", "model.xy_student.export", "model.xy_student.import");
        var roots = catalog.menuTree(42L, "aura-edu");
        assertThat(roots).extracting("code").containsExactly("xy_root");
        assertThat(roots.getFirst().getId()).isNull();
        assertThat(roots.getFirst().getPid()).isNull();
        assertThat(roots.getFirst().getChildren()).extracting("code").containsExactly("xy_student_menu");
    }

    private ApplicationDefinitionResolver.ReleaseDefinitions release(String status) {
        var selection = new ApplicationDefinitionResolver.ReleaseSelection(
                bound.applicationId, bound.code, bound.releaseId, bound.releaseDigest,
                bound.sourceLockIdentity, status, bound.bindingVersion);
        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setPluginId("com.auraboot.edu");
        manifest.setNamespace("edu");
        manifest.setModels(List.of(ModelDefinitionDTO.builder()
                .code("xy_student").displayNameZhCN("学生").modelType("entity").build()));
        manifest.setFields(List.of(FieldDefinitionDTO.builder()
                .code("xy_stu_name").displayNameZhCN("学生姓名").dataType("string")
                .constraints(FieldDefinitionDTO.FieldConstraints.builder().required(true).maxLength(80).build())
                .feature(Map.of("searchable", true)).build()));
        manifest.setModelFieldBindings(List.of(ModelFieldBindingDTO.builder()
                .modelCode("xy_student").fieldCode("xy_stu_name").sequence(1)
                .required(true).displayConfig(Map.of("searchable", true)).build()));
        manifest.setCommands(List.of(com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO.builder()
                .code("xy_student_create").modelCode("xy_student").type("create")
                .displayNameZhCN("创建学生")
                .extension(Map.of("localizedDescriptions", Map.of("en-US", "Create a student", "zh-CN", "Create student source")))
                .autoSetFields(Map.of("xy_stu_code", Map.of("strategy", "AUTO_GENERATE"))).build()));
        manifest.setRoles(List.of(com.auraboot.framework.plugin.dto.imports.RoleDefinitionDTO.builder()
                .code("xy_school_admin").nameZhCN("学校管理员")
                .permissions(List.of("xy.school.manage", "model.xy_student.read")).build()));
        manifest.setPermissions(List.of(
                com.auraboot.framework.plugin.dto.imports.PermissionDefinitionDTO.builder()
                        .code("xy.school.manage").nameZhCN("学校管理").build(),
                com.auraboot.framework.plugin.dto.imports.PermissionDefinitionDTO.builder()
                        .code("xy.petops.manage").nameZhCN("内容运营").build()));
        manifest.setMenus(List.of(
                com.auraboot.framework.plugin.dto.imports.MenuDefinitionDTO.builder()
                        .code("xy_root").nameZhCN("校园").type(0).orderNo(1).build(),
                com.auraboot.framework.plugin.dto.imports.MenuDefinitionDTO.builder()
                        .code("xy_student_menu").nameZhCN("学生").type(1).parentCode("xy_root")
                        .path("/p/xy_student").permissionCode("model.xy_student.read").orderNo(1).build()));
        return new ApplicationDefinitionResolver.ReleaseDefinitions(selection, List.of(
                new ApplicationDefinitionResolver.ComponentDefinitions(
                        "edu-core", "1.0.0", "sha256:" + "c".repeat(64), manifest)));
    }
}
