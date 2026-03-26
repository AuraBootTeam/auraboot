package com.auraboot.framework.integration.saas;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.saas.config.mapper.SystemConfigMapper;
import com.auraboot.framework.saas.config.service.SystemConfigService;
import com.auraboot.framework.saas.config.service.impl.SystemConfigServiceImpl;
import com.auraboot.framework.saas.constant.SystemConfigKeys;
import com.auraboot.framework.saas.fingerprint.InstanceFingerprintService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for InstanceFingerprintService.
 *
 * Validates:
 * - db_uuid generation via PostgreSQL gen_random_uuid()
 * - Fingerprint computation from real system config
 * - Fingerprint matching and mismatch detection
 * - Cache invalidation
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InstanceFingerprintServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private InstanceFingerprintService fingerprintService;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private SystemConfigMapper systemConfigMapper;

    private final long ts = System.currentTimeMillis();

    @BeforeEach
    void resetCaches() {
        // Clear fingerprint cache
        fingerprintService.invalidateCache();

        // Clear system config cache
        if (systemConfigService instanceof SystemConfigServiceImpl impl) {
            ReflectionTestUtils.setField(impl, "cacheExpiry", 0L);
            @SuppressWarnings("unchecked")
            Map<String, String> cache = (Map<String, String>) ReflectionTestUtils.getField(impl, "cache");
            if (cache != null) cache.clear();
        }
    }

    @Test
    @Order(1)
    @DisplayName("generateDbUuid returns valid UUID from PostgreSQL gen_random_uuid()")
    void generateDbUuid_shouldReturnValidUuid() {
        String uuid = systemConfigMapper.generateDbUuid();

        assertThat(uuid).isNotNull();
        assertThat(uuid).isNotBlank();
        // UUID format: 8-4-4-4-12 hex characters
        assertThat(uuid).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @Order(2)
    @DisplayName("generateDbUuid returns different values each call")
    void generateDbUuid_shouldReturnUniqueValues() {
        String uuid1 = systemConfigMapper.generateDbUuid();
        String uuid2 = systemConfigMapper.generateDbUuid();

        assertThat(uuid1).isNotEqualTo(uuid2);
    }

    @Test
    @Order(3)
    @DisplayName("getFingerprint returns null when db_uuid not initialized")
    void getFingerprint_shouldReturnNullWhenDbUuidMissing() {
        // No system config entries exist in a clean transaction
        String fingerprint = fingerprintService.getFingerprint();

        assertThat(fingerprint).isNull();
    }

    @Test
    @Order(4)
    @DisplayName("getFingerprint returns SHA-256 hex when both config values exist")
    void getFingerprint_shouldReturnSha256WhenConfigExists() {
        String testUrl = "https://test-" + ts + ".example.com";
        String testDbUuid = systemConfigMapper.generateDbUuid();

        systemConfigService.initialize(SystemConfigKeys.SYSTEM_INSTANCE_URL, testUrl,
                "system", "string", "Test instance URL", false);
        systemConfigService.initialize(SystemConfigKeys.SYSTEM_DB_UUID, testDbUuid,
                "system", "string", "Test db uuid", true);

        String fingerprint = fingerprintService.getFingerprint();

        assertThat(fingerprint).isNotNull();
        assertThat(fingerprint).hasSize(64); // SHA-256 = 64 hex chars
        assertThat(fingerprint).matches("[0-9a-f]{64}");

        // Verify it matches the expected computation
        String expected = InstanceFingerprintService.computeFingerprint(testUrl, testDbUuid);
        assertThat(fingerprint).isEqualTo(expected);
    }

    @Test
    @Order(5)
    @DisplayName("getFingerprint caches result and returns same value")
    void getFingerprint_shouldCacheResult() {
        String testUrl = "https://cache-test-" + ts + ".example.com";
        String testDbUuid = systemConfigMapper.generateDbUuid();

        systemConfigService.initialize(SystemConfigKeys.SYSTEM_INSTANCE_URL, testUrl,
                "system", "string", "Test instance URL", false);
        systemConfigService.initialize(SystemConfigKeys.SYSTEM_DB_UUID, testDbUuid,
                "system", "string", "Test db uuid", true);

        String first = fingerprintService.getFingerprint();
        String second = fingerprintService.getFingerprint();

        assertThat(first).isEqualTo(second);
    }

    @Test
    @Order(6)
    @DisplayName("invalidateCache forces recomputation")
    void invalidateCache_shouldForceRecomputation() {
        String testUrl = "https://invalidate-test-" + ts + ".example.com";
        String testDbUuid = systemConfigMapper.generateDbUuid();

        systemConfigService.initialize(SystemConfigKeys.SYSTEM_INSTANCE_URL, testUrl,
                "system", "string", "Test instance URL", false);
        systemConfigService.initialize(SystemConfigKeys.SYSTEM_DB_UUID, testDbUuid,
                "system", "string", "Test db uuid", true);

        String first = fingerprintService.getFingerprint();
        assertThat(first).isNotNull();

        fingerprintService.invalidateCache();

        // After invalidation, should recompute (same result since config unchanged)
        String second = fingerprintService.getFingerprint();
        assertThat(second).isEqualTo(first);
    }

    @Test
    @Order(7)
    @DisplayName("matches returns true when fingerprints match")
    void matches_shouldReturnTrueWhenFingerprintsMatch() {
        String testUrl = "https://match-test-" + ts + ".example.com";
        String testDbUuid = systemConfigMapper.generateDbUuid();

        systemConfigService.initialize(SystemConfigKeys.SYSTEM_INSTANCE_URL, testUrl,
                "system", "string", "Test instance URL", false);
        systemConfigService.initialize(SystemConfigKeys.SYSTEM_DB_UUID, testDbUuid,
                "system", "string", "Test db uuid", true);

        String localFingerprint = fingerprintService.getFingerprint();
        assertThat(localFingerprint).isNotNull();

        boolean result = fingerprintService.matches(localFingerprint);
        assertThat(result).isTrue();
    }

    @Test
    @Order(8)
    @DisplayName("matches returns false when fingerprints differ")
    void matches_shouldReturnFalseWhenFingerprintsDiffer() {
        String testUrl = "https://mismatch-test-" + ts + ".example.com";
        String testDbUuid = systemConfigMapper.generateDbUuid();

        systemConfigService.initialize(SystemConfigKeys.SYSTEM_INSTANCE_URL, testUrl,
                "system", "string", "Test instance URL", false);
        systemConfigService.initialize(SystemConfigKeys.SYSTEM_DB_UUID, testDbUuid,
                "system", "string", "Test db uuid", true);

        // Compute a fingerprint from a different URL (simulates license from another instance)
        String differentFingerprint = InstanceFingerprintService.computeFingerprint(
                "https://pirated-instance.example.com", testDbUuid);

        boolean result = fingerprintService.matches(differentFingerprint);
        assertThat(result).isFalse();
    }

    @Test
    @Order(9)
    @DisplayName("matches returns true when license fingerprint is null (Community edition)")
    void matches_shouldReturnTrueWhenLicenseFingerprintNull() {
        boolean result = fingerprintService.matches(null);
        assertThat(result).isTrue();
    }

    @Test
    @Order(10)
    @DisplayName("matches returns true when license fingerprint is blank (Community edition)")
    void matches_shouldReturnTrueWhenLicenseFingerprintBlank() {
        boolean result = fingerprintService.matches("  ");
        assertThat(result).isTrue();
    }

    @Test
    @Order(11)
    @DisplayName("getDbUuid returns stored db_uuid value")
    void getDbUuid_shouldReturnStoredValue() {
        String testDbUuid = systemConfigMapper.generateDbUuid();
        systemConfigService.initialize(SystemConfigKeys.SYSTEM_DB_UUID, testDbUuid,
                "system", "string", "Test db uuid", true);

        String result = fingerprintService.getDbUuid();
        assertThat(result).isEqualTo(testDbUuid);
    }

    @Test
    @Order(12)
    @DisplayName("getInstanceUrl returns stored instance_url value")
    void getInstanceUrl_shouldReturnStoredValue() {
        String testUrl = "https://url-test-" + ts + ".example.com";
        systemConfigService.initialize(SystemConfigKeys.SYSTEM_INSTANCE_URL, testUrl,
                "system", "string", "Test instance URL", false);

        String result = fingerprintService.getInstanceUrl();
        assertThat(result).isEqualTo(testUrl);
    }
}
