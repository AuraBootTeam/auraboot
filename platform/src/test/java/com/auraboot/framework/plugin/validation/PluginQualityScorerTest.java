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

class PluginQualityScorerTest {

    private PluginQualityScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new PluginQualityScorer();
    }

    private ModelDefinitionDTO entity(String code, String displayName) {
        ModelDefinitionDTO m = new ModelDefinitionDTO();
        m.setCode(code);
        m.setModelType("entity");
        m.setDisplayName(displayName);
        return m;
    }

    private CommandDefinitionDTO cmd(String code, String type, String desc, String displayName) {
        CommandDefinitionDTO c = new CommandDefinitionDTO();
        c.setCode(code);
        c.setType(type);
        c.setDescription(desc);
        c.setDisplayName(displayName);
        return c;
    }

    private PageSchemaDTO page(String key, String kind, String mc) {
        PageSchemaDTO p = new PageSchemaDTO();
        p.setPageKey(key);
        p.setKind(kind);
        p.setModelCode(mc);
        return p;
    }

    private PluginValidationResult emptyResult() {
        return PluginValidationResult.builder()
                .valid(true)
                .errorCount(0)
                .warningCount(0)
                .infoCount(0)
                .build();
    }

    @Test
    void emptyManifest_perfectScore() {
        PluginManifestExtended m = new PluginManifestExtended();
        Map<String, Object> score = scorer.computeScore(m, emptyResult());
        assertThat((int) score.get("overall")).isEqualTo(100);
        assertThat((int) score.get("completeness")).isEqualTo(100);
        assertThat((int) score.get("semanticRichness")).isEqualTo(100);
        assertThat((int) score.get("agentReadiness")).isEqualTo(100);
        assertThat((int) score.get("safety")).isEqualTo(100);
        assertThat((int) score.get("i18n")).isEqualTo(100);
    }

    @Test
    void entityModelWithoutPages_completenessZero() {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setModels(List.of(entity("a", "A")));
        Map<String, Object> score = scorer.computeScore(m, emptyResult());
        assertThat((int) score.get("completeness")).isEqualTo(0);
    }

    @Test
    void entityModelWithBothPages_completenessFull() {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setModels(List.of(entity("a", "A")));
        m.setPages(List.of(page("a_list", "list", "a"), page("a_form", "form", "a")));
        Map<String, Object> score = scorer.computeScore(m, emptyResult());
        assertThat((int) score.get("completeness")).isEqualTo(100);
    }

    @Test
    void semanticRichness_descriptionLengthGate() {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setCommands(List.of(
                cmd("ns:a", "create", "short", "A"),
                cmd("ns:b", "create",
                        "Long enough description over thirty characters in length always.", "B")));
        Map<String, Object> score = scorer.computeScore(m, emptyResult());
        // 1 of 2 has a long enough description
        assertThat((int) score.get("semanticRichness")).isEqualTo(50);
    }

    @Test
    void semanticRichness_agentHintCountsAsRich() {
        CommandDefinitionDTO c = cmd("ns:a", "create", "short", "A");
        Map<String, Object> unknown = new HashMap<>();
        unknown.put("agentHint", "hint here");
        c.setUnknownFields(unknown);
        PluginManifestExtended m = new PluginManifestExtended();
        m.setCommands(List.of(c));
        Map<String, Object> score = scorer.computeScore(m, emptyResult());
        assertThat((int) score.get("semanticRichness")).isEqualTo(100);
    }

    @Test
    void agentReadiness_inputAndRiskTracked() {
        // create cmd with input + risk level
        CommandDefinitionDTO c1 = cmd("ns:a", "create", "x", "A");
        c1.setInputSchema(Map.of("foo", "bar"));
        Map<String, Object> u1 = new HashMap<>();
        u1.put("cmd_risk_level", "L2");
        c1.setUnknownFields(u1);
        PluginManifestExtended m = new PluginManifestExtended();
        m.setCommands(List.of(c1));
        Map<String, Object> score = scorer.computeScore(m, emptyResult());
        assertThat((int) score.get("agentReadiness")).isEqualTo(100);
    }

    @Test
    void agentReadiness_queryCommandsGetFreePass() {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setCommands(List.of(cmd("ns:q", "query", "x", "Q")));
        Map<String, Object> score = scorer.computeScore(m, emptyResult());
        // query gets free pass on input + skip on risk
        assertThat((int) score.get("agentReadiness")).isEqualTo(100);
    }

    @Test
    void safety_deleteWithoutRisk_drops() {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setCommands(List.of(cmd("ns:d", "delete", "x", "D")));
        Map<String, Object> score = scorer.computeScore(m, emptyResult());
        assertThat((int) score.get("safety")).isEqualTo(0);
    }

    @Test
    void safety_deleteWithRisk_full() {
        CommandDefinitionDTO c = cmd("ns:d", "delete", "x", "D");
        Map<String, Object> u = new HashMap<>();
        u.put("cmd_risk_level", "L4");
        c.setUnknownFields(u);
        PluginManifestExtended m = new PluginManifestExtended();
        m.setCommands(List.of(c));
        Map<String, Object> score = scorer.computeScore(m, emptyResult());
        assertThat((int) score.get("safety")).isEqualTo(100);
    }

    @Test
    void i18n_displayNameRate() {
        PluginManifestExtended m = new PluginManifestExtended();
        m.setModels(List.of(entity("a", "A"), entity("b", null)));
        m.setCommands(List.of(cmd("ns:c", "create", "x", "Create"), cmd("ns:d", "delete", "x", null)));
        Map<String, Object> score = scorer.computeScore(m, emptyResult());
        // 2 of 4 have displayName
        assertThat((int) score.get("i18n")).isEqualTo(50);
    }

    @Test
    void overallIncorporatesValidationCounts() {
        PluginManifestExtended m = new PluginManifestExtended();
        PluginValidationResult result = PluginValidationResult.builder()
                .valid(false)
                .errorCount(3)
                .warningCount(2)
                .infoCount(1)
                .build();
        Map<String, Object> score = scorer.computeScore(m, result);
        assertThat((int) score.get("errors")).isEqualTo(3);
        assertThat((int) score.get("warnings")).isEqualTo(2);
        assertThat((int) score.get("infos")).isEqualTo(1);
    }
}
