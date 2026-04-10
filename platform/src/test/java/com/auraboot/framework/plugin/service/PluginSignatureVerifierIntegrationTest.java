package com.auraboot.framework.plugin.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.exception.PluginSignatureException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestPropertySource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for {@link PluginSignatureVerifier}.
 *
 * <p>Tests RSA-SHA256 signature verification of plugin packages using
 * real cryptographic operations (no mocks for crypto).
 *
 * <p>Uses test property {@code aura.plugins.signature.enforce=true} to ensure
 * strict verification mode is tested.
 */
@TestPropertySource(properties = {
        "aura.plugins.signature.enforce=true",
        "aura.plugins.keys-dir=src/test/resources/plugin-keys"
})
class PluginSignatureVerifierIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PluginSignatureVerifier signatureVerifier;

    @Test
    @Order(1)
    @DisplayName("should load built-in official key and external test key on startup")
    void shouldLoadPublisherKeys() {
        // The verifier should have at least 2 keys:
        // 1. auraboot-official (built-in)
        // 2. test-publisher (from external keys dir)
        assertThat(signatureVerifier.getRegisteredKeyCount())
                .as("Should have at least the official key and the test publisher key")
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    @Order(2)
    @DisplayName("should verify valid signed package successfully")
    void shouldVerifyValidSignedPackage() {
        Path signedPackage = Path.of("src/test/resources/plugin-test/signed-package");

        // Should not throw
        assertThatCode(() -> signatureVerifier.verify(signedPackage))
                .doesNotThrowAnyException();
    }

    @Test
    @Order(3)
    @DisplayName("should reject unsigned package when enforcement is enabled")
    void shouldRejectUnsignedPackage() {
        Path unsignedPackage = Path.of("src/test/resources/plugin-test/unsigned-package");

        assertThatThrownBy(() -> signatureVerifier.verify(unsignedPackage))
                .isInstanceOf(PluginSignatureException.class)
                .hasMessageContaining("Missing signature file");
    }

    @Test
    @Order(4)
    @DisplayName("should reject tampered package with mismatched signature")
    void shouldRejectTamperedPackage() {
        Path tamperedPackage = Path.of("src/test/resources/plugin-test/tampered-package");

        assertThatThrownBy(() -> signatureVerifier.verify(tamperedPackage))
                .isInstanceOf(PluginSignatureException.class)
                .hasMessageContaining("Signature verification failed");
    }

    @Test
    @Order(5)
    @DisplayName("should verify dynamically signed content with runtime-registered key")
    void shouldVerifyWithRuntimeRegisteredKey() throws Exception {
        // Generate a fresh keypair
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair keyPair = keyGen.generateKeyPair();

        // Register the public key
        String publisherId = "test-runtime-" + System.currentTimeMillis();
        signatureVerifier.registerPublisherKey(publisherId, keyPair.getPublic());

        // Create a temp directory with signed content
        Path tempDir = Files.createTempDirectory("sig-test-runtime");
        try {
            String pluginJson = "{\"pluginId\":\"runtime-test\",\"namespace\":\"rt\",\"version\":\"1.0.0\"}";
            Files.writeString(tempDir.resolve("plugin.json"), pluginJson, StandardCharsets.UTF_8);

            // Sign the plugin.json
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(keyPair.getPrivate());
            sig.update(pluginJson.getBytes(StandardCharsets.UTF_8));
            byte[] signatureBytes = sig.sign();
            String base64Sig = Base64.getEncoder().encodeToString(signatureBytes);
            Files.writeString(tempDir.resolve("signature.sig"), base64Sig, StandardCharsets.UTF_8);

            // Should verify successfully
            assertThatCode(() -> signatureVerifier.verify(tempDir))
                    .doesNotThrowAnyException();
        } finally {
            // Cleanup temp files
            Files.deleteIfExists(tempDir.resolve("plugin.json"));
            Files.deleteIfExists(tempDir.resolve("signature.sig"));
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    @Order(6)
    @DisplayName("should reject package signed by unknown publisher")
    void shouldRejectUnknownPublisher() throws Exception {
        // Generate a keypair NOT registered with the verifier
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair unknownKeyPair = keyGen.generateKeyPair();

        Path tempDir = Files.createTempDirectory("sig-test-unknown");
        try {
            String pluginJson = "{\"pluginId\":\"unknown-pub\",\"namespace\":\"up\",\"version\":\"1.0.0\"}";
            Files.writeString(tempDir.resolve("plugin.json"), pluginJson, StandardCharsets.UTF_8);

            // Sign with unregistered key
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(unknownKeyPair.getPrivate());
            sig.update(pluginJson.getBytes(StandardCharsets.UTF_8));
            byte[] signatureBytes = sig.sign();
            Files.writeString(tempDir.resolve("signature.sig"),
                    Base64.getEncoder().encodeToString(signatureBytes), StandardCharsets.UTF_8);

            assertThatThrownBy(() -> signatureVerifier.verify(tempDir))
                    .isInstanceOf(PluginSignatureException.class)
                    .hasMessageContaining("Signature verification failed");
        } finally {
            Files.deleteIfExists(tempDir.resolve("plugin.json"));
            Files.deleteIfExists(tempDir.resolve("signature.sig"));
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    @Order(7)
    @DisplayName("should reject invalid Base64 in signature file")
    void shouldRejectInvalidBase64Signature() throws Exception {
        Path tempDir = Files.createTempDirectory("sig-test-bad-b64");
        try {
            Files.writeString(tempDir.resolve("plugin.json"),
                    "{\"pluginId\":\"bad-sig\",\"namespace\":\"bs\",\"version\":\"1.0.0\"}",
                    StandardCharsets.UTF_8);
            Files.writeString(tempDir.resolve("signature.sig"),
                    "this-is-not-valid-base64!!!@@@", StandardCharsets.UTF_8);

            assertThatThrownBy(() -> signatureVerifier.verify(tempDir))
                    .isInstanceOf(PluginSignatureException.class)
                    .hasMessageContaining("base64");
        } finally {
            Files.deleteIfExists(tempDir.resolve("plugin.json"));
            Files.deleteIfExists(tempDir.resolve("signature.sig"));
            Files.deleteIfExists(tempDir);
        }
    }

    @Test
    @Order(8)
    @DisplayName("should parse PEM public key correctly")
    void shouldParsePemPublicKey() throws Exception {
        ClassPathResource resource = new ClassPathResource("plugin-keys/auraboot-official.pub");
        try (InputStream is = resource.getInputStream()) {
            String pem = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            PublicKey key = PluginSignatureVerifier.parsePemPublicKey(pem);

            assertThat(key).isNotNull();
            assertThat(key.getAlgorithm()).isEqualTo("RSA");
            assertThat(key.getEncoded()).isNotEmpty();
        }
    }

    @Test
    @Order(9)
    @DisplayName("should reject invalid PEM content")
    void shouldRejectInvalidPem() {
        assertThatThrownBy(() -> PluginSignatureVerifier.parsePemPublicKey("not a pem key"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid PEM public key");
    }

    @Test
    @Order(10)
    @DisplayName("should enforce signature by default")
    void shouldBeEnforcedByDefault() {
        assertThat(signatureVerifier.isEnforced()).isTrue();
    }

    @Test
    @Order(11)
    @DisplayName("should reject null publisher ID in registerPublisherKey")
    void shouldRejectNullPublisherId() {
        KeyPairGenerator keyGen;
        try {
            keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(2048);
            PublicKey key = keyGen.generateKeyPair().getPublic();

            assertThatThrownBy(() -> signatureVerifier.registerPublisherKey(null, key))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Publisher ID must not be blank");

            assertThatThrownBy(() -> signatureVerifier.registerPublisherKey("", key))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Publisher ID must not be blank");
        } catch (NoSuchAlgorithmException e) {
            fail("RSA algorithm not available", e);
        }
    }

    @Test
    @Order(12)
    @DisplayName("should reject null public key in registerPublisherKey")
    void shouldRejectNullPublicKey() {
        assertThatThrownBy(() -> signatureVerifier.registerPublisherKey("some-id", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Public key must not be null");
    }
}
