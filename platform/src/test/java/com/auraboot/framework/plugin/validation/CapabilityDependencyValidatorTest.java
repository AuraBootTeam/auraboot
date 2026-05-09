package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.PluginManifest;
import com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ModelDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.NamedQueryDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityDependencyValidatorTest {

    private CapabilityDependencyValidator validator;

    @BeforeEach
    void setUp() {
        validator = new CapabilityDependencyValidator();
    }

    private PluginValidationContext ctx(PluginManifestExtended m) {
        return PluginValidationContext.builder()
                .manifest(m)
                .installedModelCodes(Set.of())
                .installedCommandCodes(Set.of())
                .installedNamedQueryCodes(Set.of())
                .build();
    }

    private PluginManifest.CapabilityRequirement req(String type, String code, boolean optional) {
        return new PluginManifest.CapabilityRequirement(type, code, optional);
    }

    private PluginManifestExtended manifestWithRequires(List<PluginManifest.CapabilityRequirement> reqs) {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setRequires(reqs);
        return m;
    }

    @Test
    void category_isSemantic() {
        assertThat(validator.category()).isEqualTo("semantic");
    }

    @Test
    void noRequirements_returnsEmpty() {
        PluginManifestExtended m = new PluginManifestExtended();
        assertThat(validator.validate(ctx(m))).isEmpty();
    }

    @Test
    void modelSatisfiedByPluginManifest_noError() {
        PluginManifestExtended m = manifestWithRequires(List.of(req("model", "ns_m", false)));
        ModelDefinitionDTO mm = new ModelDefinitionDTO();
        mm.setCode("ns_m");
        m.setModels(List.of(mm));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).isEmpty();
    }

    @Test
    void modelSatisfiedByInstalled_noError() {
        PluginManifestExtended m = manifestWithRequires(List.of(req("model", "ext_m", false)));
        var msgs = validator.validate(PluginValidationContext.builder()
                .manifest(m)
                .installedModelCodes(Set.of("ext_m"))
                .installedCommandCodes(Set.of())
                .installedNamedQueryCodes(Set.of())
                .build());
        assertThat(msgs).isEmpty();
    }

    @Test
    void missingRequiredModel_emitsError() {
        PluginManifestExtended m = manifestWithRequires(List.of(req("model", "missing", false)));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).anyMatch(x -> "S-CAP-MISSING".equals(x.getCode()) && x.isError());
    }

    @Test
    void missingOptional_emitsWarning() {
        PluginManifestExtended m = manifestWithRequires(List.of(req("model", "missing", true)));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).anyMatch(x -> "S-CAP-OPTIONAL".equals(x.getCode()) && x.isWarning());
        assertThat(msgs).noneMatch(x -> "S-CAP-MISSING".equals(x.getCode()));
    }

    @Test
    void commandSatisfied_noError() {
        PluginManifestExtended m = manifestWithRequires(List.of(req("command", "ns:cmd", false)));
        CommandDefinitionDTO c = new CommandDefinitionDTO();
        c.setCode("ns:cmd");
        m.setCommands(List.of(c));
        assertThat(validator.validate(ctx(m))).isEmpty();
    }

    @Test
    void querySatisfied_noError() {
        PluginManifestExtended m = manifestWithRequires(List.of(req("query", "ns_q", false)));
        NamedQueryDefinitionDTO q = new NamedQueryDefinitionDTO();
        q.setCode("ns_q");
        m.setNamedQueries(List.of(q));
        assertThat(validator.validate(ctx(m))).isEmpty();
    }

    @Test
    void apiAndAutomationAlwaysSatisfied() {
        PluginManifestExtended m = manifestWithRequires(List.of(
                req("api", "x", false), req("automation", "y", false)));
        assertThat(validator.validate(ctx(m))).isEmpty();
    }

    @Test
    void unknownType_emitsWarning() {
        PluginManifestExtended m = manifestWithRequires(List.of(req("weird", "code", false)));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).anyMatch(x -> "S-CAP-TYPE".equals(x.getCode()) && x.isWarning());
    }

    @Test
    void requirementWithNullFields_skipped() {
        PluginManifestExtended m = manifestWithRequires(java.util.Arrays.asList(
                (PluginManifest.CapabilityRequirement) null,
                req(null, "code", false),
                req("model", null, false)));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).isEmpty();
    }
}
