package com.auraboot.framework.aurabot.skill.error;

import org.springframework.http.HttpStatus;

import java.util.Locale;

/**
 * Skill SPI error codes per Contract §11.
 *
 * <p>Stored / serialised via {@link #code()} (uppercase, snake-style) — never
 * via {@link #name()} so wire format stays decoupled from enum identifiers.
 */
public enum SkillErrorCode {
    SKILL_NOT_FOUND("SKILL_NOT_FOUND", HttpStatus.NOT_FOUND),
    PARAMS_INVALID("PARAMS_INVALID", HttpStatus.BAD_REQUEST),
    CONFIRM_REQUIRED("CONFIRM_REQUIRED", HttpStatus.UNPROCESSABLE_ENTITY),
    PREVIEW_TOKEN_INVALID("PREVIEW_TOKEN_INVALID", HttpStatus.UNPROCESSABLE_ENTITY),
    PERMISSION_DENIED("PERMISSION_DENIED", HttpStatus.FORBIDDEN),
    UNDO_EXPIRED("UNDO_EXPIRED", HttpStatus.GONE),
    /** Returned with HTTP 200 — body envelope carries prior result + this code. */
    IDEMPOTENCY_REPLAY("IDEMPOTENCY_REPLAY", HttpStatus.OK),
    SKILL_INTERNAL_ERROR("SKILL_INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR),
    STREAMING_NOT_AVAILABLE("STREAMING_NOT_AVAILABLE", HttpStatus.SERVICE_UNAVAILABLE),
    /**
     * Caller invoked {@code POST /skill/dry-run} on a skill whose
     * {@link com.auraboot.framework.aurabot.skill.AuraBotSkill#supportsDryRun()}
     * returns {@code false}. Surfaced as 422 so the FE can disable the preview
     * button instead of treating it as a transient failure.
     */
    DRY_RUN_NOT_SUPPORTED("DRY_RUN_NOT_SUPPORTED", HttpStatus.UNPROCESSABLE_ENTITY);

    private final String code;
    private final HttpStatus httpStatus;

    SkillErrorCode(String code, HttpStatus httpStatus) {
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public String code() {
        return code;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public static SkillErrorCode fromCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("SkillErrorCode code must not be blank");
        }
        String norm = code.trim().toUpperCase(Locale.ROOT);
        for (SkillErrorCode e : values()) {
            if (e.code.equals(norm)) {
                return e;
            }
        }
        throw new IllegalArgumentException("Unknown SkillErrorCode: " + code);
    }
}
