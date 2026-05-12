package com.auraboot.framework.plugin.service;

import com.auraboot.framework.common.util.PathSafetyUtils;
import com.auraboot.framework.plugin.exception.PluginSignatureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Verifies RSA-SHA256 signatures on plugin packages (.abp / ZIP).
 *
 * <h3>Signature format</h3>
 * The package directory must contain a {@code signature.sig} file whose content is
 * the Base64-encoded RSA-SHA256 signature of {@code plugin.zip} (or, for directory-based
 * installs, of {@code plugin.json}).
 *
 * <h3>Publisher key registry</h3>
 * <ul>
 *   <li>Built-in: AuraBoot official public key embedded in classpath
 *       ({@code plugin-keys/auraboot-official.pub})</li>
 *   <li>Custom: additional keys can be registered at runtime via
 *       {@link #registerPublisherKey(String, PublicKey)}</li>
 *   <li>External directory: keys can be loaded from an external directory
 *       configured via {@code aura.plugins.keys-dir}</li>
 * </ul>
 *
 * <h3>Enforcement</h3>
 * Signature verification is <b>enforced by default</b>. When
 * {@code aura.plugins.signature.enforce=true} (default), a missing or invalid
 * signature causes installation to be rejected. Set to {@code false} only in
 * development environments where unsigned local plugins are used.
 */
@Slf4j
@Component
public class PluginSignatureVerifier {

    private static final String ALGORITHM = "SHA256withRSA";
    private static final String KEY_ALGORITHM = "RSA";
    private static final String OFFICIAL_KEY_RESOURCE = "plugin-keys/auraboot-official.pub";
    private static final String OFFICIAL_PUBLISHER_ID = "auraboot-official";

    private static final String SIGNATURE_FILE = "signature.sig";
    private static final String PLUGIN_ZIP_FILE = "plugin.zip";
    private static final String PLUGIN_JSON_FILE = "plugin.json";

    @Value("${aura.plugins.signature.enforce:true}")
    private boolean enforceSignature;

    @Value("${aura.plugins.keys-dir:}")
    private String externalKeysDir;

    /**
     * Publisher key registry: publisherId -> PublicKey.
     * Thread-safe via synchronized access in registration methods.
     */
    private final Map<String, PublicKey> publisherKeys =
            Collections.synchronizedMap(new LinkedHashMap<>());

    @PostConstruct
    void init() {
        loadBuiltinKeys();
        loadExternalKeys();
        log.info("Plugin signature verifier initialized: enforce={}, publishers={}",
                enforceSignature, publisherKeys.keySet());
    }

    /**
     * Verify the signature of a plugin package directory.
     *
     * <p>Looks for {@code signature.sig} in the directory. The signed payload is
     * {@code plugin.zip} if it exists, otherwise {@code plugin.json}.
     *
     * @param packageDir the extracted package directory
     * @throws PluginSignatureException if verification fails and enforcement is enabled
     */
    public void verify(Path packageDir) {
        packageDir = PathSafetyUtils.requireExistingDirectory(packageDir, "plugin package directory");
        Path sigFile = PathSafetyUtils.requireSafeChild(packageDir, SIGNATURE_FILE, "signature file");

        if (!Files.exists(sigFile)) {
            if (enforceSignature) {
                throw new PluginSignatureException(
                        "Missing signature file (signature.sig) in package directory: " + packageDir);
            }
            log.warn("No signature.sig found in {}; skipping verification (enforce=false)", packageDir);
            return;
        }

        Path payloadFile = resolvePayloadFile(packageDir);
        if (payloadFile == null) {
            throw new PluginSignatureException(
                    "Cannot determine signed payload: neither plugin.zip nor plugin.json found in " + packageDir);
        }

        byte[] signatureBytes = readSignatureFile(sigFile);
        byte[] payloadBytes = readPayloadFile(payloadFile);

        boolean verified = verifyAgainstAnyPublisher(payloadBytes, signatureBytes);
        if (!verified) {
            throw new PluginSignatureException(
                    "Signature verification failed: signature.sig does not match " + payloadFile.getFileName()
                            + ". The package may have been tampered with or signed by an untrusted publisher.");
        }

        log.info("Signature verified for package: {}", packageDir.getFileName());
    }

    /**
     * Check if signature enforcement is enabled.
     */
    public boolean isEnforced() {
        return enforceSignature;
    }

    /**
     * Register a publisher public key at runtime.
     *
     * @param publisherId unique publisher identifier
     * @param publicKey   RSA public key
     */
    public void registerPublisherKey(String publisherId, PublicKey publicKey) {
        if (publisherId == null || publisherId.isBlank()) {
            throw new IllegalArgumentException("Publisher ID must not be blank");
        }
        if (publicKey == null) {
            throw new IllegalArgumentException("Public key must not be null");
        }
        publisherKeys.put(publisherId, publicKey);
        log.info("Registered publisher key: {}", publisherId);
    }

    /**
     * Get the number of registered publisher keys.
     */
    public int getRegisteredKeyCount() {
        return publisherKeys.size();
    }

    // ==================== Internal Methods ====================

    private void loadBuiltinKeys() {
        try {
            ClassPathResource resource = new ClassPathResource(OFFICIAL_KEY_RESOURCE);
            if (resource.exists()) {
                try (InputStream is = resource.getInputStream()) {
                    String pem = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    PublicKey key = parsePemPublicKey(pem);
                    publisherKeys.put(OFFICIAL_PUBLISHER_ID, key);
                    log.info("Loaded built-in AuraBoot official public key");
                }
            } else {
                log.warn("Built-in official public key not found at classpath:{}", OFFICIAL_KEY_RESOURCE);
            }
        } catch (Exception e) {
            // CATCH: non-transactional, startup initialization — log and continue
            log.error("Failed to load built-in official public key: {}", e.getMessage(), e);
        }
    }

    private void loadExternalKeys() {
        if (externalKeysDir == null || externalKeysDir.isBlank()) {
            return;
        }

        Path keysPath = PathSafetyUtils.normalizeAbsolute(Path.of(externalKeysDir), "external keys directory");
        if (!Files.isDirectory(keysPath)) {
            log.warn("External keys directory does not exist: {}", externalKeysDir);
            return;
        }

        try (var stream = Files.list(keysPath)) {
            stream.filter(p -> p.toString().endsWith(".pub") || p.toString().endsWith(".pem"))
                    .forEach(keyFile -> {
                        try {
                            String pem = Files.readString(keyFile, StandardCharsets.UTF_8);
                            PublicKey key = parsePemPublicKey(pem);
                            String publisherId = keyFile.getFileName().toString()
                                    .replaceAll("\\.(pub|pem)$", "");
                            publisherKeys.put(publisherId, key);
                            log.info("Loaded external publisher key: {}", publisherId);
                        } catch (Exception e) {
                            // CATCH: non-transactional, file IO during startup
                            log.error("Failed to load key file {}: {}", keyFile, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            // CATCH: non-transactional, file IO during startup
            log.error("Failed to list external keys directory: {}", e.getMessage(), e);
        }
    }

    private Path resolvePayloadFile(Path packageDir) {
        Path zipFile = PathSafetyUtils.requireSafeChild(packageDir, PLUGIN_ZIP_FILE, "plugin payload zip");
        if (Files.exists(zipFile)) {
            return zipFile;
        }

        Path jsonFile = PathSafetyUtils.requireSafeChild(packageDir, PLUGIN_JSON_FILE, "plugin payload manifest");
        if (Files.exists(jsonFile)) {
            return jsonFile;
        }

        return null;
    }

    private byte[] readSignatureFile(Path sigFile) {
        try {
            String sigContent = Files.readString(sigFile, StandardCharsets.UTF_8).trim();
            return Base64.getDecoder().decode(sigContent);
        } catch (IOException e) {
            throw new PluginSignatureException("Failed to read signature file: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw new PluginSignatureException("Invalid Base64 in signature file: " + e.getMessage(), e);
        }
    }

    private byte[] readPayloadFile(Path payloadFile) {
        try {
            return Files.readAllBytes(payloadFile);
        } catch (IOException e) {
            throw new PluginSignatureException("Failed to read payload file: " + e.getMessage(), e);
        }
    }

    /**
     * Try verifying against all registered publisher keys.
     * Returns true if ANY key verifies successfully.
     */
    private boolean verifyAgainstAnyPublisher(byte[] payload, byte[] signatureBytes) {
        for (Map.Entry<String, PublicKey> entry : publisherKeys.entrySet()) {
            try {
                Signature sig = Signature.getInstance(ALGORITHM);
                sig.initVerify(entry.getValue());
                sig.update(payload);
                if (sig.verify(signatureBytes)) {
                    log.debug("Signature matched publisher: {}", entry.getKey());
                    return true;
                }
            } catch (Exception e) {
                log.debug("Signature check failed for publisher {}: {}", entry.getKey(), e.getMessage());
            }
        }
        return false;
    }

    /**
     * Parse a PEM-encoded public key into a {@link PublicKey} object.
     */
    static PublicKey parsePemPublicKey(String pem) {
        try {
            String base64 = pem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] keyBytes = Base64.getDecoder().decode(base64);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance(KEY_ALGORITHM);
            return keyFactory.generatePublic(spec);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid PEM public key: " + e.getMessage(), e);
        }
    }
}
