package com.auraboot.framework.plugin.validation;

import com.auraboot.framework.plugin.dto.imports.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PluginQualityScorerBoundaryTest {
    private final PluginQualityScorer scorer = new PluginQualityScorer();

    @Test
    void missingExecutionMetadataStillReceivesAnIncompleteReadinessScore() {
        var manifest = new PluginManifestExtended(); manifest.setCommands(List.of(command(null)));
        var score = score(manifest);
        assertEquals(0, score.get("agentReadiness"));
        assertEquals(0, score.get("semanticRichness"));
    }

    @Test
    void nullCommandEntriesDoNotDistortValidCommandRates() {
        var command = command("query"); command.setDescription("A query description of sufficient length"); command.setDisplayName("Query");
        var manifest = new PluginManifestExtended(); manifest.setCommands(Arrays.asList(null, command));
        assertEquals(100, score(manifest).get("overall"));
    }

    @Test
    void nullModelAndPageEntriesDoNotDiscardValidPageCoverage() {
        var manifest = new PluginManifestExtended(); manifest.setModels(Arrays.asList(null, model("a", "entity", "A")));
        manifest.setPages(Arrays.asList(null, page("a", "list"), page("a", "detail")));
        assertEquals(100, score(manifest).get("overall"));
    }

    @Test
    void emptyListsAndNonEntityModelsHaveNoMissingPageRequirements() {
        var manifest = new PluginManifestExtended(); manifest.setModels(List.of()); manifest.setCommands(List.of());
        assertEquals(100, score(manifest).get("overall"));
        manifest.setModels(List.of(model("view", "view", "View")));
        assertEquals(100, score(manifest).get("completeness"));
    }

    @Test
    void partialCoverageCountsBothPageKindsAndIgnoresUnboundPages() {
        var manifest = new PluginManifestExtended(); manifest.setModels(List.of(model("a", "entity", "A"), model("b", "ENTITY", " ")));
        manifest.setPages(List.of(page(null, "list"), page("a", "list"), page("a", "detail"), page("b", "workbench")));
        assertEquals(50, score(manifest).get("completeness"));
        assertEquals(50, score(manifest).get("i18n"));
    }

    @Test
    void snakeCaseHintsAndDescriptionBoundaryDetermineSemanticRichness() {
        var first = command("create"); first.setDescription("x".repeat(29));
        first.setUnknownFields(Map.of("agentHint", " ", "agent_hint", "Create record"));
        var second = command("create"); second.setDescription("x".repeat(30)); second.setUnknownFields(Map.of("agentHint", 1));
        var third = command("create"); third.setUnknownFields(Map.of());
        var manifest = new PluginManifestExtended(); manifest.setCommands(List.of(first, second, third));
        assertEquals(66, score(manifest).get("semanticRichness"));
    }

    @Test
    void inputFieldsAndWriteRiskRatesUseTheirOwnDenominators() {
        var first = command("create"); first.setInputFields(List.of("name")); first.setUnknownFields(Map.of("cmd_risk_level", "L2"));
        var second = command("create"); second.setInputFields(List.of());
        var manifest = new PluginManifestExtended(); manifest.setCommands(List.of(first, second, command("query")));
        assertEquals(58, score(manifest).get("agentReadiness"));
    }

    @Test
    void sideEffectsAcceptBothDescriptionKeysAndLongDescriptionButRejectBlankValues() {
        var first = effects(); first.setUnknownFields(Map.of("sideEffectDescription", "Notify"));
        var second = effects(); second.setUnknownFields(Map.of("sideEffectDescription", " ", "side_effect_description", "Publish"));
        var third = effects(); third.setDescription("x".repeat(21));
        var fourth = effects(); fourth.setDescription("x".repeat(20)); fourth.setUnknownFields(Map.of("side_effect_description", 1));
        var manifest = new PluginManifestExtended(); manifest.setCommands(List.of(first, second, third, fourth));
        assertEquals(75, score(manifest).get("safety"));
        fourth.setSideEffects(List.of());
        assertEquals(100, score(manifest).get("safety"));
    }

    @Test
    void bulkDeleteRiskAndDisplayNamesAreScoredWithoutReadInputExemptions() {
        var command = command("bulk_delete"); command.setUnknownFields(Map.of("cmd_risk_level", "L4")); command.setDisplayName(" ");
        var manifest = new PluginManifestExtended(); manifest.setCommands(List.of(command));
        assertEquals(100, score(manifest).get("safety"));
        assertEquals(50, score(manifest).get("agentReadiness"));
        assertEquals(0, score(manifest).get("i18n"));
    }

    private Map<String, Object> score(PluginManifestExtended manifest) { return scorer.computeScore(manifest, PluginValidationResult.empty()); }
    private ModelDefinitionDTO model(String code, String type, String name) {
        var model = new ModelDefinitionDTO(); model.setCode(code); model.setModelType(type); model.setDisplayName(name); return model;
    }
    private PageSchemaDTO page(String model, String kind) {
        var page = new PageSchemaDTO(); page.setModelCode(model); page.setKind(kind); return page;
    }
    private CommandDefinitionDTO command(String type) {
        var command = new CommandDefinitionDTO(); command.setType(type); return command;
    }
    private CommandDefinitionDTO effects() {
        var command = command("create"); command.setSideEffects(List.of(new CommandDefinitionDTO.SideEffectConfig())); return command;
    }
}
