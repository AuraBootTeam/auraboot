package com.auraboot.framework.branding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

final class CommercialLicenseVerifier {

    static final String OFFLINE_SIGNATURE = "offline-signature";

    private static final Set<String> LICENSE_FIELDS = Set.of(
            "licenseVersion",
            "customer",
            "edition",
            "release",
            "platformVersion",
            "orderRef",
            "issuedAt",
            "upgradeUntil",
            "supportUntil",
            "features",
            "excludedFeatures",
            "runtimeEnforcement");
    private static final Set<String> SIGNATURE_FIELDS = Set.of(
            "signatureVersion",
            "algorithm",
            "keyId",
            "signedArtifact",
            "signedArtifactSha256",
            "signature");

    private CommercialLicenseVerifier() {
    }

    static void verifyIfRequired(
            Environment environment,
            ObjectMapper objectMapper,
            String edition,
            String orderReference) {
        String enforcement = property(environment,
                "AURABOOT_COMMERCIAL_LICENSE_ENFORCEMENT", "none");
        if (enforcement.isEmpty() || "none".equals(enforcement)) {
            return;
        }
        if (!OFFLINE_SIGNATURE.equals(enforcement)) {
            throw new IllegalStateException(
                    "Unsupported commercial License enforcement mode: " + enforcement);
        }

        String licensePath = requiredProperty(environment, "AURABOOT_COMMERCIAL_LICENSE_PATH");
        String signaturePath = requiredProperty(
                environment, "AURABOOT_COMMERCIAL_LICENSE_SIGNATURE_PATH");
        String publicKeyPath = requiredProperty(
                environment, "AURABOOT_COMMERCIAL_LICENSE_PUBLIC_KEY_PATH");
        String expectedKeyId = requiredProperty(
                environment, "AURABOOT_COMMERCIAL_LICENSE_KEY_ID");
        String expectedCustomer = requiredProperty(
                environment, "AURABOOT_COMMERCIAL_LICENSE_CUSTOMER");
        String expectedVersion = property(environment, "AURABOOT_VERSION", "");
        if (!StringUtils.hasText(expectedVersion)) {
            expectedVersion = property(environment, "APP_VERSION", "");
        }
        if (!StringUtils.hasText(expectedVersion)) {
            throw new IllegalStateException(
                    "AURABOOT_VERSION or APP_VERSION is required for offline License verification.");
        }

        try {
            byte[] licenseBytes = regularFile(Path.of(licensePath), "Commercial License");
            byte[] signatureBytes = regularFile(
                    Path.of(signaturePath), "Commercial License signature");
            byte[] publicKeyBytes = regularFile(
                    Path.of(publicKeyPath), "Commercial License public key");
            JsonNode envelope = objectMapper.readTree(signatureBytes);
            requireObject(envelope, "Commercial License signature");
            rejectUnknownFields(envelope, SIGNATURE_FIELDS, "Commercial License signature");
            JsonNode signatureVersion = envelope.get("signatureVersion");
            if (signatureVersion == null
                    || !signatureVersion.isIntegralNumber()
                    || signatureVersion.intValue() != 1) {
                throw new IllegalStateException("Commercial License signatureVersion must be 1.");
            }
            if (!"RS256".equals(requiredText(envelope, "algorithm", 20))) {
                throw new IllegalStateException(
                        "Commercial License signature algorithm must be RS256.");
            }
            String keyId = requiredText(envelope, "keyId", 80);
            if (!keyId.matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$")) {
                throw new IllegalStateException("Commercial License signature keyId is invalid.");
            }
            if (!keyId.equals(expectedKeyId)) {
                throw new IllegalStateException(
                        "Commercial License signature keyId does not match the trusted key.");
            }
            if (!"license/license.json".equals(
                    requiredText(envelope, "signedArtifact", 120))) {
                throw new IllegalStateException(
                        "Commercial License signature signedArtifact is invalid.");
            }
            String expectedDigest = requiredText(envelope, "signedArtifactSha256", 64);
            if (!expectedDigest.matches("^[0-9a-f]{64}$")) {
                throw new IllegalStateException(
                        "Commercial License signature SHA-256 digest is invalid.");
            }
            String actualDigest = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(licenseBytes));
            if (!actualDigest.equals(expectedDigest)) {
                throw new IllegalStateException(
                        "Commercial License SHA-256 digest does not match.");
            }
            String encodedSignature = requiredText(envelope, "signature", 4096);
            if (!encodedSignature.matches("^[A-Za-z0-9_-]+$")) {
                throw new IllegalStateException(
                        "Commercial License signature must be unpadded base64url.");
            }
            verifySignature(licenseBytes, publicKeyBytes, encodedSignature);

            JsonNode license = objectMapper.readTree(licenseBytes);
            validateLicense(
                    license,
                    expectedCustomer,
                    edition,
                    orderReference,
                    expectedVersion);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Unable to verify the offline commercial License.", exception);
        }
    }

    private static void validateLicense(
            JsonNode license,
            String expectedCustomer,
            String expectedEdition,
            String expectedOrderReference,
            String expectedVersion) {
        requireObject(license, "Commercial License");
        rejectUnknownFields(license, LICENSE_FIELDS, "Commercial License");
        JsonNode licenseVersion = license.get("licenseVersion");
        if (licenseVersion == null
                || !licenseVersion.isIntegralNumber()
                || licenseVersion.intValue() != 2) {
            throw new IllegalStateException("Signed Commercial License licenseVersion must be 2.");
        }
        String customer = requiredText(license, "customer", 80);
        String edition = requiredText(license, "edition", 40).toLowerCase(Locale.ROOT);
        requiredText(license, "release", 80);
        String platformVersion = requiredText(license, "platformVersion", 80);
        String orderReference = requiredText(license, "orderRef", 120);
        Instant issuedAt = timestamp(license, "issuedAt");
        Instant upgradeUntil = timestamp(license, "upgradeUntil");
        Instant supportUntil = timestamp(license, "supportUntil");
        if (issuedAt.isAfter(Instant.now())) {
            throw new IllegalStateException("Commercial License is not valid before issuedAt.");
        }
        if (upgradeUntil.isBefore(issuedAt) || supportUntil.isBefore(issuedAt)) {
            throw new IllegalStateException(
                    "Commercial License support periods must not precede issuedAt.");
        }
        Set<String> features = requiredTextSet(license, "features");
        Set<String> excludedFeatures = requiredTextSet(license, "excludedFeatures");
        Set<String> contradictory = new TreeSet<>(features);
        contradictory.retainAll(excludedFeatures);
        if (!contradictory.isEmpty()) {
            throw new IllegalStateException(
                    "Commercial License both includes and excludes: " + contradictory);
        }
        if (!OFFLINE_SIGNATURE.equals(requiredText(license, "runtimeEnforcement", 40))) {
            throw new IllegalStateException(
                    "Signed Commercial License runtimeEnforcement must be offline-signature.");
        }
        requireMatch("customer", customer, expectedCustomer);
        requireMatch("edition", edition, expectedEdition.toLowerCase(Locale.ROOT));
        requireMatch("orderRef", orderReference, expectedOrderReference);
        requireMatch("platformVersion", platformVersion, expectedVersion);
        if (!features.contains("white_label") || excludedFeatures.contains("white_label")) {
            throw new IllegalStateException(
                    "Commercial License does not grant required feature: white_label.");
        }
    }

    private static byte[] regularFile(Path path, String label) throws Exception {
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path)
                || Files.size(path) == 0) {
            throw new IllegalStateException(
                    label + " must be a non-empty regular file: " + path);
        }
        return Files.readAllBytes(path);
    }

    private static void verifySignature(
            byte[] licenseBytes,
            byte[] publicKeyBytes,
            String encodedSignature) throws Exception {
        String pem = new String(publicKeyBytes, StandardCharsets.US_ASCII)
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(licenseBytes);
        if (!verifier.verify(Base64.getUrlDecoder().decode(encodedSignature))) {
            throw new IllegalStateException("Commercial License signature verification failed.");
        }
    }

    private static Instant timestamp(JsonNode document, String field) {
        String value = requiredText(document, field, 40);
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalStateException(
                    "Commercial License field \"" + field
                            + "\" must be an ISO-8601 timestamp.",
                    exception);
        }
    }

    private static Set<String> requiredTextSet(JsonNode document, String field) {
        JsonNode value = document.get(field);
        if (value == null || !value.isArray() || value.isEmpty()) {
            throw new IllegalStateException(
                    "Commercial License field \"" + field
                            + "\" must be a non-empty string array.");
        }
        Set<String> result = new HashSet<>();
        for (JsonNode entry : value) {
            if (!entry.isTextual() || !StringUtils.hasText(entry.textValue())) {
                throw new IllegalStateException(
                        "Commercial License field \"" + field
                                + "\" must contain non-empty strings.");
            }
            if (!result.add(entry.textValue().trim())) {
                throw new IllegalStateException(
                        "Commercial License field \"" + field
                                + "\" must not contain duplicates.");
            }
        }
        return result;
    }

    private static void requireObject(JsonNode document, String label) {
        if (document == null || !document.isObject()) {
            throw new IllegalStateException(label + " must be a JSON object.");
        }
    }

    private static void rejectUnknownFields(
            JsonNode document,
            Set<String> allowed,
            String label) {
        Set<String> unknown = new TreeSet<>();
        Iterator<String> fields = document.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!allowed.contains(field)) {
                unknown.add(field);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalStateException(label + " contains unsupported fields: " + unknown);
        }
    }

    private static String requiredText(JsonNode document, String field, int maxLength) {
        JsonNode value = document.get(field);
        if (value == null || !value.isTextual() || !StringUtils.hasText(value.textValue())) {
            throw new IllegalStateException(
                    "Commercial License field \"" + field
                            + "\" must be a non-empty string.");
        }
        String normalized = value.textValue().trim();
        if (normalized.length() > maxLength) {
            throw new IllegalStateException(
                    "Commercial License field \"" + field + "\" is too long.");
        }
        return normalized;
    }

    private static void requireMatch(String field, String actual, String expected) {
        if (!actual.equals(expected)) {
            throw new IllegalStateException(
                    "Commercial License " + field + " does not match the deployment.");
        }
    }

    private static String requiredProperty(Environment environment, String name) {
        String value = property(environment, name, "");
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(
                    name + " is required for offline commercial License verification.");
        }
        return value;
    }

    private static String property(Environment environment, String name, String defaultValue) {
        String value = environment.getProperty(name, defaultValue);
        return value == null ? "" : value.trim();
    }
}
