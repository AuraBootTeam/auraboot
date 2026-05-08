package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ModelDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ModelFieldBindingDTO;
import com.auraboot.framework.plugin.dto.imports.PageSchemaDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CompletenessValidatorTest {

    private CompletenessValidator validator;

    @BeforeEach
    void setUp() {
        validator = new CompletenessValidator();
    }

    private PluginValidationContext ctx(PluginManifestExtended m) {
        return PluginValidationContext.builder().manifest(m).installedFieldCodes(Set.of()).build();
    }

    private PluginValidationContext ctxWithFields(PluginManifestExtended m, Set<String> fields) {
        return PluginValidationContext.builder().manifest(m).installedFieldCodes(fields).build();
    }

    private ModelDefinitionDTO model(String code) {
        ModelDefinitionDTO m = new ModelDefinitionDTO();
        m.setCode(code);
        return m;
    }

    private PageSchemaDTO page(String key, String kind, String modelCode) {
        PageSchemaDTO p = new PageSchemaDTO();
        p.setPageKey(key);
        p.setKind(kind);
        p.setModelCode(modelCode);
        return p;
    }

    private CommandDefinitionDTO cmd(String code, String type, String modelCode) {
        CommandDefinitionDTO c = new CommandDefinitionDTO();
        c.setCode(code);
        c.setType(type);
        c.setModelCode(modelCode);
        return c;
    }

    private ModelFieldBindingDTO binding(String mc, String fc) {
        ModelFieldBindingDTO b = new ModelFieldBindingDTO();
        b.setModelCode(mc);
        b.setFieldCode(fc);
        return b;
    }

    @Test
    void category_isSemantic() {
        assertThat(validator.category()).isEqualTo("semantic");
    }

    @Test
    void noModels_noMessages() {
        PluginManifestExtended m = new PluginManifestExtended();
        assertThat(validator.validate(ctx(m))).isEmpty();
    }

    @Test
    void modelWithListAndFormPagesAndCreate_noWarning() {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setModels(List.of(model("a")));
        m.setPages(List.of(page("a_list", "list", "a"), page("a_form", "form", "a")));
        m.setCommands(List.of(cmd("ns:create_a", "create", "a")));
        m.setModelFieldBindings(List.of(binding("a", "f")));
        assertThat(validator.validate(ctx(m))).isEmpty();
    }

    @Test
    void listWithoutForm_emitsWarning() {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setModels(List.of(model("a")));
        m.setPages(List.of(page("a_list", "list", "a")));
        m.setCommands(List.of(cmd("ns:create_a", "create", "a")));
        m.setModelFieldBindings(List.of(binding("a", "f")));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).anyMatch(x -> x.isWarning() && x.getMessage().contains("no form page"));
    }

    @Test
    void listWithoutCreate_emitsWarning() {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setModels(List.of(model("a")));
        m.setPages(List.of(page("a_list", "list", "a"), page("a_form", "form", "a")));
        m.setModelFieldBindings(List.of(binding("a", "f")));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).anyMatch(x -> x.getMessage().contains("no create command"));
    }

    @Test
    void modelWithoutBindings_emitsWarning() {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setModels(List.of(model("a")));
        m.setPages(List.of(page("a_list", "list", "a"), page("a_form", "form", "a")));
        m.setCommands(List.of(cmd("ns:create_a", "create", "a")));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).anyMatch(x -> x.getMessage().contains("no field bindings"));
    }

    @Test
    void modelWithoutBindings_butInstalledFieldsExist_noWarning() {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setModels(List.of(model("a")));
        m.setPages(List.of(page("a_list", "list", "a"), page("a_form", "form", "a")));
        m.setCommands(List.of(cmd("ns:create_a", "create", "a")));
        var msgs = validator.validate(ctxWithFields(m, Set.of("a.field1")));
        assertThat(msgs).noneMatch(x -> x.getMessage().contains("no field bindings"));
    }
}
