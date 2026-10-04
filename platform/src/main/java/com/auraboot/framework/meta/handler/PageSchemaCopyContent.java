package com.auraboot.framework.meta.handler;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.PageSchemaCreateRequest;
import com.auraboot.framework.meta.dto.PageSchemaDTO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The command-side copy boundary for stored flat pages and designer trees. */
final class PageSchemaCopyContent {
    private static final Set<String> ROOTS = Set.of("list", "form", "detail", "dashboard");
    private static final Set<String> LEAVES = Set.of("field", "column", "action", "filter-field");
    private static final Set<String> PASSTHROUGH = Set.of(
            "repeater", "subform", "columns", "ai-fill-banner", "sub-table", "embedded-list",
            "activity-timeline", "record-comments", "field-history", "workflow-panel", "metric-strip",
            "stage-rail", "record-inspector", "candidate-list", "workbench-action-bar", "evidence-panel",
            "artifact-timeline", "review-drawer", "status-banner", "description", "divider", "rich-text",
            "chart", "stat-card", "monthly-grid", "card-grid", "code-snippet", "conversation-panel",
            "selection-info", "trace-graph", "gerber-viewer", "custom");

    private PageSchemaCopyContent() { }

    static void apply(PageSchemaDTO source, PageSchemaCreateRequest target) {
        Integer version = source.getSchemaVersion();
        List<Object> blocks = source.getBlocks() == null ? List.of() : source.getBlocks();
        boolean tree = blocks.stream().anyMatch(block -> block instanceof Map<?, ?> m
                && m.get("blockType") instanceof String type && ROOTS.contains(type)
                && m.get("blocks") instanceof List<?>);
        if (version == null || (version != 2 && version != 3 && version != 4)) {
            throw new BusinessException("Unsupported page schema version: " + version);
        }
        target.setSchemaVersion(4);
        target.setDataSources(source.getDataSources());
        target.setExtension(source.getExtension());
        target.setKind(source.getKind());
        if (version != 3 && !tree) {
            target.setBlocks(blocks);
            return;
        }
        String kind = source.getKind() == null ? "" : source.getKind().toLowerCase(Locale.ROOT);
        if (!Set.of("list", "form", "detail").contains(kind)) {
            throw new BusinessException("Page kind has no flat v4 representation: " + kind);
        }
        target.setKind(kind);
        if (blocks.size() == 1 && kind.equals(map(blocks.getFirst()).get("blockType"))) {
            Map<String, Object> root = map(blocks.getFirst());
            blocks = children(root);
            Map<String, Object> extension = new LinkedHashMap<>(source.getExtension() == null
                    ? Map.of() : source.getExtension());
            if (root.get("id") != null) extension.put("designerRootId", root.get("id"));
            target.setExtension(extension);
        }
        target.setBlocks(blocks.stream().map(block -> (Object) serialize(map(block))).toList());
    }

