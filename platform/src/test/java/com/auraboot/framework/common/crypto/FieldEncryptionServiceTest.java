package com.auraboot.framework.common.crypto;

import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for FieldEncryptionService.
 *
 * @since 6.2.0
 */
@DisplayName("FieldEncryptionService Tests")
class FieldEncryptionServiceTest {

    // A valid AES-256 key (32 bytes, base64 encoded)
    private static final String TEST_KEY = Base64.getEncoder().encodeToString(
            "01229000001229000001229000001_32".getBytes());

    private FieldEncryptionService createService(String key) throws Exception {
        FieldEncryptionService service = new FieldEncryptionService();
        Field keyField = FieldEncryptionService.class.getDeclaredField("base64Key");
        keyField.setAccessible(true);
        keyField.set(service, key);
        // Trigger @PostConstruct manually
        service.init();
        return service;
    }

    // ========== Encrypt / Decrypt roundtrip ==========

    @Test
    @DisplayName("Encrypt then decrypt returns original plaintext")
    void testEncryptDecryptRoundtrip() throws Exception {
        FieldEncryptionService service = createService(TEST_KEY);

        String plaintext = "my-secret-api-key-12345";
        String encrypted = service.encrypt(plaintext);

        assertNotEquals(plaintext, encrypted);
        assertTrue(encrypted.startsWith("ENC:"));
        assertEquals(plaintext, service.decrypt(encrypted));
    }

    @Test
    @DisplayName("Multiple encryptions produce different ciphertexts (unique IV)")
    void testDifferentIvProducesDifferentCiphertext() throws Exception {
        FieldEncryptionService service = createService(TEST_KEY);

        String plaintext = "same-value";
        String enc1 = service.encrypt(plaintext);
        String enc2 = service.encrypt(plaintext);

        assertNotEquals(enc1, enc2, "Different IVs should produce different ciphertexts");
        assertEquals(plaintext, service.decrypt(enc1));
        assertEquals(plaintext, service.decrypt(enc2));
    }

    // ========== Null / Empty safety ==========

    @Test
    @DisplayName("Null input passes through encrypt and decrypt")
    void testNullPassthrough() throws Exception {
        FieldEncryptionService service = createService(TEST_KEY);

        assertNull(service.encrypt(null));
        assertNull(service.decrypt(null));
    }

    @Test
    @DisplayName("Empty string passes through encrypt and decrypt")
    void testEmptyStringPassthrough() throws Exception {
        FieldEncryptionService service = createService(TEST_KEY);

        assertEquals("", service.encrypt(""));
        assertEquals("", service.decrypt(""));
    }

    @Test
    @DisplayName("Blank string passes through")
    void testBlankStringPassthrough() throws Exception {
        FieldEncryptionService service = createService(TEST_KEY);

        assertEquals("   ", service.encrypt("   "));
        assertEquals("   ", service.decrypt("   "));
    }

    // ========== Passthrough mode (no key) ==========

    @Test
    @DisplayName("No key configured — encrypt returns plaintext (passthrough)")
    void testPassthroughEncrypt() throws Exception {
        FieldEncryptionService service = createService("");

        String plaintext = "no-encryption-here";
        assertEquals(plaintext, service.encrypt(plaintext));
        assertFalse(service.isEnabled());
    }

    @Test
    @DisplayName("No key configured — decrypt returns value as-is")
    void testPassthroughDecrypt() throws Exception {
        FieldEncryptionService service = createService("");

        assertEquals("plain", service.decrypt("plain"));
    }

    // ========== Backward compatibility (plain text in DB) ==========

    @Test
    @DisplayName("Decrypt returns plaintext value without ENC: prefix as-is")
    void testDecryptLegacyPlaintext() throws Exception {
        FieldEncryptionService service = createService(TEST_KEY);

        // Simulate old data stored as plaintext in DB
        String legacyValue = "sk-old-api-key-plaintext";
        assertEquals(legacyValue, service.decrypt(legacyValue));
    }

    // ========== Double encryption prevention ==========

