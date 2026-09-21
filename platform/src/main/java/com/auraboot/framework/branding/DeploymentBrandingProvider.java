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
            "generatedByText",
            // Login-story fields (web-admin only) — accepted so a shared branding
            // document cannot fail one tier and pass the other.
            "loginBadge",
            "loginHeadline",
            "loginHeadlineEm",
            "loginLead",
            "loginHeroUrl",
            "loginFeatures",
            "loginWechatOnly",
            "tenantOnboarding");
    private static final Set<String> TENANT_ONBOARDING_FIELDS = Set.of(
            "entityLabel",
            "selectionTitle",
            "selectionLead",
            "createTitle",
            "createDescription",
            "createCta",
            "joinTitle",
            "joinDescription",
            "joinCta",
            "joinChannel",
            "miniProgramName",
            "miniProgramQrUrl",
            "joinSteps");

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
        validateLoginStoryFields(document);
        validateTenantOnboarding(document);
    }

    /**
     * Validates the login-story fields with the same contract web-admin
     * enforces ({@code resolveCommercialBranding}), so one shared branding
     * document either passes both tiers or fails both. These fields shape the
     * login page only — the backend identity never carries them.
     */
    private static void validateLoginStoryFields(JsonNode document) {
        optionalTextField(document, "loginBadge", 80);
        optionalTextField(document, "loginHeadline", 120);
        optionalTextField(document, "loginHeadlineEm", 120);
        optionalTextField(document, "loginLead", 240);
        JsonNode heroUrl = document.get("loginHeroUrl");
        boolean heroUrlUnset = heroUrl == null || heroUrl.isNull()
                || (heroUrl.isTextual() && heroUrl.textValue().isEmpty());
        if (!heroUrlUnset) {
            safeUrl(document, "loginHeroUrl");
        }
        optionalTextListField(document, "loginFeatures", 4);
        // loginWechatOnly: web-admin coerces any present value through
        // Boolean(value), so any JSON value is contractually accepted here too.
    }

    /**
     * Validates the product-specific tenant onboarding document consumed by
     * Web Admin. The backend does not render these fields, but it deliberately
     * validates the same shared deployment artifact instead of silently
     * accepting configuration that the frontend would reject.
     */
    private static void validateTenantOnboarding(JsonNode document) {
        JsonNode onboarding = document.get("tenantOnboarding");
        if (onboarding == null || onboarding.isNull()) {
            return;
        }
        if (!onboarding.isObject()) {
            throw new IllegalStateException(
                    "Deployment branding tenantOnboarding must be an object.");
        }

        Set<String> unknownFields = new HashSet<>();
        onboarding.fieldNames().forEachRemaining(field -> {
            if (!TENANT_ONBOARDING_FIELDS.contains(field)) {
                unknownFields.add(field);
            }
        });
        if (!unknownFields.isEmpty()) {
            throw new IllegalStateException(
                    "Deployment branding tenantOnboarding contains unsupported fields: "
                            + unknownFields);
        }

        requiredNestedText(onboarding, "entityLabel", 24);
        requiredNestedText(onboarding, "selectionTitle", 80);
        requiredNestedText(onboarding, "selectionLead", 160);
        requiredNestedText(onboarding, "createTitle", 60);
        requiredNestedText(onboarding, "createDescription", 180);
        requiredNestedText(onboarding, "createCta", 40);
        requiredNestedText(onboarding, "joinTitle", 60);
        requiredNestedText(onboarding, "joinDescription", 180);
        requiredNestedText(onboarding, "joinCta", 40);

        String joinChannel = requiredNestedText(onboarding, "joinChannel", 32);
        if (!Set.of("invite_code", "wechat_mini").contains(joinChannel)) {
            throw new IllegalStateException(
                    "Deployment branding tenantOnboarding.joinChannel must be "
                            + "invite_code or wechat_mini.");
        }

        optionalNestedText(onboarding, "miniProgramName", 40);
        JsonNode qrUrl = onboarding.get("miniProgramQrUrl");
        if (qrUrl != null && !qrUrl.isNull()) {
            safeNestedUrl(onboarding, "miniProgramQrUrl");
        }

        JsonNode joinSteps = onboarding.get("joinSteps");
        if (joinSteps != null && !joinSteps.isNull()) {
            if (!joinSteps.isArray() || joinSteps.size() < 2 || joinSteps.size() > 4) {
                throw new IllegalStateException(
                        "Deployment branding tenantOnboarding.joinSteps must be an array "
                                + "of 2-4 strings.");
            }
            for (JsonNode step : joinSteps) {
                if (!step.isTextual() || !StringUtils.hasText(step.textValue())
                        || step.textValue().trim().length() > 120) {
                    throw new IllegalStateException(
                            "Deployment branding tenantOnboarding.joinSteps must contain "
                                    + "non-empty strings of at most 120 characters.");
                }
            }
        } else if ("wechat_mini".equals(joinChannel)) {
            throw new IllegalStateException(
                    "Deployment branding tenantOnboarding.joinSteps is required for "
                            + "the wechat_mini channel.");
        }
    }

    private static String requiredNestedText(JsonNode document, String field, int maxLength) {
        JsonNode value = document.get(field);
        if (value == null || !value.isTextual() || !StringUtils.hasText(value.textValue())) {
            throw new IllegalStateException(
                    "Deployment branding tenantOnboarding." + field
                            + " must be a non-empty string.");
        }
        String normalized = value.textValue().trim();
        if (normalized.length() > maxLength) {
            throw new IllegalStateException(
                    "Deployment branding tenantOnboarding." + field + " must be at most "
                            + maxLength + " characters.");
        }
        return normalized;
    }

    private static void optionalNestedText(JsonNode document, String field, int maxLength) {
        JsonNode value = document.get(field);
        if (value == null || value.isNull()) {
            return;
        }
        requiredNestedText(document, field, maxLength);
    }

    private static void safeNestedUrl(JsonNode document, String field) {
        String value = requiredNestedText(document, field, 2048);
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
                "Deployment branding tenantOnboarding." + field
                        + " must be a same-origin path or an HTTPS URL.");
    }

    private static void optionalTextField(JsonNode document, String field, int maxLength) {
        JsonNode value = document.get(field);
        if (value == null || value.isNull()) {
            return;
        }
        if (!value.isTextual()) {
            throw new IllegalStateException(
                    "Deployment branding field \"" + field + "\" must be a non-empty string.");
        }
        if (value.textValue().isEmpty()) {
            return;
        }
        String normalized = value.textValue().trim();
        if (normalized.isEmpty()) {
            throw new IllegalStateException(
                    "Deployment branding field \"" + field + "\" must be a non-empty string.");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalStateException(
                    "Deployment branding field \"" + field + "\" must be at most "
                            + maxLength + " characters.");
        }
    }

    private static void optionalTextListField(JsonNode document, String field, int maxItems) {
        JsonNode value = document.get(field);
        if (value == null || value.isNull()) {
            return;
        }
        if (!value.isArray() || value.isEmpty() || value.size() > maxItems) {
            throw new IllegalStateException(
                    "Deployment branding field \"" + field + "\" must be an array of 1-4 strings.");
        }
        for (JsonNode item : value) {
            if (!item.isTextual() || !StringUtils.hasText(item.textValue())) {
                throw new IllegalStateException(
                        "Deployment branding field \"" + field
                                + "\" must be an array of 1-4 strings.");
            }
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
