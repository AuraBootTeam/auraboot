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
    void suppliesRenderingMetadataWithoutTenantLocalModelOrFieldIds() {
        var fields = catalog.findFieldMetadata(42L, "aura-edu", "xy_student").orElseThrow();
        var name = fields.stream().filter(field -> "xy_stu_name".equals(field.getCode())).findFirst().orElseThrow();
        assertThat(name.getId()).isNull();
        assertThat(name.getPid()).isNull();
        assertThat(name.getDisplayName()).isEqualTo("学生姓名");
        assertThat(name.getRequired()).isTrue();
        assertThat(name.getFieldOrder()).isEqualTo(1);
        assertThat(name.getDataType()).isEqualTo("string");
        assertThat(catalog.findFieldMetadata(42L, "aura-edu", "platform_user")).isEmpty();
    }

    @Test
    void materializesReleaseDictionaryWithoutTenantLocalIds() {
        var source = release("active");
        source.components().getFirst().manifest().setDicts(List.of(
                com.auraboot.framework.plugin.dto.imports.DictDefinitionDTO.builder().code("xy_subject")
                        .nameZhCN("学科").items(List.of(
                                com.auraboot.framework.plugin.dto.imports.DictDefinitionDTO.DictItemDTO.builder()
                                        .value("math").labelZhCN("数学").labelEn("Mathematics").sortNo(1).build())).build()));
        when(resolver.boundRelease(42L, "aura-edu")).thenReturn(source);
        var dict = catalog.findDict(42L, "aura-edu", "xy_subject").orElseThrow();
        assertThat(dict.getId()).isNull();
        assertThat(dict.getPid()).isNull();
        assertThat(dict.getItems()).hasSize(1);
        assertThat(dict.getItems().getFirst().getLabel()).isEqualTo("数学");
        assertThat(dict.getItems().getFirst().getExtra()).containsKey("labels");
        assertThat(dict.getExtendedProps().get("releaseId").asText()).isEqualTo(bound.releaseId);
        assertThat(catalog.findDict(42L, "aura-edu", "platform_status")).isEmpty();
    }

    @Test
    void keepsShadowAndUnboundTenantsOnTheirExistingReadPath() {
        bound.status = "shadow";
        assertThat(catalog.findModel(42L, "aura-edu", "xy_student")).isEmpty();
        verify(resolver, never()).boundRelease(42L, "aura-edu");
    }

    @Test
    void projectsReleaseDashboardsWithoutInventingPersistentIds() {
        var source = release("active");
        source.components().getFirst().manifest().setDashboards(List.of(
                com.auraboot.framework.plugin.dto.imports.DashboardDefinitionDTO.builder()
                        .code("class_overview").title("Class overview").widgets(List.of(Map.of("id", "count")))
                        .extension(Map.of("workbenchContribution", Map.of("enabled", true))).build()));
        when(resolver.boundRelease(42L, "aura-edu")).thenReturn(source);
        var dashboard = catalog.dashboards(42L, "aura-edu").orElseThrow().getFirst();
        assertThat(dashboard.getTenantId()).isEqualTo(42L);
        assertThat(dashboard.getId()).isNull();
        assertThat(dashboard.getPid()).isNull();
        assertThat(dashboard.getCode()).isEqualTo("class_overview");
        assertThat(dashboard.getWidgets().get(0).get("id").asText()).isEqualTo("count");
        assertThat(dashboard.getExtension().get("releaseId").asText()).isEqualTo(bound.releaseId);
        assertThat(dashboard.getLayoutConfig().get("columns").asInt()).isEqualTo(12);
    }

    @Test
    void rejectsAmbiguousAndNonGlobalReleaseDashboards() {
        var source = release("active");
        var dashboard = com.auraboot.framework.plugin.dto.imports.DashboardDefinitionDTO.builder()
                .code("count").title("Count").widgets(List.of(Map.of("id", "count"))).build();
        source.components().getFirst().manifest().setDashboards(List.of(dashboard, dashboard));
        when(resolver.boundRelease(42L, "aura-edu")).thenReturn(source);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> catalog.dashboards(42L, "aura-edu"))
                .hasMessageContaining("unique");
        source.components().getFirst().manifest().setDashboards(List.of(dashboard));
        dashboard.setScope("personal");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> catalog.dashboards(42L, "aura-edu"))
                .hasMessageContaining("global scope");
    }

    @Test
    void projectsQueryAndWhitelistFromOneBindingAndRejectsMissingFields() {
        var source = release("active");
        var field = new com.auraboot.framework.meta.dto.NamedQueryFieldRequest();
        field.setFieldCode("studentCount");
        field.setColumnExpr("student_count");
        field.setDataType("number");
        field.setOperators(List.of("eq", "gt"));
        var query = com.auraboot.framework.plugin.dto.imports.NamedQueryDefinitionDTO.builder()
                .code("class_count").titleZhCN("学生人数").fromSql("mt_xy_student")
                .resourceCode("xy_student").actionCode("read").status("published").fields(List.of(field)).build();
        source.components().getFirst().manifest().setNamedQueries(List.of(query));
        when(resolver.boundRelease(42L, "aura-edu")).thenReturn(source);
        var definition = catalog.findNamedQuery(42L, "aura-edu", "class_count").orElseThrow();
        assertThat(definition.query().getId()).isNull();
        assertThat(definition.query().getPid()).isNull();
        assertThat(definition.query().getTitle()).isEqualTo("学生人数");
        assertThat(definition.query().getResourceCode()).isEqualTo("xy_student");
        assertThat(definition.fields()).hasSize(1);
        assertThat(definition.fields().getFirst().getOperatorList()).containsExactly("eq", "gt");
        assertThat(definition.fields().getFirst().getId()).isNull();
        query.setFields(List.of());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> catalog.findNamedQuery(42L, "aura-edu", "class_count"))
                .hasMessageContaining("explicit field whitelist");
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
