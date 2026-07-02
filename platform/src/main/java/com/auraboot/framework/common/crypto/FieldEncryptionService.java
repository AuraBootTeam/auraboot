package com.auraboot.framework.common.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Iterator;
import java.util.Set;

/**
 * AES-256-GCM field-level encryption service.
 * <p>
 * Encrypted format: {@code ENC:} + Base64(12-byte-IV + ciphertext + 16-byte-GCM-tag)
 * <p>
 * When no encryption key is configured, operates in transparent passthrough mode
 * (data stored/returned as-is). Existing plaintext data (no {@code ENC:} prefix)
 * is returned as-is on decrypt, enabling zero-downtime migration.
 *
 * @since 6.2.0
 */
@Slf4j
@Service
public class FieldEncryptionService {

    private static final String PREFIX = "ENC:";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final String MASK = "****";

    private final SecureRandom secureRandom = new SecureRandom();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${security.field-encryption.key:}")
    private String base64Key;

    @Value("${spring.profiles.active:}")
    private String activeProfile;

    private SecretKeySpec secretKey;

    /** Dev-ish profiles (dev/local/test or unset) tolerate passthrough (plaintext) mode. */
    private boolean isDevProfile() {
        if (activeProfile == null || activeProfile.isBlank()) {
            return true;
        }
        String p = activeProfile.toLowerCase();
        return p.contains("dev") || p.contains("local") || p.contains("test");
    }

