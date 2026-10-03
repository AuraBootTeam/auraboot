package com.auraboot.framework.meta.dto;

import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.common.constant.ResponseCode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Projects only declared localized display metadata; other extension data stays private. */
public final class CommandDescriptionLocalization {
    private CommandDescriptionLocalization() {}

    public static Map<String, String> from(Object value) {
        return from(value, "localizedDescriptions");
    }

    public static Map<String, String> displayNamesFrom(Object value) {
        return from(value, "localizedDisplayNames");
    }

    private static Map<String, String> from(Object value, String field) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> translations)) {
            throw invalid(field);
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : translations.entrySet()) {
            if (!(entry.getKey() instanceof String locale) || locale.isBlank()
                    || !(entry.getValue() instanceof String text)) {
                throw invalid(field);
            }
            result.put(locale, text);
        }
        return Map.copyOf(result);
    }

    private static ValidationException invalid(String field) {
        return new ValidationException(ResponseCode.CommonValidationFailed,
                "Command " + field + " must be a locale-to-string object");
    }
}
