package com.auraboot.framework.iot.security;

import com.auraboot.framework.common.crypto.FieldEncryptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Tenant-scoped wrapper around {@link FieldEncryptionService} for IoT
 * credentials. Provides an explicit per-tenant decrypt audit hook (see
 * {@link #decrypt(long, String)}) so that every plaintext exposure is
 * traceable to a tenant id, as required by the design doc §6.
 *
 * <p>The underlying KEK is whatever {@code security.field-encryption.key}
 * provides (AES-256-GCM, ENC: prefix, IV-prefixed Base64). We deliberately
 * do <em>not</em> roll our own AES envelope here — reuse, not reinvent.
 *
 * @since 2.6.0
 */
@Slf4j
@Service
public class IotCredentialEncryptionService {

    private final FieldEncryptionService delegate;

    public IotCredentialEncryptionService(FieldEncryptionService delegate) {
        this.delegate = delegate;
    }

    /**
     * Encrypt the device secret. Returns the {@code ENC:}-prefixed cipher
     * string. Passthrough mode (no platform key configured) is preserved
     * from the underlying service; callers must NOT assume encryption is
     * always active in non-prod profiles.
     */
    public String encrypt(long tenantId, String plainSecret) {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be > 0");
        }
        if (plainSecret == null || plainSecret.isBlank()) {
            return plainSecret;
        }
        String encrypted = delegate.encrypt(plainSecret);
        log.debug("[iot-credential-encrypt] tenant={} encrypted=len{}",
                tenantId, encrypted == null ? 0 : encrypted.length());
        return encrypted;
    }

    /**
     * Decrypt a stored secret. Emits an audit-class log line at INFO so the
     * standard log pipeline can ship credential-read events to the audit
     * trail.
     */
    public String decrypt(long tenantId, String encrypted) {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be > 0");
        }
        if (encrypted == null || encrypted.isBlank()) {
            return encrypted;
        }
        String plain = delegate.decrypt(encrypted);
        // Audit-grade log: NEVER include the plaintext or cipher tail.
        log.info("[iot-credential-audit] decrypt tenant={} cipher_prefix={} encrypted={}",
                tenantId,
                encrypted.length() > 8 ? encrypted.substring(0, 8) : encrypted,
                delegate.isEncrypted(encrypted));
        return plain;
    }

    /** Whether the platform KEK is configured (i.e. encryption is real, not passthrough). */
    public boolean isEnabled() {
        return delegate.isEnabled();
    }
}
