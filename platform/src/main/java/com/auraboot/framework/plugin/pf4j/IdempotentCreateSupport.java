package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.meta.dto.ValidationResult;
import com.auraboot.framework.meta.exception.ValidationException;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

/** Shared classification for conflicts that make an idempotent create a no-op. */
final class IdempotentCreateSupport {

    private IdempotentCreateSupport() {
    }

    static boolean isUniqueViolation(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof DuplicateKeyException) return true;
            if (current instanceof ValidationException validation && isUniqueValidation(validation)) return true;
        }
        return false;
    }

    private static boolean isUniqueValidation(ValidationException error) {
        ValidationResult result = error.getValidationResult();
        List<String> errors = result != null ? result.getErrors() : null;
        return errors != null && !errors.isEmpty() && errors.stream().allMatch(IdempotentCreateSupport::isUniqueError);
    }

    private static boolean isUniqueError(String error) {
        return error != null && error.startsWith("Field '") && error.contains("' value '")
                && error.endsWith("' already exists");
    }
}
