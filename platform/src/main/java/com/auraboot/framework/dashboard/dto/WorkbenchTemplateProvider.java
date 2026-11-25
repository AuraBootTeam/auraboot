package com.auraboot.framework.dashboard.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provides the default workbench template for new users.
 */
@Slf4j
public class WorkbenchTemplateProvider {

    private static final String TEMPLATE_JSON = """
        [
          {
            "id": "wb-stats",
            "type": "StatsRowWidget",
            "x": 0, "y": 0, "w": 12, "h": 2,
            "minW": 6, "minH": 2, "maxW": 12, "maxH": 2,
            "config": {
              "title": "workbench.stats.title",
              "dataSource": { "type": "static" },
              "visualization": {}
            }
          },
          {
            "id": "wb-inbox",
            "type": "InboxWidget",
            "x": 0, "y": 2, "w": 7, "h": 5,
            "minW": 4, "minH": 3, "maxW": 12, "maxH": 8,
            "config": {
              "title": "workbench.inbox.title",
              "dataSource": { "type": "static" },
              "visualization": { "maxItems": 8 }
            }
          },
          {
            "id": "wb-shortcuts",
            "type": "ShortcutsWidget",
            "x": 7, "y": 2, "w": 5, "h": 3,
            "minW": 3, "minH": 2, "maxW": 12, "maxH": 4,
            "config": {
              "title": "workbench.shortcuts.title",
              "dataSource": { "type": "static" },
              "visualization": { "columns": 4, "showAddButton": true }
            }
          },
          {
            "id": "wb-recent",
            "type": "RecentWidget",
            "x": 7, "y": 5, "w": 5, "h": 3,
            "minW": 3, "minH": 2, "maxW": 6, "maxH": 6,
            "config": {
              "title": "workbench.recent.title",
              "dataSource": { "type": "static" },
              "visualization": { "maxItems": 8 }
            }
          }
        ]
        """;

    private static final String LAYOUT_CONFIG_JSON = """
        {"columns": 12, "rowHeight": 80, "gap": 12, "compactType": "vertical"}
        """;

    /**
     * Position and size of every widget of the default template as shipped from 2026-10-05 to
     * 2026-11-25: the stats row was made two rows high without moving the cards below it, so it
     * overlapped them on row 1 and the grid pushed it to the bottom of the page, leaving row 0
     * empty. Row: widget id, x, y, w, h.
     */
    private static final String[][] OVERLAPPING_TEMPLATE = {
            {"wb-stats", "0", "0", "12", "2"},
            {"wb-inbox", "0", "1", "7", "5"},
            {"wb-shortcuts", "7", "1", "5", "3"},
            {"wb-recent", "7", "4", "5", "3"},
    };

    /**
     * Moves a widget list that still has exactly the positions of {@link #OVERLAPPING_TEMPLATE}
     * onto the rows of the current template. Everything else on the widgets (titles, configuration,
     * size limits) is kept. A list that differs in any widget — one added, removed, moved or
     * resized — is left untouched.
     *
     * @return whether the widgets were moved
     */
    public static boolean upgradeOverlappingDefaultLayout(ObjectMapper objectMapper, JsonNode widgets) {
        if (widgets == null || !widgets.isArray() || widgets.size() != OVERLAPPING_TEMPLATE.length) {
            return false;
        }
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode widget : widgets) {
            byId.put(widget.path("id").asText(""), widget);
        }
        Map<String, JsonNode> targets = new LinkedHashMap<>();
        for (JsonNode candidate : getDefaultWidgets(objectMapper)) {
            targets.put(candidate.path("id").asText(""), candidate);
        }
        // Nothing is written before every widget has matched.
        for (String[] legacy : OVERLAPPING_TEMPLATE) {
            JsonNode widget = byId.get(legacy[0]);
            if (widget == null || !widget.isObject() || !hasPosition(widget, legacy)
                    || !targets.containsKey(legacy[0])) {
                return false;
            }
        }
        for (String[] legacy : OVERLAPPING_TEMPLATE) {
            ObjectNode widget = (ObjectNode) byId.get(legacy[0]);
            JsonNode target = targets.get(legacy[0]);
            widget.put("x", target.path("x").asInt());
            widget.put("y", target.path("y").asInt());
        }
        return true;
    }

    private static boolean hasPosition(JsonNode widget, String[] position) {
        return widget.isObject()
                && widget.path("x").asInt(Integer.MIN_VALUE) == Integer.parseInt(position[1])
                && widget.path("y").asInt(Integer.MIN_VALUE) == Integer.parseInt(position[2])
                && widget.path("w").asInt(Integer.MIN_VALUE) == Integer.parseInt(position[3])
                && widget.path("h").asInt(Integer.MIN_VALUE) == Integer.parseInt(position[4]);
    }

    public static JsonNode getDefaultWidgets(ObjectMapper objectMapper) {
        try {
            return objectMapper.readTree(TEMPLATE_JSON);
        } catch (Exception e) {
            // CATCH: non-transactional, safe to handle — JSON parse of static template
            log.error("Failed to parse workbench template", e);
            return objectMapper.createArrayNode();
        }
    }

    public static JsonNode getDefaultLayoutConfig(ObjectMapper objectMapper) {
        try {
            return objectMapper.readTree(LAYOUT_CONFIG_JSON);
        } catch (Exception e) {
            // CATCH: non-transactional, safe to handle — JSON parse of static template
            log.error("Failed to parse workbench layout config", e);
            return objectMapper.createObjectNode();
        }
    }
}
