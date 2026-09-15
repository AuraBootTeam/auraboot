package com.auraboot.framework.bi.service.impl;

import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.common.constant.ResponseCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves report input values into query filters without changing the stored definition. */
final class ReportParameterBindings {
    private ReportParameterBindings() {}

    static Map<String, Object> apply(Map<String, Object> report, Map<String, Object> supplied) {
        Map<String, Object> inputs = supplied == null ? Map.of() : supplied;
        Object definitions = report.getOrDefault("parameters", List.of());
        if (!(definitions instanceof List<?> parameters)) throw invalid("Invalid report parameters");
        Map<String, Object> sources = copyMap(report.getOrDefault("dataSources", Map.of()));
        Set<String> accepted = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (Object raw : parameters) {
            Map<String, Object> parameter = copyMap(raw);
            String name = string(parameter.get("name"));
            if (name.isBlank() || !names.add(name)) throw invalid("Invalid or duplicate report parameter name");
            String type = string(parameter.get("type"));
            Object value;
            if ("date-range".equals(type)) {
                accepted.add(name + "_start");
                accepted.add(name + "_end");
                String start = string(inputs.get(name + "_start"));
                String end = string(inputs.get(name + "_end"));
                if (start.isBlank() && end.isBlank()) value = null;
                else {
                    LocalDate from = date(start), to = date(end);
                    if (from.isAfter(to)) throw invalid("Report date range is reversed");
                    value = List.of(from.toString(), to.toString());
                }
            } else {
                accepted.add(name);
                value = inputs.containsKey(name) ? inputs.get(name) : parameter.get("defaultValue");
                if (value != null && string(value).isBlank()) value = null;
                if (value != null) value = typedValue(type, value, parameter);
            }
            if (value == null) {
                if (Boolean.TRUE.equals(parameter.get("required"))) throw invalid("Required report parameter is missing: " + name);
                continue;
            }
            Map<String, Object> binding = copyMap(parameter.get("bindTo"));
            String sourceKey = string(binding.get("dataSource"));
            String field = string(binding.get("field"));
            if (field.isBlank() || !sources.containsKey(sourceKey)) throw invalid("Invalid report parameter binding: " + name);
            Map<String, Object> source = copyMap(sources.get(sourceKey));
            if (!"model".equals(source.get("type"))) throw invalid("Report filter parameters require a model data source");
            Object existing = source.getOrDefault("filters", List.of());
            if (!(existing instanceof List<?> filters)) throw invalid("Invalid report source filters");
            List<Object> nextFilters = new ArrayList<>(filters);
            Map<String, Object> filter = new LinkedHashMap<>();
            filter.put("field", field);
            if ("date-range".equals(type)) {
                filter.put("operator", "BETWEEN");
                filter.put("values", value);
            } else {
                String operator = string(binding.get("operator"));
                if (operator.isBlank()) throw invalid("Report parameter operator is required");
                filter.put("operator", operator);
                filter.put("value", value);
            }
            nextFilters.add(filter);
            source.put("filters", nextFilters);
            sources.put(sourceKey, source);
        }
        if (!accepted.containsAll(inputs.keySet())) throw invalid("Unknown report parameter");
        Map<String, Object> result = new LinkedHashMap<>(report);
        result.put("dataSources", sources);
        return result;
    }

    private static Object typedValue(String type, Object value, Map<String, Object> parameter) {
        if (!(value instanceof String) && !(value instanceof Number)) throw invalid("Invalid report parameter value");
        return switch (type) {
            case "text" -> string(value);
            case "date" -> date(string(value)).toString();
            case "number" -> {
                try { yield new BigDecimal(string(value)); }
                catch (NumberFormatException e) { throw invalid("Invalid numeric report parameter"); }
            }
            case "select" -> {
                Object options = parameter.get("options");
                if (!(options instanceof List<?> list) || list.stream().noneMatch(option -> string(copyMap(option).get("value")).equals(string(value)))) {
                    throw invalid("Invalid report parameter option");
                }
                yield string(value);
            }
            default -> throw invalid("Unsupported report parameter type");
        };
    }

    private static LocalDate date(String value) {
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException e) { throw invalid("Invalid report parameter date"); }
    }

    private static Map<String, Object> copyMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw invalid("Invalid report parameter definition");
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static String string(Object value) { return value == null ? "" : value.toString(); }
    private static ValidationException invalid(String message) {
        return new ValidationException(ResponseCode.CommonValidationFailed, message);
    }
}
