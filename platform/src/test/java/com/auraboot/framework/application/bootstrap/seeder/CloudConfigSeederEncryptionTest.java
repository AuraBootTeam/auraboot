package com.auraboot.framework.application.bootstrap.seeder;

import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CloudConfigSeeder — sensitive field encryption before INSERT")
class CloudConfigSeederEncryptionTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private FieldEncryptionService fieldEncryptionService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private CloudConfigSeeder seeder;

    @BeforeEach
    void setUp() {
        seeder = new CloudConfigSeeder(jdbcTemplate, fieldEncryptionService, objectMapper);
    }

    @Test
    @DisplayName("encryptSensitiveFields wraps apiKey value via FieldEncryptionService")
    void encryptsApiKey() throws Exception {
        // Stub encryption to return ENC:<value> marker so we can verify it was called
        when(fieldEncryptionService.encrypt("sk-cp-PLAINTEXT"))
                .thenReturn("ENC:simulated-ciphertext");

        String input = "{\"apiKey\":\"sk-cp-PLAINTEXT\",\"baseUrl\":\"https://x\",\"displayName\":\"X\"}";
        String out = seeder.encryptSensitiveFields(input);

        JsonNode node = objectMapper.readTree(out);
        assertEquals("ENC:simulated-ciphertext", node.get("apiKey").asText());
        assertEquals("https://x", node.get("baseUrl").asText(), "non-sensitive field unchanged");
        assertEquals("X", node.get("displayName").asText());
    }

    @Test
    @DisplayName("encryptSensitiveFields wraps multiple sensitive fields when present")
    void encryptsMultipleSensitiveFields() throws Exception {
        lenient().when(fieldEncryptionService.encrypt(anyString())).thenAnswer(inv -> {
            String v = inv.getArgument(0);
            return "ENC:" + v;
        });

        String input = "{\"apiKey\":\"k1\",\"secretKey\":\"k2\",\"accessToken\":\"k3\",\"baseUrl\":\"https://x\"}";
        String out = seeder.encryptSensitiveFields(input);

        JsonNode node = objectMapper.readTree(out);
        assertTrue(node.get("apiKey").asText().startsWith("ENC:"));
        assertTrue(node.get("secretKey").asText().startsWith("ENC:"));
        assertTrue(node.get("accessToken").asText().startsWith("ENC:"));
        assertEquals("https://x", node.get("baseUrl").asText());
    }

    @Test
    @DisplayName("encryptSensitiveFields returns input unchanged when no sensitive fields present")
    void noSensitiveFields() {
        String input = "{\"displayName\":\"X\",\"baseUrl\":\"https://x\",\"models\":[\"a\",\"b\"]}";
        String out = seeder.encryptSensitiveFields(input);
        assertEquals(input, out);
    }

    @Test
    @DisplayName("encryptSensitiveFields passthrough when FieldEncryptionService returns input unchanged (no key configured)")
    void passthroughWhenEncryptionDisabled() {
        // Simulate FieldEncryptionService.encrypt in passthrough mode (no key) — returns input verbatim
        when(fieldEncryptionService.encrypt("sk-plain")).thenReturn("sk-plain");

        String input = "{\"apiKey\":\"sk-plain\",\"baseUrl\":\"https://x\"}";
        String out = seeder.encryptSensitiveFields(input);
        // Because no field value actually changed, we expect the original JSON string back
        assertEquals(input, out, "passthrough must preserve input byte-for-byte");
    }

    @Test
    @DisplayName("encryptSensitiveFields skips empty/blank sensitive values (don't encrypt nothing)")
    void skipsBlankSensitiveValues() throws Exception {
        // Note: lenient() because some seed rows have apiKey="" (no key configured)
        // We must NOT call encrypt() on empty values.
        String input = "{\"apiKey\":\"\",\"baseUrl\":\"https://x\"}";
        String out = seeder.encryptSensitiveFields(input);

        JsonNode node = objectMapper.readTree(out);
        assertEquals("", node.get("apiKey").asText());
    }

    @Test
    @DisplayName("encryptSensitiveFields returns input verbatim on malformed JSON (logged warn)")
    void malformedJsonReturnedUnchanged() {
        String input = "{not valid json";
        String out = seeder.encryptSensitiveFields(input);
        assertEquals(input, out);
    }

    @Test
    @DisplayName("encryptSensitiveFields handles null/blank input safely")
    void nullAndBlank() {
        assertEquals(null, seeder.encryptSensitiveFields(null));
        assertEquals("", seeder.encryptSensitiveFields(""));
        assertEquals("   ", seeder.encryptSensitiveFields("   "));
    }

    @Test
    @DisplayName("encryptSensitiveFields preserves non-textual sensitive fields (e.g. apiKey:null) — defense in depth")
    void preservesNonTextualValues() throws Exception {
        // If a seed row somehow had apiKey:null, we must not crash trying to encrypt it
        String input = "{\"apiKey\":null,\"baseUrl\":\"https://x\"}";
        String out = seeder.encryptSensitiveFields(input);
        JsonNode node = objectMapper.readTree(out);
        assertTrue(node.get("apiKey").isNull());
    }
}