    @Test
    @DisplayName("Encrypting already encrypted value returns as-is")
    void testDoubleEncryptionPrevented() throws Exception {
        FieldEncryptionService service = createService(TEST_KEY);

        String encrypted = service.encrypt("secret");
        String doubleEncrypted = service.encrypt(encrypted);

        assertEquals(encrypted, doubleEncrypted, "Should not double-encrypt");
    }

    // ========== isEncrypted ==========

    @Test
    @DisplayName("isEncrypted detects ENC: prefix")
    void testIsEncrypted() throws Exception {
        FieldEncryptionService service = createService(TEST_KEY);

        assertTrue(service.isEncrypted("ENC:abc123"));
        assertFalse(service.isEncrypted("plain-value"));
        assertFalse(service.isEncrypted(null));
        assertFalse(service.isEncrypted(""));
    }

    // ========== Mask ==========

    @Test
    @DisplayName("Mask shows last 4 characters")
    void testMask() throws Exception {
        FieldEncryptionService service = createService(TEST_KEY);

        assertEquals("****c123", service.mask("sk-abc123"));
    }

    @Test
    @DisplayName("Mask short value returns ****")
    void testMaskShortValue() throws Exception {
        FieldEncryptionService service = createService(TEST_KEY);

        assertEquals("****", service.mask("abc"));
        assertEquals("****", service.mask("abcd"));
    }

    @Test
    @DisplayName("Mask null/empty returns ****")
    void testMaskNullEmpty() throws Exception {
        FieldEncryptionService service = createService(TEST_KEY);

        assertEquals("****", service.mask(null));
        assertEquals("****", service.mask(""));
    }

    @Test
    @DisplayName("Mask encrypted value decrypts first then masks")
    void testMaskEncryptedValue() throws Exception {
        FieldEncryptionService service = createService(TEST_KEY);

        String encrypted = service.encrypt("sk-abc123");
        String masked = service.mask(encrypted);

        assertEquals("****c123", masked);
    }

    // ========== maskJsonFields ==========

    @Test
    @DisplayName("maskJsonFields masks specified fields")
    void testMaskJsonFields() throws Exception {
        FieldEncryptionService service = createService(TEST_KEY);

        String json = "{\"apiKey\":\"sk-test-long-key-123\",\"url\":\"https://api.example.com\"}";
        String masked = service.maskJsonFields(json, Set.of("apiKey"));

        assertTrue(masked.contains("****"));
        assertTrue(masked.contains("https://api.example.com"));
        assertFalse(masked.contains("sk-test-long-key-123"));
    }

    @Test
    @DisplayName("maskJsonFields with null/empty JSON returns as-is")
    void testMaskJsonFieldsNullEmpty() throws Exception {
        FieldEncryptionService service = createService(TEST_KEY);

        assertNull(service.maskJsonFields(null, Set.of("apiKey")));
        assertEquals("", service.maskJsonFields("", Set.of("apiKey")));
    }

    @Test
    @DisplayName("maskJsonFields with empty fields set returns JSON as-is")
    void testMaskJsonFieldsEmptyFields() throws Exception {
        FieldEncryptionService service = createService(TEST_KEY);

        String json = "{\"apiKey\":\"secret\"}";
        assertEquals(json, service.maskJsonFields(json, Set.of()));
    }

    // ========== Invalid key ==========

    @Test
    @DisplayName("Invalid key length throws on init")
    void testInvalidKeyLength() {
        assertThrows(IllegalStateException.class, () -> {
            createService(Base64.getEncoder().encodeToString("too-short".getBytes()));
        });
    }

    // ========== Unicode support ==========

    @Test
    @DisplayName("Encrypt/decrypt handles Unicode text")
    void testUnicodeSupport() throws Exception {
        FieldEncryptionService service = createService(TEST_KEY);

        String unicode = "密钥测试 🔐 Schlüssel テスト";
        String encrypted = service.encrypt(unicode);
        assertEquals(unicode, service.decrypt(encrypted));
    }
}
