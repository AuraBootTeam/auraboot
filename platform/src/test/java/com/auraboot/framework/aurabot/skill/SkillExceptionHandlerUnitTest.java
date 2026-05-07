package com.auraboot.framework.aurabot.skill;

import com.auraboot.framework.aurabot.skill.error.SkillErrorCode;
import com.auraboot.framework.aurabot.skill.error.SkillExceptionHandler;
import com.auraboot.framework.aurabot.skill.error.SkillSpiException;
import com.auraboot.framework.common.dto.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.ResponseEntity;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit test for {@link SkillExceptionHandler} (Plan §Step 8).
 *
 * <p>Wires the real {@code i18n/aurabot-skill} bundle (via
 * {@link ResourceBundleMessageSource}) so the assertions exercise both the
 * status/code mapping and the bundle key lookup. No Spring context needed —
 * the handler is constructor-injected with the bundle and invoked directly.
 */
class SkillExceptionHandlerUnitTest {

    private SkillExceptionHandler handler;
    private MessageSource messageSource;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource ms = new ResourceBundleMessageSource();
        ms.setBasename("i18n/aurabot-skill");
        ms.setDefaultEncoding("UTF-8");
        ms.setUseCodeAsDefaultMessage(false);
        this.messageSource = ms;
        this.handler = new SkillExceptionHandler(ms);
    }

    @Test
    @DisplayName("SKILL_NOT_FOUND → HTTP 404 + body code SKILL_NOT_FOUND + en message")
    void skillNotFound() {
        SkillSpiException ex = new SkillSpiException(SkillErrorCode.SKILL_NOT_FOUND,
                "skill not found: foo");
        ResponseEntity<ApiResponse<Void>> resp = handler.handleSkillSpiException(ex, Locale.ENGLISH);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        ApiResponse<Void> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo("SKILL_NOT_FOUND");
        assertThat(body.getMessage()).isEqualTo("Skill not found");
    }

    @Test
    @DisplayName("PARAMS_INVALID → HTTP 400 + fieldPath in context + suffix in message")
    void paramsInvalidWithFieldPath() {
        SkillSpiException ex = new SkillSpiException(SkillErrorCode.PARAMS_INVALID,
                "text required", "/text");
        ResponseEntity<ApiResponse<Void>> resp = handler.handleSkillSpiException(ex, Locale.ENGLISH);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        ApiResponse<Void> body = resp.getBody();
        assertThat(body.getCode()).isEqualTo("PARAMS_INVALID");
        assertThat(body.getMessage()).isEqualTo("Invalid parameters (at: /text)");
        assertThat(body.getContext()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, String> ctx = (Map<String, String>) body.getContext();
        assertThat(ctx).containsEntry("fieldPath", "/text");
    }

    @Test
    @DisplayName("CONFIRM_REQUIRED → HTTP 422 + body code CONFIRM_REQUIRED")
    void confirmRequired() {
        SkillSpiException ex = new SkillSpiException(SkillErrorCode.CONFIRM_REQUIRED,
                "confirm needed");
        ResponseEntity<ApiResponse<Void>> resp = handler.handleSkillSpiException(ex, Locale.ENGLISH);

        assertThat(resp.getStatusCode().value()).isEqualTo(422);
        assertThat(resp.getBody().getCode()).isEqualTo("CONFIRM_REQUIRED");
        assertThat(resp.getBody().getMessage()).isEqualTo("Confirmation required for this risk level");
    }

    @Test
    @DisplayName("PREVIEW_TOKEN_INVALID → HTTP 422 + body code PREVIEW_TOKEN_INVALID")
    void previewTokenInvalid() {
        SkillSpiException ex = new SkillSpiException(SkillErrorCode.PREVIEW_TOKEN_INVALID,
                "expired token");
        ResponseEntity<ApiResponse<Void>> resp = handler.handleSkillSpiException(ex, Locale.ENGLISH);

        assertThat(resp.getStatusCode().value()).isEqualTo(422);
        assertThat(resp.getBody().getCode()).isEqualTo("PREVIEW_TOKEN_INVALID");
        assertThat(resp.getBody().getMessage()).isEqualTo("Preview token is invalid or expired");
    }

    @Test
    @DisplayName("PERMISSION_DENIED → HTTP 403 + body code PERMISSION_DENIED")
    void permissionDenied() {
        SkillSpiException ex = new SkillSpiException(SkillErrorCode.PERMISSION_DENIED,
                "no perm");
        ResponseEntity<ApiResponse<Void>> resp = handler.handleSkillSpiException(ex, Locale.ENGLISH);

        assertThat(resp.getStatusCode().value()).isEqualTo(403);
        assertThat(resp.getBody().getCode()).isEqualTo("PERMISSION_DENIED");
        assertThat(resp.getBody().getMessage()).isEqualTo("Missing required permission(s)");
    }

    @Test
    @DisplayName("UNDO_EXPIRED → HTTP 410 + body code UNDO_EXPIRED")
    void undoExpired() {
        SkillSpiException ex = new SkillSpiException(SkillErrorCode.UNDO_EXPIRED,
                "token gone");
        ResponseEntity<ApiResponse<Void>> resp = handler.handleSkillSpiException(ex, Locale.ENGLISH);

        assertThat(resp.getStatusCode().value()).isEqualTo(410);
        assertThat(resp.getBody().getCode()).isEqualTo("UNDO_EXPIRED");
        assertThat(resp.getBody().getMessage()).isEqualTo("Undo token expired or not found");
    }

    @Test
    @DisplayName("IDEMPOTENCY_REPLAY → HTTP 200 + body code IDEMPOTENCY_REPLAY")
    void idempotencyReplay() {
        SkillSpiException ex = new SkillSpiException(SkillErrorCode.IDEMPOTENCY_REPLAY,
                "replay");
        ResponseEntity<ApiResponse<Void>> resp = handler.handleSkillSpiException(ex, Locale.ENGLISH);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().getCode()).isEqualTo("IDEMPOTENCY_REPLAY");
        assertThat(resp.getBody().getMessage()).isEqualTo("Idempotent replay");
    }

    @Test
    @DisplayName("SKILL_INTERNAL_ERROR → HTTP 500 + body code SKILL_INTERNAL_ERROR")
    void skillInternalError() {
        SkillSpiException ex = new SkillSpiException(SkillErrorCode.SKILL_INTERNAL_ERROR,
                "boom", null, new RuntimeException("downstream"));
        ResponseEntity<ApiResponse<Void>> resp = handler.handleSkillSpiException(ex, Locale.ENGLISH);

        assertThat(resp.getStatusCode().value()).isEqualTo(500);
        assertThat(resp.getBody().getCode()).isEqualTo("SKILL_INTERNAL_ERROR");
        assertThat(resp.getBody().getMessage()).isEqualTo("Internal skill error");
    }

    @Test
    @DisplayName("zh_CN locale → Chinese bundle entry resolved")
    void zhLocaleResolves() {
        SkillSpiException ex = new SkillSpiException(SkillErrorCode.SKILL_NOT_FOUND,
                "skill not found");
        ResponseEntity<ApiResponse<Void>> resp = handler.handleSkillSpiException(ex, Locale.SIMPLIFIED_CHINESE);

        assertThat(resp.getBody().getMessage()).isEqualTo("技能不存在");
    }

    @Test
    @DisplayName("missing bundle key → falls back to exception.getMessage()")
    void fallbackOnMissingKey() {
        // STREAMING_NOT_AVAILABLE is in en bundle; simulate missing by using
        // a fresh handler bound to an empty bundle.
        ResourceBundleMessageSource empty = new ResourceBundleMessageSource();
        empty.setBasename("i18n/nonexistent-bundle");
        empty.setUseCodeAsDefaultMessage(false);
        SkillExceptionHandler local = new SkillExceptionHandler(empty);

        SkillSpiException ex = new SkillSpiException(SkillErrorCode.SKILL_INTERNAL_ERROR,
                "fallback message text");
        ResponseEntity<ApiResponse<Void>> resp = local.handleSkillSpiException(ex, Locale.ENGLISH);

        assertThat(resp.getBody().getMessage()).isEqualTo("fallback message text");
    }
}
