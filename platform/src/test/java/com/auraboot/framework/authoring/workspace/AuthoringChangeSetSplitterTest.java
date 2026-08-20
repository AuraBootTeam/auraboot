package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.workspace.AuthoringChangeSetSplitter.ChangeItem;
import com.auraboot.framework.authoring.workspace.AuthoringChangeSetSplitter.SplitPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoringChangeSetSplitterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AuthoringChangeSetSplitter splitter;

    @BeforeEach
    void setUp() {
        splitter = new AuthoringChangeSetSplitter(
                new AuthoringSnapshotTargetResolver(),
                new AuthoringJsonObjectPatchApplier(),
                new AuthoringStableBlockTreeEditor(new CoreAuthoringStructurePolicy()));
    }

    @Test
    void replaysIndependentPartitionsFromTheSameBase() throws Exception {
        JsonNode current = objectMapper.readTree("""
                {"blocks":[{"id":"table-1","blockType":"table","props":{
                  "density":"compact","pageSize":20}}]}
                """);
        ChangeItem density = item(
                "item-density", 1, "/props/density", "REPLACE",
                JsonNodeFactory.instance.textNode("normal"),
                JsonNodeFactory.instance.textNode("compact"), "L0", "INLINE");
        ChangeItem pageSize = item(
                "item-page-size", 2, "/props/pageSize", "ADD", null,
                JsonNodeFactory.instance.numberNode(20), "L3", "HANDOFF_STUDIO");

        SplitPlan plan = splitter.split(current, List.of(density, pageSize),
                List.of(pageSize.pid()));

        assertThat(plan.sourceSnapshot().at("/blocks/0/props/density").asText())
                .isEqualTo("compact");
        assertThat(plan.sourceSnapshot().at("/blocks/0/props/pageSize").isMissingNode()).isTrue();
        assertThat(plan.targetSnapshot().at("/blocks/0/props/density").asText())
                .isEqualTo("normal");
        assertThat(plan.targetSnapshot().at("/blocks/0/props/pageSize").asInt())
                .isEqualTo(20);
        assertThat(plan.targetDependencySnapshots().get(pageSize.pid()).isEmpty()).isTrue();
    }

    @Test
    void rejectsASelectionThatCutsAPropertyDependencyChain() throws Exception {
        JsonNode current = objectMapper.readTree("""
                {"blocks":[{"id":"table-1","blockType":"table","props":{
                  "density":"comfortable"}}]}
                """);
        ChangeItem first = item(
                "item-first", 1, "/props/density", "REPLACE",
                JsonNodeFactory.instance.textNode("normal"),
                JsonNodeFactory.instance.textNode("compact"), "L0", "INLINE");
        ChangeItem second = item(
                "item-second", 2, "/props/density", "REPLACE",
                JsonNodeFactory.instance.textNode("compact"),
                JsonNodeFactory.instance.textNode("comfortable"), "L0", "INLINE");

        assertThatThrownBy(() -> splitter.split(
                current, List.of(first, second), List.of(second.pid())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("authoring.split.dependency-crosses-partition");
    }

    @Test
    void rejectsUnknownDuplicateAndWholeSetSelections() throws Exception {
        JsonNode current = objectMapper.readTree("""
                {"blocks":[{"id":"table-1","blockType":"table","props":{
                  "density":"compact","pageSize":20}}]}
                """);
        ChangeItem first = item(
                "item-first", 1, "/props/density", "REPLACE",
                JsonNodeFactory.instance.textNode("normal"),
                JsonNodeFactory.instance.textNode("compact"), "L0", "INLINE");
        ChangeItem second = item(
                "item-second", 2, "/props/pageSize", "ADD", null,
                JsonNodeFactory.instance.numberNode(20), "L3", "HANDOFF_STUDIO");
        List<ChangeItem> items = List.of(first, second);

        assertThatThrownBy(() -> splitter.split(current, items, List.of("missing")))
                .hasMessageContaining("authoring.split.item-not-found");
        assertThatThrownBy(() -> splitter.split(
                current, items, List.of(first.pid(), first.pid())))
                .hasMessageContaining("authoring.split.selection-duplicate");
        assertThatThrownBy(() -> splitter.split(
                current, items, List.of(first.pid(), second.pid())))
                .hasMessageContaining("authoring.split.partition-empty");
    }

    private ChangeItem item(
            String pid,
            long baseRevision,
            String propertyPath,
            String operation,
            JsonNode oldValue,
            JsonNode newValue,
            String risk,
            String route) {
        return new ChangeItem(
                baseRevision,
                pid,
                "table-1",
                propertyPath,
                operation,
                oldValue,
                newValue,
                JsonNodeFactory.instance.arrayNode(),
                risk,
                route,
                "L3".equals(risk) ? "STUDIO_APPROVAL" : "DIRECT_ALLOWED",
                "REVERSIBLE",
                "manifest",
                baseRevision,
                baseRevision + 1,
                101,
                Instant.parse("2026-08-09T00:00:00Z").plusSeconds(baseRevision),
                null,
                null,
                JsonNodeFactory.instance.arrayNode());
    }
}