    private static Map<String, Object> serialize(Map<String, Object> block) {
        String type = text(block, "blockType", "");
        Map<String, Object> props = nonNull(mapOrEmpty(block.get("props")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", block.get("id"));
        out.put("blockType", type);
        copy(block, out, "title");
        switch (type) {
            case "filter-bar" -> {
                out.put("blockType", "filters");
                out.put("fields", fields(block, "filter-field"));
                for (String key : List.of("actions", "buttons")) {
                    Object value = props.remove(key);
                    if (value instanceof List<?>) out.put(key, value);
                }
            }
            case "action-bar" -> {
                out.put("blockType", "footer".equals(block.get("region")) ? "form-buttons" : "toolbar");
                out.put("buttons", children(block).stream().map(item -> {
                    Map<String, Object> child = map(item);
                    if (!"action".equals(child.get("blockType"))) throw unsupported(child);
                    return action(child, text(block, "id", ""));
                }).toList());
                props.clear();
            }
            case "form-section", "detail-section" -> {
                copy(block, out, "region");
                out.put("fields", fields(block, "field"));
                move(props, out, "columns", "collapsible", "defaultCollapsed");
            }
            case "table" -> {
                copy(block, out, "region");
                List<Object> columns = new ArrayList<>();
                List<Object> actions = new ArrayList<>();
                for (Object item : children(block)) {
                    Map<String, Object> child = map(item);
                    if ("column".equals(child.get("blockType"))) columns.add(field(child, text(block, "id", "")));
                    else if ("action".equals(child.get("blockType"))) actions.add(action(child, text(block, "id", "")));
                    else throw unsupported(child);
                }
                out.put("columns", columns);
                Object rowActions = props.remove("rowActions");
                if (!actions.isEmpty()) out.put("rowActions", actions);
                else if (rowActions instanceof List<?>) out.put("rowActions", rowActions);
                move(props, out, "selection");
            }
            case "tabs" -> {
                copy(block, out, "region");
                List<Object> tabs = new ArrayList<>();
                String parent = text(block, "id", "");
                int index = 0;
                for (Object item : children(block)) {
                    Map<String, Object> tab = map(item);
                    if (!"tab".equals(tab.get("blockType"))) throw unsupported(tab);
                    Map<String, Object> entry = nonNull(mapOrEmpty(tab.get("props")));
                    entry.put("blocks", children(tab).stream().map(child -> serialize(map(child))).toList());
                    if (tab.containsKey("title")) entry.put("label", tab.get("title"));
                    String key = text(entry, "key", "");
                    String id = text(tab, "id", "");
                    String prefix = stable(parent) + "_";
                    if (key.isEmpty()) key = id.startsWith(prefix) && id.length() > prefix.length()
                            ? id.substring(prefix.length()) : "tab_" + (index + 1);
                    entry.put("key", key);
                    if (!id.isEmpty()) entry.put("id", id);
                    tabs.add(entry);
                    index++;
                }
                out.put("tabs", tabs);
                props.clear();
            }
            default -> {
                if ("widget".equals(type)) {
                    String widget = text(block, "widgetType", "");
                    if (!Set.of("chart", "stat-card", "number-card").contains(widget)) throw unsupported(block);
                    out.put("blockType", "number-card".equals(widget) ? "stat-card" : widget);
                } else if (!PASSTHROUGH.contains(type)) throw unsupported(block);
                copy(block, out, "region");
                if (!children(block).isEmpty()) out.put("blocks", children(block).stream().map(child -> {
                    Map<String, Object> nested = map(child);
                    return LEAVES.contains(text(nested, "blockType", "")) ? nested : serialize(nested);
                }).toList());
            }
        }
        common(block, out, props);
        return out;
    }

    private static List<Object> fields(Map<String, Object> block, String allowed) {
        return children(block).stream().map(item -> {
            Map<String, Object> child = map(item);
            if (!allowed.equals(child.get("blockType"))) throw unsupported(child);
            return field(child, text(block, "id", ""));
        }).toList();
    }

    private static Object field(Map<String, Object> block, String parent) {
        String field = text(block, "field", "field");
        Map<String, Object> props = nonNull(mapOrEmpty(block.get("props")));
        Map<String, Object> layout = mapOrEmpty(block.get("layout"));
        String id = text(block, "id", "");
        boolean canonical = id.isEmpty() || id.equals(stable(parent, field));
        if (props.isEmpty() && layout.isEmpty() && canonical) return field;
        props.put("field", field);
        if (!canonical) props.put("id", id);
        for (String key : List.of("span", "width")) if (layout.get(key) instanceof Number) props.put(key, layout.get(key));
        return props;
    }

    private static Object action(Map<String, Object> block, String parent) {
        String actionType = text(block, "actionType", "custom");
        Map<String, Object> props = nonNull(mapOrEmpty(block.get("props")));
        String code = text(props, "code", actionType);
        String id = text(block, "id", "");
        boolean canonical = id.isEmpty() || id.equals(stable(parent, code));
        if (props.isEmpty() && !block.containsKey("title") && canonical) return actionType;
        props.put("code", code);
        props.put("actionType", actionType);
        if (!canonical) props.put("id", id);
        copy(block, props, "title");
        Map<String, Object> layout = mapOrEmpty(block.get("layout"));
        if (layout.get("span") instanceof Number) props.put("span", layout.get("span"));
        return props;
    }

    private static void common(Map<String, Object> block, Map<String, Object> out, Map<String, Object> props) {
        Map<String, Object> layout = new LinkedHashMap<>(mapOrEmpty(block.get("layout")));
        for (String key : List.of("span", "rowSpan")) {
            Object value = layout.remove(key);
            if (value instanceof Number) out.put(key, value);
        }
        if (!layout.isEmpty()) out.put("layout", layout);
        if (!props.isEmpty()) out.put("props", props);
        if (block.containsKey("dataSource")) {
            Object dataSource = block.get("dataSource");
            if (dataSource instanceof Map<?, ?> ref && ref.size() == 1 && ref.get("ref") instanceof String) {
                dataSource = ref.get("ref");
            }
            out.put("dataSource", dataSource);
        }
    }

    private static String stable(String... parts) {
        return java.util.Arrays.stream(parts).map(part -> part.trim().replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", ""))
                .filter(part -> !part.isEmpty()).collect(java.util.stream.Collectors.joining("_"));
    }

    private static String text(Map<String, Object> map, String key, String fallback) {
        return map.get(key) instanceof String value ? value : fallback;
    }

    private static void copy(Map<String, Object> from, Map<String, Object> to, String key) {
        if (from.containsKey(key)) to.put(key, from.get(key));
    }

    private static void move(Map<String, Object> from, Map<String, Object> to, String... keys) {
        for (String key : keys) if (from.containsKey(key)) to.put(key, from.remove(key));
    }

    private static Map<String, Object> nonNull(Map<String, Object> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        source.forEach((key, value) -> { if (value != null) out.put(key, value); });
        return out;
    }

    private static Map<String, Object> mapOrEmpty(Object value) {
        return value == null ? Map.of() : map(value);
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) throw new BusinessException("Page block must be an object");
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (!(key instanceof String)) throw new BusinessException("Page block keys must be strings");
            result.put((String) key, item);
        });
        return result;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> children(Map<String, Object> block) {
        Object value = block.get("blocks");
        if (value == null) return List.of();
        if (!(value instanceof List<?>)) throw new BusinessException("Page block children must be an array");
        return (List<Object>) value;
    }

    private static BusinessException unsupported(Map<String, Object> block) {
        return new BusinessException("Block " + block.get("id") + " (" + block.get("blockType")
                + ") has no flat v4 representation in this container");
    }
}
