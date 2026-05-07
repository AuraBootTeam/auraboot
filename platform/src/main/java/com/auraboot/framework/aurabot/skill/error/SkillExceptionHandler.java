package com.auraboot.framework.aurabot.skill.error;

import com.auraboot.framework.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Locale;

/**
 * Central wire mapper for {@link SkillSpiException} thrown by the AuraBot
 * Skill SPI controller (Plan §Step 8 / SPI Contract §11).
 *
 * <p><strong>Scope.</strong> Bound to {@code basePackages = aurabot.skill.controller}
 * so it never shadows the module-wide {@code GlobalExceptionHandler}
 * ({@link org.springframework.core.annotation.Order} 100). Local
 * {@link Order} 1 is the conventional precedence for scoped advices in this
 * codebase (mirrors {@code MetaApiExceptionHandler}).
 *
 * <p><strong>Mapping.</strong>
 * <ul>
 *     <li>HTTP status ← {@link SkillErrorCode#httpStatus()}</li>
 *     <li>{@code body.code} ← {@link SkillErrorCode#code()} (uppercase wire form)</li>
 *     <li>{@code body.message} ← {@code messageSource.getMessage("aurabot.skill.error." + name, null, e.getMessage(), locale)}</li>
 *     <li>{@code body.context.fieldPath} ← {@link SkillSpiException#getFieldPath()} when non-null</li>
 * </ul>
 *
 * <p>{@link SkillSpiException} does not carry MessageFormat args today; we
 * pass {@code null} which causes Spring to skip MessageFormat substitution
 * and return the raw bundle entry. Dynamic detail (e.g. the offending skill
 * name) lives in the exception's literal message and surfaces via the
 * fallback path when a bundle entry is missing — adequate for current B6
 * scope; B7+ may upgrade to typed args once the SPI grows resolvable
 * placeholders.
 */
@Slf4j
@Order(1)
@RestControllerAdvice(basePackages = "com.auraboot.framework.aurabot.skill.controller")
public class SkillExceptionHandler {

    private final MessageSource messageSource;

    public SkillExceptionHandler(
            @Qualifier(SkillMessageSourceConfig.BEAN_NAME) MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler(SkillSpiException.class)
    public ResponseEntity<ApiResponse<Void>> handleSkillSpiException(
            SkillSpiException e, Locale locale) {
        SkillErrorCode errorCode = e.getErrorCode();
        HttpStatus status = errorCode.httpStatus();

        Locale resolved = locale == null ? Locale.getDefault() : locale;
        String key = "aurabot.skill.error." + errorCode.name();
        String fallback = e.getMessage() == null ? errorCode.code() : e.getMessage();
        String message = messageSource.getMessage(key, null, fallback, resolved);

        // Append fieldPath into the message suffix as well — the wire context
        // already carries it structurally, but FE clients that surface only
        // `message` (e.g. toast) still get the locator. Keeps the contract
        // both machine- and human-friendly without inventing a new envelope.
        if (e.getFieldPath() != null && !e.getFieldPath().isBlank()) {
            message = message + " (at: " + e.getFieldPath() + ")";
        }

        ApiResponse<Void> body = ApiResponse.error(
                Integer.parseInt(numericFor(status)), message, null);
        // Overwrite the wire `code` with the SPI-specific uppercase identifier
        // so FE switch-cases on a stable string rather than a numeric HTTP
        // status. ApiResponse stores `code` as String; status numeric is only
        // used as a placeholder during construction.
        body.setCode(errorCode.code());

        if (e.getFieldPath() != null && !e.getFieldPath().isBlank()) {
            // Structured locator alongside the message-suffix copy.
            body.setContext(java.util.Map.of("fieldPath", e.getFieldPath()));
        }

        // Internal errors are server-side incidents — log at WARN with stack
        // so the platform observability stack picks them up; everything else
        // is caller-induced and stays at DEBUG to avoid log noise.
        if (errorCode == SkillErrorCode.SKILL_INTERNAL_ERROR) {
            log.warn("Skill SPI internal error code={} fieldPath={}: {}",
                    errorCode.code(), e.getFieldPath(), e.getMessage(), e);
        } else {
            log.debug("Skill SPI typed failure code={} status={} fieldPath={}: {}",
                    errorCode.code(), status.value(), e.getFieldPath(), e.getMessage());
        }

        return ResponseEntity.status(status).body(body);
    }

    /** ApiResponse#error(int,...) takes a numeric placeholder; we overwrite later. */
    private static String numericFor(HttpStatus status) {
        return String.valueOf(status.value());
    }
}
