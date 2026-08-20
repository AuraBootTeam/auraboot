package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SessionView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SyntheticPreviewView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SyntheticPreviewWidgetView;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Generates deterministic Studio fixtures from schema metadata without reading business rows. */
@Service
public class AuthoringSyntheticPreviewService {

    private static final int RECORD_COUNT = 3;
    private static final String MODE = "SYNTHETIC";
    private static final String SOURCE = "GENERATED_IN_MEMORY";

    private final AuthoringWorkspaceService workspaceService;

    public AuthoringSyntheticPreviewService(AuthoringWorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @Transactional(readOnly = true)
    public SyntheticPreviewView preview(String sessionPid) {
        SessionView session = workspaceService.get(sessionPid);
        LinkedHashMap<String, FieldFixture> fields = new LinkedHashMap<>();
        LinkedHashMap<String, SyntheticPreviewWidgetView> widgets = new LinkedHashMap<>();
        collectMetadata(session.snapshot(), fields, widgets);

        List<Map<String, Object>> records = new ArrayList<>();
        for (int index = 1; index <= RECORD_COUNT; index++) {
            LinkedHashMap<String, Object> record = new LinkedHashMap<>();
            for (FieldFixture field : fields.values()) {
                record.put(field.code(), generateValue(field, index));
            }
            record.put("pid", "synthetic-%03d".formatted(index));
            records.add(Collections.unmodifiableMap(record));
        }

        Map<String, Object> formValues = records.isEmpty()
                ? Map.of()
                : records.getFirst();
        return new SyntheticPreviewView(
                MODE,
                session.pagePid(),
                SOURCE,
                true,
                false,
                false,
                false,
                session.revision(),
                formValues,
                List.copyOf(records),
                Collections.unmodifiableMap(widgets));
    }

    private static void collectMetadata(
            JsonNode node,
            Map<String, FieldFixture> fields,
            Map<String, SyntheticPreviewWidgetView> widgets) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectMetadata(child, fields, widgets));
            return;
        }
        if (!node.isObject()) {
            return;
        }

        String blockType = text(node, "blockType");
        if (isFieldBlock(blockType)) {
            String code = text(node, "field");
            if (code != null) {
                JsonNode props = node.path("props");
                fields.putIfAbsent(code, new FieldFixture(
                        code,
                        text(props, "component"),
                        optionValues(props.get("options"))));
            }
        } else if ("widget".equalsIgnoreCase(blockType)) {
            String blockId = text(node, "id");
            if (blockId != null) {
                widgets.put(blockId, widgetFixture(text(node, "widgetType")));
            }
        }

        node.properties().forEach(entry -> {
            if (entry.getValue().isContainerNode()) {
                collectMetadata(entry.getValue(), fields, widgets);
            }
        });
    }

    private static boolean isFieldBlock(String blockType) {
        return "field".equalsIgnoreCase(blockType)
                || "filter-field".equalsIgnoreCase(blockType)
                || "column".equalsIgnoreCase(blockType);
    }

    private static Object generateValue(FieldFixture field, int index) {
        String signal = (field.code() + " " + nullToEmpty(field.component()))
                .toLowerCase(Locale.ROOT);
        if (signal.contains("checkbox") || signal.contains("switch") || signal.contains("boolean")) {
            return index % 2 == 1;
        }
        if (signal.contains("datetime")) {
            return "2026-08-%02dT09:30:00Z".formatted(index);
        }
        if (signal.contains("date")) {
            return "2026-08-%02d".formatted(index);
        }
        if (signal.contains("email")) {
            return "sample%02d@example.invalid".formatted(index);
        }
        if (signal.contains("phone") || signal.contains("mobile") || signal.contains("tel")) {
            return "1380000%04d".formatted(index);
        }
        if (signal.contains("number")
                || signal.contains("amount")
                || signal.contains("price")
                || signal.contains("total")
                || signal.contains("count")
                || signal.contains("quantity")
                || signal.contains("qty")) {
            return 100 + index;
        }
        if (!field.options().isEmpty()) {
            return field.options().get((index - 1) % field.options().size());
        }
        return "Sample %s %02d".formatted(field.code(), index);
    }

    private static SyntheticPreviewWidgetView widgetFixture(String widgetType) {
        String normalized = nullToEmpty(widgetType).toLowerCase(Locale.ROOT);
        String value = normalized.contains("percent") || normalized.contains("progress")
                ? "72"
                : "128";
        List<Map<String, Object>> series = List.of(
                seriesPoint("Sample A", 24),
                seriesPoint("Sample B", 39),
                seriesPoint("Sample C", 31));
        return new SyntheticPreviewWidgetView(SOURCE, value, series);
    }

    private static Map<String, Object> seriesPoint(String label, int value) {
        LinkedHashMap<String, Object> point = new LinkedHashMap<>();
        point.put("label", label);
        point.put("value", value);
        return Collections.unmodifiableMap(point);
    }

    private static List<String> optionValues(JsonNode options) {
        if (options == null || !options.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode option : options) {
            String value = option.isTextual() ? option.asText() : text(option, "value");
            if (value != null && !value.isBlank()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank()
                ? value.asText()
                : null;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record FieldFixture(
            String code,
            String component,
            List<String> options) {
    }
}
