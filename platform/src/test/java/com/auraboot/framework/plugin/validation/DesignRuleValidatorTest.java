package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ModelDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PageSchemaDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DesignRuleValidatorTest {

    private DesignRuleValidator validator;

    @BeforeEach
    void setUp() {
        validator = new DesignRuleValidator();
    }

    private PluginValidationContext ctx(PluginManifestExtended m) {
        return PluginValidationContext.builder().manifest(m).build();
    }

    private ModelDefinitionDTO model(String code, String type) {
        ModelDefinitionDTO m = new ModelDefinitionDTO();
        m.setCode(code);
        m.setModelType(type);
        return m;
    }

    private PageSchemaDTO page(String key, String kind, String modelCode) {
        PageSchemaDTO p = new PageSchemaDTO();
        p.setPageKey(key);
        p.setKind(kind);
        p.setModelCode(modelCode);
        return p;
    }

    private CommandDefinitionDTO cmd(String code, String type) {
        CommandDefinitionDTO c = new CommandDefinitionDTO();
        c.setCode(code);
        c.setType(type);
        return c;
    }

    @Test
    void category_isGovernance() {
        assertThat(validator.category()).isEqualTo("governance");
    }

    @Test
    void entityWithoutListPage_emitsInfo() {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setModels(List.of(model("a", "entity")));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).anyMatch(x -> "D-PAGE-LIST".equals(x.getCode()));
        assertThat(msgs).anyMatch(x -> "D-PAGE-FORM".equals(x.getCode()));
    }

    @Test
    void entityWithBothPages_noPageInfo() {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setModels(List.of(model("a", "entity")));
        m.setPages(List.of(page("a_list", "list", "a"), page("a_form", "form", "a")));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).noneMatch(x -> "D-PAGE-LIST".equals(x.getCode()));
        assertThat(msgs).noneMatch(x -> "D-PAGE-FORM".equals(x.getCode()));
    }

    @Test
    void detailPageSatisfiesFormRequirement() {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setModels(List.of(model("a", "entity")));
        m.setPages(List.of(page("a_list", "list", "a"), page("a_detail", "detail", "a")));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).noneMatch(x -> "D-PAGE-FORM".equals(x.getCode()));
    }

    @Test
    void nonEntityModel_skipped() {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setModels(List.of(model("v1", "view"), model("c1", "config")));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).noneMatch(x -> "D-PAGE-LIST".equals(x.getCode()));
    }

    @Test
    void deleteCommandWithoutRiskLevel_emitsWarning() {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setCommands(List.of(cmd("ns:d", "delete")));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).anyMatch(x -> "D-RISK-DELETE".equals(x.getCode()));
    }

    @Test
    void deleteCommandWithRiskLevel_noWarning() {
        CommandDefinitionDTO c = cmd("ns:d", "delete");
        Map<String, Object> unknown = new HashMap<>();
        unknown.put("cmd_risk_level", "L4");
        c.setUnknownFields(unknown);
        PluginManifestExtended m = new PluginManifestExtended();
        m.setCommands(List.of(c));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).noneMatch(x -> "D-RISK-DELETE".equals(x.getCode()));
    }

    @Test
    void queryCommand_skipsRiskCheck() {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setCommands(List.of(cmd("ns:q", "query")));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).noneMatch(x -> "D-RISK-DELETE".equals(x.getCode()));
    }

    @Test
    void commandWithSideEffectsButNoDescription_emitsWarning() {
        CommandDefinitionDTO c = cmd("ns:create", "create");
        c.setSideEffects(List.of(new CommandDefinitionDTO.SideEffectConfig()));
        c.setDescription("short");
        PluginManifestExtended m = new PluginManifestExtended();
        m.setCommands(List.of(c));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).anyMatch(x -> "D-SIDE-EFFECT-DESC".equals(x.getCode()));
    }

    @Test
    void commandWithSideEffectsAndAgentHint_noWarning() {
        CommandDefinitionDTO c = cmd("ns:create", "create");
        c.setSideEffects(List.of(new CommandDefinitionDTO.SideEffectConfig()));
        c.setDescription("short");
        Map<String, Object> unknown = new HashMap<>();
        unknown.put("agentHint", "explanation");
        c.setUnknownFields(unknown);
        PluginManifestExtended m = new PluginManifestExtended();
        m.setCommands(List.of(c));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).noneMatch(x -> "D-SIDE-EFFECT-DESC".equals(x.getCode()));
    }

    @Test
    void emptyManifest_noMessages() {
        PluginManifestExtended m = new PluginManifestExtended();
        assertThat(validator.validate(ctx(m))).isEmpty();
    }
}
