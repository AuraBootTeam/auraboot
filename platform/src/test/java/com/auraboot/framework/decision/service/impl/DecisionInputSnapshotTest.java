package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.dto.DecisionFactCatalogDTO;
import com.auraboot.framework.decision.dto.DecisionFactDTO;
import com.auraboot.framework.decision.dto.DecisionFactEntityDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionInputSnapshotTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void capturesTheEvaluatedValueNotTheDictionaryCatalogueAndDeduplicatesAliases() {
        var metadata = new LinkedHashMap<String, Object>();
        var amount = fact("amount", false);
        metadata.put("amount", amount); metadata.put("record.data.amount", amount);
        var values = DecisionInputSnapshot.capture(DecisionContext.builder().record(Map.of("amount", 68000, "secret", "never-log")).build(), metadata);
        assertThat(values).hasSize(1);
        assertThat(mapper.valueToTree(values).path("record.data.amount").path("value").asInt()).isEqualTo(68000);
        assertThat(values.toString()).doesNotContain("never-log", "secret");
    }

    @Test void distinguishesNullMissingRedactedAndUnboundedValuesWithoutPersistingSecrets() {
        var input = new LinkedHashMap<String, Object>();
        input.put("nullValue", null); input.put("salary", "sensitive-value"); input.put("nested", Map.of("secret", "nested-secret")); input.put("long", "x".repeat(513));
        var metadata = new LinkedHashMap<String, Object>();
        for (String key : List.of("nullValue", "missing", "salary", "nested", "long")) metadata.put(key, fact(key, key.equals("salary")));
        var json = mapper.valueToTree(DecisionInputSnapshot.capture(DecisionContext.builder().record(input).build(), metadata));
        assertThat(json.path("record.data.nullValue").path("state").asText()).isEqualTo("NULL");
        assertThat(json.path("record.data.missing").path("state").asText()).isEqualTo("MISSING");
        assertThat(json.path("record.data.salary").path("state").asText()).isEqualTo("REDACTED");
        assertThat(json.path("record.data.nested").path("state").asText()).isEqualTo("OMITTED");
        assertThat(json.path("record.data.long").path("state").asText()).isEqualTo("OMITTED");
        assertThat(json.toString()).doesNotContain("sensitive-value", "nested-secret", "x".repeat(513));
    }

    @Test void currentViewerPermissionRemovesFieldsAndTheirReferenceLabelsWithoutMutatingStoredEvidence() {
        var metadata = Map.<String,Object>of("amount", fact("amount", false), "salary", fact("salary", false));
        var original = mapper.createObjectNode();
        original.set("inputSnapshot", mapper.valueToTree(DecisionInputSnapshot.capture(DecisionContext.builder().record(Map.of("amount", 12, "salary", 99999)).build(), metadata)));
        original.set("factMetadata", mapper.valueToTree(metadata));
        var visible = DecisionInputSnapshot.project(original, mapper, model -> catalog(false));
        assertThat(visible.path("inputSnapshot").size()).isEqualTo(1);
        assertThat(visible.toString()).doesNotContain("99999", "salary");
        assertThat(original.path("inputSnapshot").size()).isEqualTo(2);
    }

    @Test void currentMaskingRemovesPreviouslyVisibleValue() {
        var original = mapper.createObjectNode();
        original.set("inputSnapshot", mapper.valueToTree(DecisionInputSnapshot.capture(DecisionContext.builder().record(Map.of("amount", 68000)).build(), Map.of("amount", fact("amount", false)))));
        var visible = DecisionInputSnapshot.project(original, mapper, model -> catalog(true));
        assertThat(visible.path("inputSnapshot").path("record.data.amount").path("state").asText()).isEqualTo("REDACTED");
        assertThat(visible.toString()).doesNotContain("68000");
    }

    @Test void legacyRecordsRemainUncapturedAndEmptyCatalogueFailsClosed() {
        var legacy = mapper.createObjectNode().put("old", true);
        assertThat(DecisionInputSnapshot.project(legacy, mapper, model -> { throw new AssertionError(); })).isSameAs(legacy);
        var original = mapper.createObjectNode();
        original.set("inputSnapshot", mapper.valueToTree(DecisionInputSnapshot.capture(DecisionContext.builder().record(Map.of("amount", 68000)).build(), Map.of("amount", fact("amount", false)))));
        assertThat(DecisionInputSnapshot.project(original, mapper, model -> null).path("inputSnapshot").isEmpty()).isTrue();
    }

    private Map<String,Object> fact(String name, boolean masked) {
        return Map.of("scope", "record", "path", "data." + name, "modelCode", "invoice", "label", name, "masked", masked);
    }
    private DecisionFactCatalogDTO catalog(boolean masked) {
        var fact = new DecisionFactDTO(); fact.setScope("record"); fact.setPath("data.amount"); fact.setModelCode("invoice"); fact.setVisible(true); fact.setMasked(masked);
        var entity = new DecisionFactEntityDTO(); entity.setFacts(List.of(fact));
        var result = new DecisionFactCatalogDTO(); result.setEntities(List.of(entity)); return result;
    }
}
