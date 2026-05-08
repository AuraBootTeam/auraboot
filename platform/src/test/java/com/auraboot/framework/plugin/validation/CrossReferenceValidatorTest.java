package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.FieldDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.MenuDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ModelDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ModelFieldBindingDTO;
import com.auraboot.framework.plugin.dto.imports.PermissionDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CrossReferenceValidatorTest {

    private CrossReferenceValidator validator;

    @BeforeEach
    void setUp() {
        validator = new CrossReferenceValidator();
    }

    private PluginValidationContext ctx(PluginManifestExtended manifest, Set<String> models,
                                         Set<String> fields, Set<String> perms) {
        return PluginValidationContext.builder()
                .pluginId("p")
                .namespace("ns")
                .manifest(manifest)
                .installedModelCodes(models)
                .installedFieldCodes(fields)
                .installedPermissionCodes(perms)
                .build();
    }

    private CommandDefinitionDTO command(String code, String modelCode) {
        CommandDefinitionDTO c = new CommandDefinitionDTO();
        c.setCode(code);
        c.setModelCode(modelCode);
        return c;
    }

    private ModelDefinitionDTO model(String code) {
        ModelDefinitionDTO m = new ModelDefinitionDTO();
        m.setCode(code);
        return m;
    }

    private FieldDefinitionDTO field(String code) {
        FieldDefinitionDTO f = new FieldDefinitionDTO();
        f.setCode(code);
        f.setDataType("string");
        return f;
    }

    private ModelFieldBindingDTO binding(String modelCode, String fieldCode) {
        ModelFieldBindingDTO b = new ModelFieldBindingDTO();
        b.setModelCode(modelCode);
        b.setFieldCode(fieldCode);
        return b;
    }

    private PermissionDefinitionDTO permission(String code) {
        PermissionDefinitionDTO p = new PermissionDefinitionDTO();
        p.setCode(code);
        return p;
    }

    private MenuDefinitionDTO menu(String code, String permCode) {
        MenuDefinitionDTO m = new MenuDefinitionDTO();
        m.setCode(code);
        m.setPermissionCode(permCode);
        return m;
    }

    @Test
    void category_returnsSemantic() {
        assertThat(validator.category()).isEqualTo("semantic");
    }

    @Test
    void emptyManifest_noMessages() {
        PluginManifestExtended manifest = new PluginManifestExtended();
        var msgs = validator.validate(ctx(manifest, Set.of(), Set.of(), Set.of()));
        assertThat(msgs).isEmpty();
    }

    @Test
    void commandReferencingPluginModel_noError() {
        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setModels(List.of(model("ns_m")));
        manifest.setCommands(List.of(command("ns:c", "ns_m")));
        var msgs = validator.validate(ctx(manifest, Set.of(), Set.of(), Set.of()));
        assertThat(msgs).noneMatch(m -> "S-REF-MODEL".equals(m.getCode()));
    }

    @Test
    void commandReferencingInstalledModel_noError() {
        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setCommands(List.of(command("ns:c", "external_m")));
        var msgs = validator.validate(ctx(manifest, Set.of("external_m"), Set.of(), Set.of()));
        assertThat(msgs).noneMatch(m -> "S-REF-MODEL".equals(m.getCode()));
    }

    @Test
    void commandReferencingMissingModel_emitsError() {
        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setCommands(List.of(command("ns:c", "missing")));
        var msgs = validator.validate(ctx(manifest, Set.of(), Set.of(), Set.of()));
        assertThat(msgs).anyMatch(m -> "S-REF-MODEL".equals(m.getCode()) && m.isError()
                && m.getMessage().contains("missing"));
    }

    @Test
    void commandWithNullModel_skipped() {
        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setCommands(List.of(command("ns:c", null)));
        var msgs = validator.validate(ctx(manifest, Set.of(), Set.of(), Set.of()));
        assertThat(msgs).noneMatch(m -> "S-REF-MODEL".equals(m.getCode()));
    }

    @Test
    void bindingMissingModelAndField_emitsTwoErrors() {
        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setModelFieldBindings(List.of(binding("nope_m", "nope_f")));
        var msgs = validator.validate(ctx(manifest, Set.of(), Set.of(), Set.of()));
        assertThat(msgs).anyMatch(m -> "S-REF-BINDING-MODEL".equals(m.getCode()));
        assertThat(msgs).anyMatch(m -> "S-REF-BINDING-FIELD".equals(m.getCode()));
    }

    @Test
    void bindingValidWhenModelAndFieldDeclaredInPlugin() {
        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setModels(List.of(model("ns_m")));
        manifest.setFields(List.of(field("ns_f")));
        manifest.setModelFieldBindings(List.of(binding("ns_m", "ns_f")));
        var msgs = validator.validate(ctx(manifest, Set.of(), Set.of(), Set.of()));
        assertThat(msgs).isEmpty();
    }

    @Test
    void menuPermissionMissing_emitsWarning() {
        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setMenus(List.of(menu("m1", "missing_perm")));
        var msgs = validator.validate(ctx(manifest, Set.of(), Set.of(), Set.of()));
        assertThat(msgs).anyMatch(m -> "S-REF-PERM".equals(m.getCode()) && m.isWarning());
    }

    @Test
    void menuPermissionValid_noWarning() {
        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setPermissions(List.of(permission("ns:read")));
        manifest.setMenus(List.of(menu("m1", "ns:read")));
        var msgs = validator.validate(ctx(manifest, Set.of(), Set.of(), Set.of()));
        assertThat(msgs).noneMatch(m -> "S-REF-PERM".equals(m.getCode()));
    }

    @Test
    void menuWithNullPermission_skipped() {
        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setMenus(List.of(menu("m1", null)));
        var msgs = validator.validate(ctx(manifest, Set.of(), Set.of(), Set.of()));
        assertThat(msgs).isEmpty();
    }

    @Test
    void nullEntriesInLists_handledGracefully() {
        PluginManifestExtended manifest = new PluginManifestExtended();
        manifest.setModels(java.util.Arrays.asList((ModelDefinitionDTO) null));
        manifest.setCommands(java.util.Arrays.asList((CommandDefinitionDTO) null));
        manifest.setModelFieldBindings(java.util.Arrays.asList((ModelFieldBindingDTO) null));
        manifest.setMenus(java.util.Arrays.asList((MenuDefinitionDTO) null));
        var msgs = validator.validate(ctx(manifest, Set.of(), Set.of(), Set.of()));
        assertThat(msgs).isEmpty();
    }
}
