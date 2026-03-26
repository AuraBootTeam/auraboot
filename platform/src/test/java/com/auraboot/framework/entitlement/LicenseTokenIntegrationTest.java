package com.auraboot.framework.entitlement;

import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.entitlement.entity.PluginPlan;
import com.auraboot.framework.entitlement.mapper.PluginPlanMapper;
import com.auraboot.framework.entitlement.provider.LicenseProvider.LicenseClaims;
import com.auraboot.framework.entitlement.service.EntitlementService;
import com.auraboot.framework.entitlement.service.LicenseKeyRegistry;
import com.auraboot.framework.entitlement.service.LicenseTokenService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link LicenseTokenService}.
 *
 * <p>Verifies RS256 JWT issuance, signature verification, tamper detection, and
 * offline-token import through {@link EntitlementService#importOfflineToken}.</p>
 *
 * <p>Both {@code auraboot.entitlement.enabled} and
 * {@code auraboot.entitlement.offline-license.enabled} are forced to {@code true}
 * so that verifyToken() does not short-circuit and return empty.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "auraboot.entitlement.enabled=true",
        "auraboot.entitlement.offline-license.enabled=true"
})
public class LicenseTokenIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private LicenseTokenService licenseTokenService;

    @Autowired
    private EntitlementService entitlementService;

    @Autowired
    private PluginPlanMapper pluginPlanMapper;

    @Autowired
    private LicenseKeyRegistry keyRegistry;

    @Autowired
    private ObjectMapper objectMapper;

    private final String testRunId = String.valueOf(System.currentTimeMillis());

    private final String pluginId = "license-test-" + testRunId;
    private final String importPluginId = "import-" + testRunId;

    // Set during Order(1); used by subsequent tests
    private String issuedToken;

    // =========================================================================
    // Test 1 — issue a valid RS256 JWT
    // =========================================================================

    @Test
    @Order(1)
    void issueToken_returnsValidRS256Jwt() {
        Long tenantId = getTestTenant().getId();
        Instant expiry = Instant.now().plus(30, ChronoUnit.DAYS);

        String token = licenseTokenService.issueToken(
                tenantId, pluginId, "pro",
                Set.of("feature.a", "feature.b"),
                expiry);

        assertNotNull(token, "Issued token must not be null");

        String[] parts = token.split("\\.");
        assertEquals(3, parts.length,
                "Compact JWT must have exactly 3 dot-separated parts (header.payload.signature)");

        // Store for use in subsequent tests
        this.issuedToken = token;
    }

    // =========================================================================
    // Test 2 — verify returns correct claims
    // =========================================================================

    @Test
    @Order(2)
    void verifyToken_withValidToken_returnsCorrectClaims() {
        assertNotNull(issuedToken, "issuedToken must be set by Order(1)");
        Long tenantId = getTestTenant().getId();

        Optional<LicenseClaims> result = licenseTokenService.verifyToken(issuedToken);

        assertTrue(result.isPresent(), "verifyToken should return non-empty Optional for a valid token");

        LicenseClaims claims = result.get();
        assertEquals(tenantId, claims.tenantId(), "claims.tenantId() must match the issuing tenant");
        assertEquals(pluginId, claims.pluginId(), "claims.pluginId() must match the plugin used at issuance");
        assertEquals("pro", claims.planCode(), "claims.planCode() must be PRO");
        assertTrue(claims.features().contains("feature.a"),
                "claims.features() must contain feature.a");
    }

    // =========================================================================
    // Test 3 — token with past expiry still verifiable (signature check only)
    // =========================================================================

    @Test
    @Order(3)
    void verifyToken_withExpiredToken_claimsReflectPastExpiry() {
        Long tenantId = getTestTenant().getId();
        // Issue a token whose exp is in the past
        Instant pastExpiry = Instant.now().minus(1, ChronoUnit.DAYS);
        String expiredToken = licenseTokenService.issueToken(
                tenantId, pluginId, "pro", Set.of("feature.a"), pastExpiry);

        // verifyToken() checks the signature but NOT the expiry timestamp;
        // expiry enforcement is the caller's responsibility
        Optional<LicenseClaims> result = licenseTokenService.verifyToken(expiredToken);

        assertTrue(result.isPresent(),
                "verifyToken should still return claims for a structurally valid (but expired) token — expiry is caller-checked");
        assertTrue(result.get().expiresAt().isBefore(Instant.now()),
                "claims.expiresAt() should be in the past for an expired token");
    }

    // =========================================================================
    // Test 4 — tampered payload invalidates signature
    // =========================================================================

    @Test
    @Order(4)
    void verifyToken_withTamperedToken_returnsEmpty() {
        assertNotNull(issuedToken, "issuedToken must be set by Order(1)");

        String[] parts = issuedToken.split("\\.");
        assertEquals(3, parts.length);

        // Decode payload, append a character to invalidate it, re-encode
        byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
        String tamperedPayloadJson = new String(payloadBytes, java.nio.charset.StandardCharsets.UTF_8) + "X";
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tamperedPayloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        String tamperedToken = parts[0] + "." + tamperedPayload + "." + parts[2];

        Optional<LicenseClaims> result = licenseTokenService.verifyToken(tamperedToken);
        assertTrue(result.isEmpty(),
                "verifyToken should return empty Optional when the token payload has been tampered with");
    }

    // =========================================================================
    // Test 5 — import offline token activates entitlement
    // =========================================================================

    @Test
    @Order(5)
    void importOfflineToken_validToken_activatesEntitlement() {
        Long tenantId = getTestTenant().getId();

        // Create a PRO plan for importPluginId so that activateEntitlement can find it
        PluginPlan proPlan = PluginPlan.builder()
                .pid(UlidGenerator.nextULID())
                .pluginId(importPluginId)
                .planCode("pro")
                .displayNameEn("Pro")
                .displayNameZh("专业版")
                .sortOrder(1)
                .isDefault(false)
                .billingType("subscription")
                .createdAt(Instant.now())
                .build();
        pluginPlanMapper.insert(proPlan);

        // Issue a token scoped to this tenant + importPluginId
        Instant expiry = Instant.now().plus(30, ChronoUnit.DAYS);
        String token = licenseTokenService.issueToken(
                tenantId, importPluginId, "pro", Set.of("feature.x"), expiry);

        assertDoesNotThrow(() -> entitlementService.importOfflineToken(tenantId, token),
                "importOfflineToken should not throw for a valid token with matching tenant and existing plan");

        assertTrue(entitlementService.isPluginActive(tenantId, importPluginId),
                "Plugin should be active after importing a valid offline license token");
    }

    // =========================================================================
    // Test 6 — token with wrong tenant ID must be rejected
    // =========================================================================

    @Test
    @Order(6)
    void importOfflineToken_wrongTenantToken_throwsException() {
        Long realTenantId = getTestTenant().getId();

        // Issue a token for a completely different tenant (99999)
        Long wrongTenantId = 99999L;
        Instant expiry = Instant.now().plus(30, ChronoUnit.DAYS);
        String wrongTenantToken = licenseTokenService.issueToken(
                wrongTenantId, pluginId, "pro", Set.of(), expiry);

        // Attempting to import it under the real tenant must throw
        assertThrows(IllegalArgumentException.class,
                () -> entitlementService.importOfflineToken(realTenantId, wrongTenantToken),
                "importOfflineToken must reject a token whose tenantId does not match the target tenant");
    }

    // =========================================================================
    // Test 7 — claims contain planCode and features
    // =========================================================================

    @Test
    @Order(7)
    void verifyToken_claimsContainFeaturesAndPlanCode() {
        assertNotNull(issuedToken, "issuedToken must be set by Order(1)");

        Optional<LicenseClaims> result = licenseTokenService.verifyToken(issuedToken);
        assertTrue(result.isPresent(), "verifyToken should return non-empty Optional");

        LicenseClaims claims = result.get();
        assertEquals("pro", claims.planCode(), "Plan code must be PRO");
        assertFalse(claims.features().isEmpty(), "Features set must not be empty");
        assertTrue(claims.features().contains("feature.b"),
                "Features must include feature.b which was set at issuance");
    }

    // =========================================================================
    // Test 8 — multiple tokens have unique JTIs
    // =========================================================================

    @Test
    @Order(8)
    void issueToken_multipleTokens_uniqueJti() {
        Long tenantId = getTestTenant().getId();
        Instant expiry = Instant.now().plus(30, ChronoUnit.DAYS);

        String token1 = licenseTokenService.issueToken(
                tenantId, pluginId, "pro", Set.of("feature.a"), expiry);
        String token2 = licenseTokenService.issueToken(
                tenantId, pluginId, "pro", Set.of("feature.a"), expiry);

        assertNotEquals(token1, token2, "Two separately issued tokens must not be identical");

        Optional<LicenseClaims> claims1 = licenseTokenService.verifyToken(token1);
        Optional<LicenseClaims> claims2 = licenseTokenService.verifyToken(token2);

        assertTrue(claims1.isPresent(), "First token must verify successfully");
        assertTrue(claims2.isPresent(), "Second token must verify successfully");

        assertNotEquals(claims1.get().jti(), claims2.get().jti(),
                "Each issued token must have a unique JTI (jti claim)");
    }

    // =========================================================================
    // Test 9 — issued token contains kid in header
    // =========================================================================

    @Test
    @Order(9)
    void issueToken_headerContainsKid() throws Exception {
        Long tenantId = getTestTenant().getId();
        Instant expiry = Instant.now().plus(30, ChronoUnit.DAYS);

        String token = licenseTokenService.issueToken(
                tenantId, pluginId, "pro", Set.of("feature.a"), expiry);

        // Decode header and verify kid is present
        String[] parts = token.split("\\.");
        byte[] headerBytes = Base64.getUrlDecoder().decode(parts[0]);
        Map<String, Object> header = objectMapper.readValue(headerBytes,
                new TypeReference<Map<String, Object>>() {});

        assertTrue(header.containsKey("kid"),
                "JWT header must contain a 'kid' claim for key rotation support");
        assertEquals(keyRegistry.getActiveKid(), header.get("kid"),
                "kid in JWT header must match the active kid from LicenseKeyRegistry");

        // Token with kid should still verify successfully
        Optional<LicenseClaims> result = licenseTokenService.verifyToken(token);
        assertTrue(result.isPresent(),
                "Token with kid should verify successfully against the matching public key");
    }

    // =========================================================================
    // Test 10 — token without kid uses default key (backward compat)
    // =========================================================================

    @Test
    @Order(10)
    void verifyToken_withoutKid_usesDefaultKey() throws Exception {
        Long tenantId = getTestTenant().getId();
        Instant expiry = Instant.now().plus(30, ChronoUnit.DAYS);

        // Issue a normal token (which now includes kid)
        String token = licenseTokenService.issueToken(
                tenantId, pluginId, "pro", Set.of("feature.a"), expiry);

        // Manually strip the kid from the header to simulate a pre-rotation token
        String[] parts = token.split("\\.");
        byte[] headerBytes = Base64.getUrlDecoder().decode(parts[0]);
        Map<String, Object> header = objectMapper.readValue(headerBytes,
                new TypeReference<Map<String, Object>>() {});
        header.remove("kid");
        String newHeader = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectMapper.writeValueAsBytes(header));

        // Re-sign with the same key (since we changed the header, old signature is invalid)
        String signingInput = newHeader + "." + parts[1];
        java.security.Signature signer = java.security.Signature.getInstance("SHA256withRSA");
        signer.initSign(keyRegistry.getSigningKey());
        signer.update(signingInput.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String newSignature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signer.sign());

        String tokenWithoutKid = signingInput + "." + newSignature;

        // Verify — should succeed using the default key
        Optional<LicenseClaims> result = licenseTokenService.verifyToken(tokenWithoutKid);
        assertTrue(result.isPresent(),
                "Token without kid must still verify using the default public key (backward compatibility)");
        assertEquals(tenantId, result.get().tenantId());
    }

    // =========================================================================
    // Test 11 — token with unknown kid is rejected
    // =========================================================================

    @Test
    @Order(11)
    void verifyToken_withUnknownKid_returnsEmpty() throws Exception {
        Long tenantId = getTestTenant().getId();
        Instant expiry = Instant.now().plus(30, ChronoUnit.DAYS);

        // Issue a normal token
        String token = licenseTokenService.issueToken(
                tenantId, pluginId, "pro", Set.of("feature.a"), expiry);

        // Replace kid with an unknown value
        String[] parts = token.split("\\.");
        byte[] headerBytes = Base64.getUrlDecoder().decode(parts[0]);
        Map<String, Object> header = objectMapper.readValue(headerBytes,
                new TypeReference<Map<String, Object>>() {});
        header.put("kid", "unknown-key-99");
        String newHeader = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectMapper.writeValueAsBytes(header));

        // Keep original payload and signature (signature won't match anyway,
        // but the unknown kid should cause rejection before signature check)
        String tamperedToken = newHeader + "." + parts[1] + "." + parts[2];

        Optional<LicenseClaims> result = licenseTokenService.verifyToken(tamperedToken);
        assertTrue(result.isEmpty(),
                "Token with an unknown kid must be rejected");
    }
}
