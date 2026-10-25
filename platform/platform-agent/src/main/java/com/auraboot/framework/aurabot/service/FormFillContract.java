package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.FormFillRequest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Closed draft schema shared by tool discovery and execution-time validation. */
final class FormFillContract {
    static final String TOOL_NAME = "platform_fill_form";
    private static final Set<String> TYPES = Set.of("string", "integer", "number", "boolean");
    private static final Set<String> RESERVED = Set.of("__proto__", "constructor", "prototype");

    private FormFillContract() { }

    static Map<String, Object> schema(FormFillRequest request) {
        if (request.targetId() == null || request.targetId().isBlank()
                || request.modelCode() == null || request.modelCode().isBlank()
                || request.fields() == null || request.fields().isEmpty() || request.fields().size() > 100) {
            throw new IllegalArgumentException("Invalid form fill target or field count");
        }
        LocalDate.parse(request.currentDate());
        ZoneId.of(request.timeZone());
        Map<String, Object> fields = new LinkedHashMap<>();
        for (FormFillRequest.Field field : request.fields()) {
            if (field == null || field.code() == null || !field.code().matches("[A-Za-z][A-Za-z0-9_]{0,127}")
                    || RESERVED.contains(field.code()) || !TYPES.contains(field.type()) || field.locked()) {
                throw new IllegalArgumentException("Invalid or locked form fill field");
            }
            Map<String, Object> property = new LinkedHashMap<>();
            property.put("type", field.type());
            if (field.label() != null) property.put("description", field.label());
            if (field.format() != null) {
                if (!Set.of("date", "date-time").contains(field.format())) {
                    throw new IllegalArgumentException("Unsupported form fill field format");
                }
                property.put("format", field.format());
            }
            if (field.choices() != null && !field.choices().isEmpty()) property.put("enum", field.choices());
            if (fields.putIfAbsent(field.code(), property) != null) {
                throw new IllegalArgumentException("Duplicate form fill field");
            }
        }
        Map<String, Object> reviews = new LinkedHashMap<>();
        fields.keySet().forEach(code -> reviews.put(code, Map.of(
                "type", "object", "additionalProperties", false,
                "properties", Map.of(
                        "status", Map.of("type", "string", "enum", List.of("supported", "ambiguous")),
                        "quote", Map.of("type", "string", "minLength", 1, "maxLength", 500),
                        "reason", Map.of("type", "string", "enum", List.of(
                                "multiple_values", "unclear_mapping", "uncertain_date"))),
                "required", List.of("status", "quote"))));
        return Map.of("type", "object", "additionalProperties", false,
                "properties", Map.of("fields", Map.of("type", "object", "properties", fields,
                        "additionalProperties", false),
                        "reviews", Map.of("type", "object", "properties", reviews, "additionalProperties", false),
                        "source", Map.of("type", "string", "description", "Brief source description")),
                "required", List.of("fields", "reviews"));
    }

    @SuppressWarnings("unchecked")
    static void validate(Map<String, Object> schema, Map<String, Object> input) {
        validate(schema, input, null);
    }

    @SuppressWarnings("unchecked")
    static void validate(Map<String, Object> schema, Map<String, Object> input, String sourceText) {
        if (!(input.get("fields") instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException("Form fields must be an object");
        }
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        if (properties == null || !(properties.get("fields") instanceof Map<?, ?> fields)) return;
        if (!(fields.get("properties") instanceof Map<?, ?> allowed)) return;
        if (!Set.of("fields", "source", "reviews").containsAll(input.keySet())
                || (input.containsKey("source") && !(input.get("source") instanceof String))) {
            throw new IllegalArgumentException("Unexpected form fill arguments");
        }
        for (var entry : values.entrySet()) {
            if (!(allowed.get(entry.getKey()) instanceof Map<?, ?> field)) {
                throw new IllegalArgumentException("Unknown form field");
            }
            Object value = entry.getValue();
            boolean valid = switch (String.valueOf(field.get("type"))) {
                case "string" -> value instanceof String;
                case "boolean" -> value instanceof Boolean;
                case "number" -> value instanceof Number n && Double.isFinite(n.doubleValue());
                case "integer" -> value instanceof Number n && Double.isFinite(n.doubleValue())
                        && n.doubleValue() == Math.rint(n.doubleValue());
                default -> false;
            };
            if (!valid || (field.get("enum") instanceof List<?> choices && !choices.contains(value))) {
                throw new IllegalArgumentException("Invalid form field value");
            }
            if ("date".equals(field.get("format"))) LocalDate.parse((String) value);
            if ("date-time".equals(field.get("format"))) {
                java.time.format.DateTimeFormatter.ISO_DATE_TIME.parse((String) value);
            }
        }
        if (!(input.get("reviews") instanceof Map<?, ?> reviews)
                || !reviews.keySet().containsAll(values.keySet())) {
            throw new IllegalArgumentException("Every form value requires source evidence");
        }
        for (var entry : reviews.entrySet()) {
            if (!allowed.containsKey(entry.getKey()) || !(entry.getValue() instanceof Map<?, ?> review)
                    || !Set.of("status", "quote", "reason").containsAll(review.keySet())) {
                throw new IllegalArgumentException("Invalid form evidence field");
            }
            if (!(review.get("quote") instanceof String quote) || quote.isBlank() || quote.length() > 500
                    || sourceText == null || !sourceText.contains(quote)) {
                throw new IllegalArgumentException("Form evidence must quote the original text");
            }
            if ("supported".equals(review.get("status"))) {
                if (!values.containsKey(entry.getKey()) || review.containsKey("reason")) {
                    throw new IllegalArgumentException("Supported evidence requires a form value");
                }
            } else if ("ambiguous".equals(review.get("status"))) {
                if (values.containsKey(entry.getKey()) || !Set.of("multiple_values", "unclear_mapping", "uncertain_date")
                        .contains(String.valueOf(review.get("reason")))) {
                    throw new IllegalArgumentException("Ambiguous fields must remain unfilled with a reason");
                }
            } else {
                throw new IllegalArgumentException("Invalid form evidence status");
            }
        }

    }
}
