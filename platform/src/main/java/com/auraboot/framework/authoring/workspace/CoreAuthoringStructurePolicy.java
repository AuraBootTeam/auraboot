package com.auraboot.framework.authoring.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/** Server-owned containment policy for core PageSchema blocks. Unknown plugin blocks fail closed. */
@Component
public class CoreAuthoringStructurePolicy {

    private static final Set<String> PAGE_ROOTS = Set.of("form", "list", "detail", "dashboard");
    private static final Map<String, Set<String>> ALLOWED_CHILDREN = Map.ofEntries(
            Map.entry("form", Set.of("form-section", "tabs", "action-bar")),
            Map.entry("list", Set.of("tabs", "filter-bar", "action-bar", "table", "widget")),
            Map.entry("detail", Set.of("tabs", "detail-section", "action-bar", "widget",
                    "stat-card", "description", "chart", "rich-text")),
            Map.entry("dashboard", Set.of("widget", "stat-card", "description", "chart", "rich-text")),
            Map.entry("form-section", Set.of("form-section", "field")),
            Map.entry("detail-section", Set.of("field")),
            Map.entry("tabs", Set.of("tab")),
            Map.entry("tab", Set.of("form-section", "detail-section", "filter-bar", "action-bar",
                    "table", "widget", "stat-card", "description", "chart", "rich-text")),
            Map.entry("filter-bar", Set.of("filter-field")),
            Map.entry("table", Set.of("column", "action")),
            Map.entry("action-bar", Set.of("action")));

    public boolean allowsChild(String parentBlockType, String childBlockType) {
        return ALLOWED_CHILDREN.getOrDefault(parentBlockType, Set.of()).contains(childBlockType);
    }

    public boolean allowsRoot(JsonNode snapshot, String childBlockType) {
        String kind = snapshot.path("kind").asText("composite");
        return "composite".equals(kind)
                ? PAGE_ROOTS.contains(childBlockType)
                : kind.equals(childBlockType) && PAGE_ROOTS.contains(childBlockType);
    }

    public boolean isContainer(String blockType) {
        return ALLOWED_CHILDREN.containsKey(blockType);
    }
}
