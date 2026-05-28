package com.auraboot.framework.iot.security;

import com.auraboot.framework.common.crypto.FieldEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IotCredentialEncryptionServiceTest {

    private FieldEncryptionService fes;
    private IotCredentialEncryptionService svc;

    @BeforeEach
    void setUp() {
        fes = new FieldEncryptionService();
        // 32-byte AES-256 key.
        byte[] k = new byte[32];
        for (int i = 0; i < 32; i++) k[i] = (byte) (i + 7);
        ReflectionTestUtils.setField(fes, "base64Key", Base64.getEncoder().encodeToString(k));
        ReflectionTestUtils.invokeMethod(fes, "init");
        svc = new IotCredentialEncryptionService(fes);
    }

    @Test
    void encrypt_decrypt_roundTrip() {
        String enc = svc.encrypt(42L, "super-secret-token-abc");
        assertThat(enc).isNotEqualTo("super-secret-token-abc");
        assertThat(enc).startsWith("ENC:");
        assertThat(svc.decrypt(42L, enc)).isEqualTo("super-secret-token-abc");
    }

    @Test
    void encrypt_rejectsTenantZero() {
        assertThatThrownBy(() -> svc.encrypt(0L, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.decrypt(-1L, "ENC:abc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encrypt_passthrough_onBlank() {
        assertThat(svc.encrypt(1L, null)).isNull();
        assertThat(svc.encrypt(1L, "")).isEqualTo("");
        assertThat(svc.decrypt(1L, null)).isNull();
    }

    @Test
    void encrypt_passthrough_whenKeyUnconfigured() {
        FieldEncryptionService noKey = new FieldEncryptionService();
        ReflectionTestUtils.invokeMethod(noKey, "init"); // no key
        IotCredentialEncryptionService passthrough = new IotCredentialEncryptionService(noKey);
        assertThat(passthrough.isEnabled()).isFalse();
        // Without a key, encrypt returns the plaintext.
        assertThat(passthrough.encrypt(1L, "plain")).isEqualTo("plain");
    }

    @Test
    void encrypt_isNonDeterministic() {
        // AES-GCM with random IV → distinct ciphertext for the same plaintext.
        String a = svc.encrypt(1L, "same");
        String b = svc.encrypt(1L, "same");
        assertThat(a).isNotEqualTo(b);
        assertThat(svc.decrypt(1L, a)).isEqualTo("same");
        assertThat(svc.decrypt(1L, b)).isEqualTo("same");
    }

    @Test
    void isEnabled_reflectsUnderlyingService() {
        assertThat(svc.isEnabled()).isTrue();
    }
}
