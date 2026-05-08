package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.CommandDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentReadinessValidatorTest {

    private AgentReadinessValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AgentReadinessValidator();
    }

    private PluginValidationContext ctx(PluginManifestExtended m) {
        return PluginValidationContext.builder().manifest(m).build();
    }

    private CommandDefinitionDTO cmd(String code, String type, String desc) {
        CommandDefinitionDTO c = new CommandDefinitionDTO();
        c.setCode(code);
        c.setType(type);
        c.setDescription(desc);
        return c;
    }

    @Test
    void category_isGovernance() {
        assertThat(validator.category()).isEqualTo("governance");
    }

    @Test
    void noCommands_returnsEmpty() {
        PluginManifestExtended m = new PluginManifestExtended();
        assertThat(validator.validate(ctx(m))).isEmpty();
    }

    @Test
    void shortDescriptionWithoutAgentHint_emitsInfo() {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setCommands(List.of(cmd("ns:c", "create", "short")));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).anyMatch(x -> "A-DESC-QUALITY".equals(x.getCode()));
    }

    @Test
    void longDescription_noDescQualityInfo() {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setCommands(List.of(cmd("ns:c", "create",
                "A reasonably long description well past thirty characters in length.")));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).noneMatch(x -> "A-DESC-QUALITY".equals(x.getCode()));
    }

    @Test
    void agentHintPresent_skipsDescQualityInfo() {
        CommandDefinitionDTO c = cmd("ns:c", "create", "x");
        Map<String, Object> unknown = new HashMap<>();
        unknown.put("agentHint", "use this for X");
        c.setUnknownFields(unknown);
        PluginManifestExtended m = new PluginManifestExtended();
        m.setCommands(List.of(c));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).noneMatch(x -> "A-DESC-QUALITY".equals(x.getCode()));
    }

    @Test
    void stateTransitionMissingFields_emitsWarning() {
        CommandDefinitionDTO c = cmd("ns:approve", "state_transition",
                "Approve the record per workflow.");
        PluginManifestExtended m = new PluginManifestExtended();
        m.setCommands(List.of(c));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).anyMatch(x -> "A-STATE-INCOMPLETE".equals(x.getCode()) && x.isWarning());
    }

    @Test
    void stateTransitionWithFullSpec_noWarning() {
        CommandDefinitionDTO c = new CommandDefinitionDTO();
        c.setCode("ns:approve");
        c.setType("state_transition");
        c.setDescription("Approve the record");
        c.setStateField("status");
        c.setFromStates(List.of("draft"));
        c.setToState("approved");
        PluginManifestExtended m = new PluginManifestExtended();
        m.setCommands(List.of(c));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).noneMatch(x -> "A-STATE-INCOMPLETE".equals(x.getCode()));
    }

    @Test
    void nonQueryCommandWithoutInputSchema_emitsInputSchemaInfo() {
        CommandDefinitionDTO c = cmd("ns:c", "create", "Long enough description for description rule pass.");
        PluginManifestExtended m = new PluginManifestExtended();
        m.setCommands(List.of(c));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).anyMatch(x -> "A-INPUT-SCHEMA".equals(x.getCode()));
    }

    @Test
    void deleteCommand_skipsInputSchemaCheck() {
        CommandDefinitionDTO c = cmd("ns:d", "delete", "Long enough description for description rule pass.");
        PluginManifestExtended m = new PluginManifestExtended();
        m.setCommands(List.of(c));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).noneMatch(x -> "A-INPUT-SCHEMA".equals(x.getCode()));
    }

    @Test
    void hintCoverageBelow50_emitsCoverageInfo() {
        CommandDefinitionDTO c1 = cmd("ns:a", "create", "Long enough description over thirty chars please.");
        CommandDefinitionDTO c2 = cmd("ns:b", "create", "Long enough description over thirty chars please.");
        c1.setInputSchema(Map.of("x", 1));
        c2.setInputSchema(Map.of("x", 1));
        PluginManifestExtended m = new PluginManifestExtended();
        m.setCommands(List.of(c1, c2));
        var msgs = validator.validate(ctx(m));
        assertThat(msgs).anyMatch(x -> "A-HINT-COVERAGE".equals(x.getCode()));
    }
}
