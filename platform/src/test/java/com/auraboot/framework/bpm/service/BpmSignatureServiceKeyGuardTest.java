package com.auraboot.framework.bpm.service;

import com.auraboot.framework.bpm.mapper.BpmSignatureRecordMapper;
import com.auraboot.framework.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the BPM signature key guard.
 *
 * <p>Security regression: when {@code bpm.signature.secret-key} was unset, the
 * service signed with the source-visible built-in default key
 * ({@code aura-bpm-default-signature-key}), producing forgeable tamper-evidence,
 * and only logged a warning. It now fails closed in non-dev profiles — refusing
 * to produce/verify a signature with the insecure default — while still allowing
 * dev/local/test to run on the default.
 */
@DisplayName("BpmSignatureService signing-key guard")
class BpmSignatureServiceKeyGuardTest {

    private static final String DEFAULT_KEY = "aura-bpm-default-signature-key";
    private static final String STRONG_KEY = "a-very-strong-32plus-char-secret-key-value";

    private BpmSignatureService service(String secretKey, String profile) {
        BpmSignatureService svc = new BpmSignatureService(
                mock(BpmSignatureRecordMapper.class), new ObjectMapper());
        ReflectionTestUtils.setField(svc, "secretKey", secretKey);
        ReflectionTestUtils.setField(svc, "activeProfile", profile);
        return svc;
    }

    @Test
    @DisplayName("default key in a non-dev profile is refused")
    void defaultKeyNonDevRefused() {
        assertThrows(BusinessException.class,
                () -> service(DEFAULT_KEY, "prod").requireUsableSigningKey());
        assertThrows(BusinessException.class,
                () -> service(DEFAULT_KEY, "production").requireUsableSigningKey());
    }

    @Test
    @DisplayName("default key is tolerated in dev/local/test/unset profiles")
    void defaultKeyDevTolerated() {
        assertDoesNotThrow(() -> service(DEFAULT_KEY, "dev").requireUsableSigningKey());
        assertDoesNotThrow(() -> service(DEFAULT_KEY, "local").requireUsableSigningKey());
        assertDoesNotThrow(() -> service(DEFAULT_KEY, "test").requireUsableSigningKey());
        assertDoesNotThrow(() -> service(DEFAULT_KEY, "").requireUsableSigningKey());
    }

    @Test
    @DisplayName("a real (>=32 char) configured key is always allowed")
    void strongKeyAllowed() {
        assertDoesNotThrow(() -> service(STRONG_KEY, "prod").requireUsableSigningKey());
    }

    @Test
    @DisplayName("a too-short key in a non-dev profile is refused")
    void shortKeyNonDevRefused() {
        assertThrows(BusinessException.class,
                () -> service("short-key", "prod").requireUsableSigningKey());
    }
}