    @PostConstruct
    public void init() {
        if (base64Key == null || base64Key.isBlank()) {
            log.warn("security.field-encryption.key is not configured — " +
                    "field encryption is DISABLED (passthrough mode). " +
                    "Set FIELD_ENCRYPTION_KEY env var for production.");
            return;
        }

        try {
            byte[] keyBytes = Base64.getDecoder().decode(base64Key);
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException(
                        "AES-256 key must be exactly 32 bytes, got " + keyBytes.length);
            }
            secretKey = new SecretKeySpec(keyBytes, "AES");
            log.info("Field encryption initialized with AES-256-GCM");
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Invalid field-encryption key: " + e.getMessage(), e);
        }
    }

    /**
     * Encrypt a plaintext value. Returns {@code ENC:}<base64> string.
     * If no key is configured, returns the value unchanged (passthrough).
     *
     * @param plaintext the value to encrypt; null/blank values pass through
     * @return encrypted string or original value in passthrough mode
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return plaintext;
        }
        if (secretKey == null) {
            // Fail closed in non-dev: refuse to store a sensitive value in plaintext
            // when no encryption key is configured. Dev/local/test tolerate passthrough.
            if (isDevProfile()) {
                return plaintext; // passthrough in dev/local/test
            }
            throw new IllegalStateException(
                    "Field encryption key is not configured (security.field-encryption.key) — "
                    + "refusing to store a sensitive value in plaintext in a non-dev profile. "
                    + "Set the FIELD_ENCRYPTION_KEY environment variable.");
        }
        if (isEncrypted(plaintext)) {
            return plaintext; // already encrypted
        }

        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // IV + ciphertext (includes GCM tag appended by JCE)
            ByteBuffer buffer = ByteBuffer.allocate(Math.addExact(iv.length, ciphertext.length));
            buffer.put(iv);
            buffer.put(ciphertext);

            return PREFIX + Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /**
     * Decrypt a value. If the value has no {@code ENC:} prefix, returns it as-is
     * (backward compatibility with existing plaintext data).
     *
     * @param value the value to decrypt; null/blank values pass through
     * @return decrypted plaintext or original value if not encrypted
     */
    public String decrypt(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        if (!isEncrypted(value)) {
            return value; // plaintext — backward compatible
        }
        if (secretKey == null) {
            log.warn("Cannot decrypt ENC: value — no encryption key configured");
            return value;
        }

        try {
            byte[] decoded = Base64.getDecoder().decode(value.substring(PREFIX.length()));

            ByteBuffer buffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[GCM_IV_LENGTH];
            buffer.get(iv);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }

    /**
     * Check if a value is encrypted (has the {@code ENC:} prefix).
     */
    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    /**
     * Mask a sensitive value for display: {@code "****" + last 4 chars}.
     *
     * @param value the value to mask (plaintext or encrypted)
     * @return masked string, or {@code "****"} if value is too short
     */
    public String mask(String value) {
        if (value == null || value.isBlank()) {
            return MASK;
        }
        // Decrypt first if encrypted, so we mask the real value
        String plain = decrypt(value);
        if (plain.length() <= 4) {
            return MASK;
        }
        return MASK + plain.substring(plain.length() - 4);
    }

    /**
     * Mask specified fields within a JSON string.
     * <p>
     * For example, given JSON {@code {"apiKey":"sk-abc123","url":"https://..."}}
     * and fields {@code {"apiKey"}}, returns {@code {"apiKey":"****c123","url":"https://..."}}.
     *
     * @param json   the JSON string to process
     * @param fields set of field names to mask
     * @return JSON string with specified fields masked, or original if parsing fails
     */
    public String maskJsonFields(String json, Set<String> fields) {
        if (json == null || json.isBlank() || fields == null || fields.isEmpty()) {
            return json;
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isObject()) {
                return json;
            }

            ObjectNode obj = (ObjectNode) root;
            for (String field : fields) {
                if (obj.has(field) && !obj.get(field).isNull()) {
                    String originalValue = obj.get(field).asText();
                    obj.put(field, mask(originalValue));
                }
            }

            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("Failed to mask JSON fields: {}", e.getMessage());
            return json;
        }
    }

    /**
     * Encrypt specified fields within a JSON string, keeping the blob valid JSON.
     * <p>
     * For example, given JSON {@code {"password":"secret","username":"alice"}}
     * and fields {@code {"password"}}, returns
     * {@code {"password":"ENC:...","username":"alice"}}.
     * Non-existent fields and null values are left untouched.
     * If no encryption key is configured, the JSON is returned unchanged.
     *
     * @param json   the JSON string to process; null/blank values pass through
     * @param fields set of field names whose values should be encrypted
     * @return JSON string with specified fields encrypted, or original if parsing fails
     */
    public String encryptJsonFields(String json, Set<String> fields) {
        if (json == null || json.isBlank() || fields == null || fields.isEmpty()) {
            return json;
        }
        if (secretKey == null) {
            return json; // passthrough — encryption disabled
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isObject()) {
                return json;
            }

            ObjectNode obj = (ObjectNode) root;
            for (String field : fields) {
                if (obj.has(field) && !obj.get(field).isNull()) {
                    String plainValue = obj.get(field).asText();
                    if (!isEncrypted(plainValue)) {
                        obj.put(field, encrypt(plainValue));
                    }
                }
            }

            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("Failed to encrypt JSON fields: {}", e.getMessage());
            return json;
        }
    }

    /**
     * Decrypt specified fields within a JSON string.
     * <p>
     * The inverse of {@link #encryptJsonFields}: for each named field whose
     * value starts with {@code ENC:}, the value is decrypted in place.
     * Fields without the prefix (plaintext or missing) are left untouched,
     * enabling zero-downtime migration from unencrypted rows.
     *
     * @param json   the JSON string to process; null/blank values pass through
     * @param fields set of field names whose values should be decrypted
     * @return JSON string with specified fields decrypted, or original if parsing fails
     */
    public String decryptJsonFields(String json, Set<String> fields) {
        if (json == null || json.isBlank() || fields == null || fields.isEmpty()) {
            return json;
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isObject()) {
                return json;
            }

            ObjectNode obj = (ObjectNode) root;
            boolean changed = false;
            for (String field : fields) {
                if (obj.has(field) && !obj.get(field).isNull()) {
                    String value = obj.get(field).asText();
                    if (isEncrypted(value)) {
                        obj.put(field, decrypt(value));
                        changed = true;
                    }
                }
            }

            return changed ? objectMapper.writeValueAsString(obj) : json;
        } catch (Exception e) {
            log.warn("Failed to decrypt JSON fields: {}", e.getMessage());
            return json;
        }
    }

    /**
     * Check if encryption is enabled (key is configured).
     */
    public boolean isEnabled() {
        return secretKey != null;
    }
}
