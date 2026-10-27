package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.FieldDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.Set;

/** Adapts strict JSON primitives to existing native validation without changing the input. */
final class JsonFieldValueAdapter {
    private static final Set<String> TYPES = Set.of("string", "text", "integer", "long", "decimal",
            "boolean", "date", "datetime", "time", "enum", "reference", "json", "array");

    private JsonFieldValueAdapter() {}

    static Object toNativeValue(FieldDefinition field, JsonNode value) {
        String type = field.getDataType();
        if (!TYPES.contains(type)) throw invalid(field, "unsupported JSON field type");
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        try {
            return switch (type) {
                case "string", "text", "enum" -> text(field, value);
                case "reference" -> {
                    if (field.getRefTarget() == null) throw invalid(field, "reference target is required");
                    yield text(field, value);
                }
                case "integer" -> number(field, value).intValueExact();
                case "long" -> number(field, value).longValueExact();
                case "decimal" -> decimal(field, value);
                case "boolean" -> {
                    if (!value.isBoolean()) throw invalid(field, "a JSON boolean is required");
                    yield value.booleanValue();
                }
                case "date" -> LocalDate.parse(text(field, value));
                case "datetime" -> Instant.parse(text(field, value));
                case "time" -> LocalTime.parse(text(field, value));
                case "json" -> {
                    if (!value.isObject() && !value.isArray()) throw invalid(field, "a JSON object or array is required");
                    yield value.deepCopy();
                }
                case "array" -> {
                    if (!value.isArray()) throw invalid(field, "a JSON array is required");
                    yield value.deepCopy();
                }
                default -> throw invalid(field, "unsupported JSON field type");
            };
        } catch (ArithmeticException | NumberFormatException | DateTimeParseException failure) {
            throw invalid(field, "value is outside the declared JSON type or numeric precision");
        }
    }

    private static String text(FieldDefinition field, JsonNode value) {
        if (!value.isTextual()) throw invalid(field, "a JSON string is required");
        return value.textValue();
    }

    private static BigDecimal number(FieldDefinition field, JsonNode value) {
        if (!value.isNumber()) throw invalid(field, "a JSON number is required");
        return value.decimalValue();
    }

    private static BigDecimal decimal(FieldDefinition field, JsonNode value) {
        BigDecimal number = number(field, value).stripTrailingZeros();
        Integer precision = field.getPrecision(), scale = field.getScale();
        if ((precision != null && precision < 1) || (scale != null && scale < 0)
                || (precision != null && scale != null && scale > precision)) {
            throw invalid(field, "invalid precision or scale declaration");
        }
        int fraction = Math.max(0, number.scale()), whole = Math.max(0, number.precision() - number.scale());
        if ((scale != null && fraction > scale)
                || (precision != null && (scale == null ? whole + fraction > precision : whole > precision - scale))) {
            throw invalid(field, "value exceeds numeric precision or scale; rounding is forbidden");
        }
        return number;
    }

    private static IllegalArgumentException invalid(FieldDefinition field, String reason) {
        return new IllegalArgumentException("Field '" + field.getCode() + "': " + reason);
    }
}
