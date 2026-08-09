package com.auraboot.framework.branding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves the same order-bound deployment branding document used by Web Admin.
 * Community editions always ignore external branding and retain AuraBoot identity.
 */
@Component
public class DeploymentBrandingProvider implements BrandingProvider {

    private static final Set<String> COMMERCIAL_EDITIONS = Set.of(
            "standard", "professional", "enterprise");
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "schemaVersion",
            "orderReference",
            "productName",
            "platformName",
            "logoUrl",
            "faviconUrl",
            "favicon32Url",
            "appleTouchIconUrl",
            "manifestUrl",
            "websiteUrl",
            "docsUrl",
            "supportUrl",
            "copyrightHolder",
            "poweredByText",
            "generatedByText");

    private final BrandingIdentity identity;

    public DeploymentBrandingProvider(Environment environment, ObjectMapper objectMapper) {
        this.identity = resolve(environment, objectMapper);
    }

    @Override
    public BrandingIdentity current() {
        return identity;
    }

    static BrandingIdentity resolve(Environment environment, ObjectMapper objectMapper) {
        String edition = normalized(environment.getProperty("EDITION"));
        String configPath = environment.getProperty("AURABOOT_BRANDING_CONFIG_PATH", "").trim();
        if (!StringUtils.hasText(configPath) || !COMMERCIAL_EDITIONS.contains(edition)) {
            return BrandingIdentity.community();
        }

        String expectedOrder = environment.getProperty(
                "AURABOOT_WHITE_LABEL_ORDER_REFERENCE", "").trim();
        if (!StringUtils.hasText(expectedOrder)) {
            throw new IllegalStateException(
                    "AURABOOT_WHITE_LABEL_ORDER_REFERENCE is required when deployment branding is enabled.");
        }
        CommercialLicenseVerifier.verifyIfRequired(
                environment, objectMapper, edition, expectedOrder);

        JsonNode document;
        try {
            document = objectMapper.readTree(Files.readString(Path.of(configPath)));
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException(
                    "Unable to read deployment branding configuration: " + configPath,
                    exception);
        }
        validateDocument(document, expectedOrder);
        return new BrandingIdentity(
                requiredText(document, "productName", 80),
                requiredText(document, "platformName", 120),
                requiredAsciiText(document, "generatedByText", 160));
    }

    private static void validateDocument(JsonNode document, String expectedOrder) {
        if (document == null || !document.isObject()) {
            throw new IllegalStateException("Deployment branding configuration must be a JSON object.");
        }
        JsonNode schemaVersion = document.get("schemaVersion");
        if (schemaVersion == null
                || !schemaVersion.isNumber()
                || schemaVersion.decimalValue().compareTo(BigDecimal.ONE) != 0) {
            throw new IllegalStateException("Deployment branding schemaVersion must be 1.");
        }

        Set<String> unknownFields = new HashSet<>();
        Iterator<String> fieldNames = document.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            if (!ALLOWED_FIELDS.contains(fieldName)) {
                unknownFields.add(fieldName);
            }
        }
        if (!unknownFields.isEmpty()) {
            throw new IllegalStateException(
                    "Deployment branding contains unsupported fields: " + unknownFields);
        }

        String orderReference = requiredText(document, "orderReference", 120);
        if (!orderReference.equals(expectedOrder)) {
            throw new IllegalStateException(
                    "Deployment branding orderReference does not match the delivery order.");
        }

        requiredText(document, "productName", 80);
        requiredText(document, "platformName", 120);
        requiredText(document, "copyrightHolder", 160);
        requiredText(document, "poweredByText", 160);
        requiredAsciiText(document, "generatedByText", 160);
        for (String field : Set.of(
                "logoUrl",
                "faviconUrl",
                "favicon32Url",
                "appleTouchIconUrl",
                "manifestUrl",
                "websiteUrl",
                "docsUrl",
                "supportUrl")) {
            safeUrl(document, field);
        }
    }

    private static String requiredText(JsonNode document, String field, int maxLength) {
        JsonNode value = document.get(field);
        if (value == null || !value.isTextual() || !StringUtils.hasText(value.textValue())) {
            throw new IllegalStateException(
                    "Deployment branding field \"" + field + "\" must be a non-empty string.");
        }
        String normalized = value.textValue().trim();
        if (normalized.length() > maxLength) {
            throw new IllegalStateException(
                    "Deployment branding field \"" + field + "\" must be at most "
                            + maxLength + " characters.");
        }
        return normalized;
    }

    private static String requiredAsciiText(JsonNode document, String field, int maxLength) {
        String value = requiredText(document, field, maxLength);
        if (value.chars().anyMatch(character -> character < 0x20 || character > 0x7e)) {
            throw new IllegalStateException(
                    "Deployment branding field \"" + field
                            + "\" must use printable ASCII for PDF fallback compatibility.");
        }
        return value;
    }

    private static void safeUrl(JsonNode document, String field) {
        String value = requiredText(document, field, 2048);
        if (value.startsWith("/") && !value.startsWith("//")) {
            return;
        }
        try {
            URI uri = URI.create(value);
            if ("https".equalsIgnoreCase(uri.getScheme()) && StringUtils.hasText(uri.getHost())) {
                return;
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through to the stable configuration error below.
        }
        throw new IllegalStateException(
                "Deployment branding field \"" + field
                        + "\" must be a same-origin path or an HTTPS URL.");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
