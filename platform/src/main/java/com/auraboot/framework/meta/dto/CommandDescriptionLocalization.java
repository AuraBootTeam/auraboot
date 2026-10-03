package com.auraboot.framework.meta.dto;

import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.common.constant.ResponseCode;
import java.util.LinkedHashMap;
import java.util.Map;

/** Projects only declared display descriptions; other extension data stays private. */
public final class CommandDescriptionLocalization {
    private CommandDescriptionLocalization() {}

    public static Map<String, String> from(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> translations)) {
            throw invalid();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : translations.entrySet()) {
            if (!(entry.getKey() instanceof String locale) || locale.isBlank()
                    || !(entry.getValue() instanceof String text)) {
                throw invalid();
            }
            result.put(locale, text);
        }
        return Map.copyOf(result);
    }

    private static ValidationException invalid() {
        return new ValidationException(ResponseCode.CommonValidationFailed,
                "Command localizedDescriptions must be a locale-to-string object");
    }
}
