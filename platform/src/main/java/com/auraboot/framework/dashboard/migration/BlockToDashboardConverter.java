package com.auraboot.framework.dashboard.migration;

import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.dashboard.entity.Dashboard;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Converts a V2 blocks page (kind=dashboard) from {@link PageSchemaDTO} into
 * a {@link Dashboard} entity ready for persistence.
 *
 * <p>Mapping rules:
 * <ul>
 *   <li>blockType=chart + chartType=X → smart-X-chart</li>
 *   <li>blockType=stat-card           → smart-number-card</li>
 *   <li>blockType=table               → smart-table-chart</li>
 *   <li>blockType=rich-text           → smart-rich-text</li>
 *   <li>any other blockType           → smart-unknown (WARN logged)</li>
 * </ul>
 *
 * <p>Layout derivation:
 * <ul>
 *   <li>x = 0 (always; horizontal placement is not encoded in V2 blocks)</li>
 *   <li>y = cumulative rowSpan of preceding blocks</li>
 *   <li>w = block.colSpan  (default 12)</li>
 *   <li>h = block.rowSpan  (default 1)</li>
 * </ul>
 */
public final class BlockToDashboardConverter {

    private static final Logger log = LoggerFactory.getLogger(BlockToDashboardConverter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BlockToDashboardConverter() {
        // utility class
    }

    // ------------------------------------------------------------------ public API

    /**
     * Convert a dashboard-kind PageSchemaDTO into a Dashboard entity.
     * The entity's {@code id} and {@code tenantId} are left null — the caller
     * (typically a service or importer) must set them before persistence.
     *
     * @param page source page schema DTO (kind must be "dashboard")
     * @return populated Dashboard entity
     */
    public static Dashboard convert(PageSchemaDTO page) {
        Dashboard dashboard = new Dashboard();

        dashboard.setPid(UniqueIdGenerator.generate());
        dashboard.setCode(page.getPageKey());
        dashboard.setTitle(resolveTitle(page.getTitle()));
        dashboard.setDescription(page.getDescription());
        dashboard.setScope("global");
        dashboard.setStatus(StatusConstants.PUBLISHED);
        dashboard.setIsDefault(false);
        dashboard.setSortOrder(0);

        dashboard.setLayoutConfig(buildLayoutConfig(page));
        dashboard.setWidgets(buildWidgets(page.getBlocks()));

        return dashboard;
    }

    /**
     * Overload for plugin import path: accepts the plugin-import DTO variant
     * ({@link com.auraboot.framework.plugin.dto.imports.PageSchemaDTO}) which has the same
     * relevant fields (pageKey, title, description, layout, blocks) as the meta DTO.
     *
     * @param page plugin import page schema DTO (kind must be "dashboard")
     * @return populated Dashboard entity
     */
    public static Dashboard convert(com.auraboot.framework.plugin.dto.imports.PageSchemaDTO page) {
        Dashboard dashboard = new Dashboard();

        dashboard.setPid(UniqueIdGenerator.generate());
        dashboard.setCode(page.getPageKey());
        dashboard.setTitle(resolveTitle(page.getTitle()));
        dashboard.setDescription(page.getDescription());
        dashboard.setScope("global");
        dashboard.setStatus(StatusConstants.PUBLISHED);
        dashboard.setIsDefault(false);
        dashboard.setSortOrder(0);

        dashboard.setLayoutConfig(buildLayoutConfigFromMap(page.getLayout()));
        dashboard.setWidgets(buildWidgets(page.getBlocks()));

        return dashboard;
    }

    // ------------------------------------------------------------------ private helpers

    /**
     * Build the JSON layout config.
     * If the page has a layout map that already carries columns/rowHeight/gap, use those values;
     * otherwise use defaults: columns=12, rowHeight=100, gap=16.
     */
    private static com.fasterxml.jackson.databind.JsonNode buildLayoutConfig(PageSchemaDTO page) {
        return buildLayoutConfigFromMap(page.getLayout());
    }

    /**
     * Build layout config from a raw Map (used by both overloads of convert).
     */
    private static com.fasterxml.jackson.databind.JsonNode buildLayoutConfigFromMap(Map<String, Object> layout) {
        ObjectNode cfg = MAPPER.createObjectNode();

        cfg.put("columns",   getInt(layout, "columns",   12));
        cfg.put("rowHeight", getInt(layout, "rowHeight", 100));
        cfg.put("gap",       getInt(layout, "gap",       16));
        cfg.put("compactType", "vertical");
        return cfg;
    }

    /**
     * Convert blocks list into a JSON array of widget nodes.
     */
    private static com.fasterxml.jackson.databind.JsonNode buildWidgets(List<Object> blocks) {
        ArrayNode widgets = MAPPER.createArrayNode();
        if (blocks == null || blocks.isEmpty()) {
            return widgets;
        }

        int yOffset = 0;
        for (int i = 0; i < blocks.size(); i++) {
            @SuppressWarnings("unchecked")
            Map<String, Object> block = (Map<String, Object>) blocks.get(i);

            Map<String, Object> layout = extractLayoutMap(block);
            int colSpan = getInt(layout, "colSpan", getInt(block, "colSpan", 12));
            int rowSpan = getInt(layout, "rowSpan", getInt(block, "rowSpan", 1));
            String blockId = block.containsKey("id") ? String.valueOf(block.get("id")) : "widget_" + i;
            String widgetType = mapWidgetType(block);

            ObjectNode widget = MAPPER.createObjectNode();
            widget.put("id",   blockId);
            widget.put("type", widgetType);
            widget.put("x",    0);
            widget.put("y",    yOffset);
            widget.put("w",    colSpan);
            widget.put("h",    rowSpan);

            // Resolve widget title — publish validator requires non-blank title
            // either at widget.title or widget.config.title. Source can be a
            // LocalizedText map, a plain string, or missing (fall back to blockId).
            String title = resolveWidgetTitle(block, blockId);
            widget.put("title", title);

            ObjectNode config = (ObjectNode) extractConfig(block);
            if (!config.has("title") || !com.fasterxml.jackson.databind.node.JsonNodeType.STRING.equals(config.get("title").getNodeType())
                    || config.get("title").asText().isBlank()) {
                config.put("title", title);
            }
            widget.set("config", config);

            widgets.add(widget);
            yOffset += rowSpan;
        }
        return widgets;
    }

    /**
     * Resolve the widget title from the block.  Accepts a LocalizedText map,
     * a plain String, or falls back to the block id.
     */
    private static String resolveWidgetTitle(Map<String, Object> block, String blockId) {
        Object raw = block.get("title");
        if (raw instanceof String s && !s.isBlank()) {
            return s;
        }
        if (raw instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> titleMap = (Map<String, Object>) map;
            String resolved = resolveTitle(titleMap);
            if (resolved != null && !resolved.isBlank()) {
                return resolved;
            }
        }
        return blockId;
    }

    /**
     * Map a block's blockType (and optional chartType) to a widget type string.
     */
    private static String mapWidgetType(Map<String, Object> block) {
        String blockType = String.valueOf(block.getOrDefault("blockType", ""));
        return switch (blockType) {
            case "chart" -> {
                String chartType = String.valueOf(block.getOrDefault("chartType", "bar"));
                yield "smart-" + chartType + "-chart";
            }
            case "stat-card" -> "smart-number-card";
            case "table"     -> "smart-table-chart";
            case "rich-text" -> "smart-rich-text";
            default -> {
                log.warn("BlockToDashboardConverter: unknown blockType '{}', mapping to smart-unknown", blockType);
                yield "smart-unknown";
            }
        };
    }

    /**
     * Extract the widget config from the block.
     * Uses "chartConfig" if present; otherwise copies all block fields
     * except blockType/id/colSpan/rowSpan/chartType.
     */
    private static com.fasterxml.jackson.databind.JsonNode extractConfig(Map<String, Object> block) {
        if (block.containsKey("chartConfig")) {
            Object chartConfig = block.get("chartConfig");
            return MAPPER.valueToTree(chartConfig);
        }
        ObjectNode config = MAPPER.createObjectNode();
        for (Map.Entry<String, Object> entry : block.entrySet()) {
            String key = entry.getKey();
            if (!key.equals("blockType") && !key.equals("id")
                    && !key.equals("colSpan") && !key.equals("rowSpan")
                    && !key.equals("chartType")) {
                config.set(key, MAPPER.valueToTree(entry.getValue()));
            }
        }
        return config;
    }

    /**
     * Resolve a localized title Map to a single String.
     * Preference order: zh-CN → en → first non-null value.
     * Returns an empty string if the map is null or empty.
     */
    private static String resolveTitle(Map<String, Object> titleMap) {
        if (titleMap == null || titleMap.isEmpty()) {
            return "";
        }
        if (titleMap.containsKey("zh-CN") && titleMap.get("zh-CN") != null) {
            return String.valueOf(titleMap.get("zh-CN"));
        }
        if (titleMap.containsKey("en") && titleMap.get("en") != null) {
            return String.valueOf(titleMap.get("en"));
        }
        return titleMap.values().stream()
                .filter(v -> v != null)
                .map(String::valueOf)
                .findFirst()
                .orElse("");
    }

    /**
     * Extract a block's nested `layout` map if present. Real plugin JSONs use
     * `{ "blockType": "chart", "layout": { "colSpan": 6, "rowSpan": 3 } }`.
     * Returns empty map when the block has no layout object so callers can fall back.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractLayoutMap(Map<String, Object> block) {
        Object layout = block.get("layout");
        if (layout instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    private static int getInt(Map<String, Object> map, String key, int defaultValue) {
        if (map == null || !map.containsKey(key)) {
            return defaultValue;
        }
        Object val = map.get(key);
        if (val instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(val));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
