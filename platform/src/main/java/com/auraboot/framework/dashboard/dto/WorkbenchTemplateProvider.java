package com.auraboot.framework.dashboard.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

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
            "x": 0, "y": 0, "w": 12, "h": 1,
            "minW": 6, "minH": 1, "maxW": 12, "maxH": 2,
            "config": {
              "title": "workbench.stats.title",
              "dataSource": { "type": "static" },
              "visualization": {}
            }
          },
          {
            "id": "wb-inbox",
            "type": "InboxWidget",
            "x": 0, "y": 1, "w": 7, "h": 5,
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
            "x": 7, "y": 1, "w": 5, "h": 2,
            "minW": 3, "minH": 2, "maxW": 12, "maxH": 3,
            "config": {
              "title": "workbench.shortcuts.title",
              "dataSource": { "type": "static" },
              "visualization": { "columns": 4, "showAddButton": true }
            }
          },
          {
            "id": "wb-recent",
            "type": "RecentWidget",
            "x": 7, "y": 3, "w": 5, "h": 3,
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
