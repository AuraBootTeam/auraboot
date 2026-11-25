package com.auraboot.framework.dashboard.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkbenchTemplateProviderTest {

    private static final List<String> TEMPLATE_WIDGET_IDS =
            List.of("wb-stats", "wb-inbox", "wb-shortcuts", "wb-recent");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void defaultWidgets_doNotOverlapAndStartOnTheFirstRow() {
        ArrayNode widgets = (ArrayNode) WorkbenchTemplateProvider.getDefaultWidgets(objectMapper);

        assertThat(widgets.size()).isEqualTo(TEMPLATE_WIDGET_IDS.size());
        for (int i = 0; i < widgets.size(); i++) {
            for (int j = i + 1; j < widgets.size(); j++) {
                assertThat(overlaps(widgets.get(i), widgets.get(j)))
                        .as("%s must not overlap %s", id(widgets.get(i)), id(widgets.get(j)))
                        .isFalse();
            }
        }
        assertThat(widgets.findValuesAsText("y").stream().mapToInt(Integer::parseInt).min().orElse(-1)).isZero();
    }

    @Test
    void workbenchStillOnTheOverlappingTemplate_isMovedOntoTheCurrentRows_andKeepsTheRest() {
        ArrayNode widgets = overlappingTemplate();
        ((ObjectNode) find(widgets, "wb-inbox").get("config")).put("title", "我的待办");

        assertThat(WorkbenchTemplateProvider.upgradeOverlappingDefaultLayout(objectMapper, widgets)).isTrue();

        ArrayNode current = (ArrayNode) WorkbenchTemplateProvider.getDefaultWidgets(objectMapper);
        for (String widgetId : TEMPLATE_WIDGET_IDS) {
            assertThat(position(find(widgets, widgetId))).isEqualTo(position(find(current, widgetId)));
        }
        assertThat(find(widgets, "wb-inbox").path("config").path("title").asText()).isEqualTo("我的待办");
        for (int i = 0; i < widgets.size(); i++) {
            for (int j = i + 1; j < widgets.size(); j++) {
                assertThat(overlaps(widgets.get(i), widgets.get(j))).isFalse();
            }
        }
    }

    @Test
    void theOrderOfTheWidgetsInTheListDoesNotMatter() {
        ArrayNode widgets = objectMapper.createArrayNode();
        for (String widgetId : List.of("wb-recent", "wb-shortcuts", "wb-inbox", "wb-stats")) {
            widgets.add(find(overlappingTemplate(), widgetId).deepCopy());
        }

        assertThat(WorkbenchTemplateProvider.upgradeOverlappingDefaultLayout(objectMapper, widgets)).isTrue();

        ArrayNode current = (ArrayNode) WorkbenchTemplateProvider.getDefaultWidgets(objectMapper);
        assertThat(position(find(widgets, "wb-stats"))).isEqualTo(position(find(current, "wb-stats")));
        assertThat(position(find(widgets, "wb-recent"))).isEqualTo(position(find(current, "wb-recent")));
    }

    @Test
    void workbenchTheUserChanged_isLeftUntouched() {
        ArrayNode moved = overlappingTemplate();
        ((ObjectNode) find(moved, "wb-inbox")).put("y", 3);
        assertUntouched(moved);

        ArrayNode resized = overlappingTemplate();
        ((ObjectNode) find(resized, "wb-recent")).put("h", 4);
        assertUntouched(resized);

        ArrayNode withoutOne = overlappingTemplate();
        withoutOne.remove(indexOf(withoutOne, "wb-shortcuts"));
        assertUntouched(withoutOne);

        ArrayNode withOneMore = overlappingTemplate();
        withOneMore.add(objectMapper.createObjectNode().put("id", "wb-extra")
                .put("x", 0).put("y", 7).put("w", 12).put("h", 2));
        assertUntouched(withOneMore);
    }

    @Test
    void otherWidgetLists_areLeftUntouched() {
        ArrayNode empty = objectMapper.createArrayNode();
        assertUntouched(empty);

        ArrayNode contributedOnly = objectMapper.createArrayNode();
        contributedOnly.add(objectMapper.createObjectNode().put("id", "workbench-contribution-team-note-1")
                .put("x", 0).put("y", 0).put("w", 12).put("h", 2));
        assertUntouched(contributedOnly);

        assertThat(WorkbenchTemplateProvider.upgradeOverlappingDefaultLayout(objectMapper, null)).isFalse();
    }

    /** The default template as shipped from 2026-10-05 to 2026-11-25: the cards started one row too high. */
    private ArrayNode overlappingTemplate() {
        ArrayNode widgets = (ArrayNode) WorkbenchTemplateProvider.getDefaultWidgets(objectMapper).deepCopy();
        ((ObjectNode) find(widgets, "wb-inbox")).put("y", 1);
        ((ObjectNode) find(widgets, "wb-shortcuts")).put("y", 1);
        ((ObjectNode) find(widgets, "wb-recent")).put("y", 4);
        return widgets;
    }

    /** Not moved, and not a single field changed. */
    private void assertUntouched(ArrayNode widgets) {
        ArrayNode before = widgets.deepCopy();
        assertThat(WorkbenchTemplateProvider.upgradeOverlappingDefaultLayout(objectMapper, widgets)).isFalse();
        assertThat(widgets).isEqualTo(before);
    }

    private JsonNode find(ArrayNode widgets, String widgetId) {
        for (JsonNode widget : widgets) {
            if (widgetId.equals(widget.path("id").asText(""))) {
                return widget;
            }
        }
        throw new AssertionError("no widget " + widgetId + " in " + widgets);
    }

    private int indexOf(ArrayNode widgets, String widgetId) {
        for (int i = 0; i < widgets.size(); i++) {
            if (widgetId.equals(widgets.get(i).path("id").asText(""))) {
                return i;
            }
        }
        throw new AssertionError("no widget " + widgetId + " in " + widgets);
    }

    private boolean overlaps(JsonNode a, JsonNode b) {
        boolean columnsOverlap = a.path("x").asInt() < b.path("x").asInt() + b.path("w").asInt()
                && b.path("x").asInt() < a.path("x").asInt() + a.path("w").asInt();
        boolean rowsOverlap = a.path("y").asInt() < b.path("y").asInt() + b.path("h").asInt()
                && b.path("y").asInt() < a.path("y").asInt() + a.path("h").asInt();
        return columnsOverlap && rowsOverlap;
    }

    private String position(JsonNode widget) {
        return widget.path("x").asInt() + "," + widget.path("y").asInt() + " "
                + widget.path("w").asInt() + "x" + widget.path("h").asInt();
    }

    private String id(JsonNode widget) {
        return widget.path("id").asText("");
    }
}
