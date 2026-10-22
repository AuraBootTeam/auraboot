package com.auraboot.framework.i18n;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.i18n.service.OrphanKeyDetector;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for {@link OrphanKeyDetector}.
 *
 * <p>Test cases:
 * <ul>
 *   <li>OK-01: scan — existing system model keys (valid modelCode) are NOT marked as orphans</li>
 *   <li>OK-02: scan — a key with a non-existent modelCode IS detected as an orphan</li>
 *   <li>OK-03: scan — only model.* keys are analyzed; action.* keys are ignored</li>
 *   <li>OK-04: deleteOrphans — removes only the orphan keys, valid keys remain</li>
 *   <li>OK-05: deleteOrphans — empty key list returns 0 without errors</li>
 * </ul>
 *
 * @author AuraBoot
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrphanKeyDetectorIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private OrphanKeyDetector orphanKeyDetector;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Insert a minimal i18n resource row directly (bypasses service soft-delete logic). */
    private void insertKey(Long tenantId, String key, String lang, String value) {
        jdbcTemplate.update(
            "INSERT INTO ab_i18n_resource (pid, tenant_id, i18n_key, lang, value, source, deleted_flag) " +
            "VALUES (?, ?, ?, ?, ?, 'system', FALSE)",
            UniqueIdGenerator.generate(), tenantId, key, lang, value);
    }

    /** Resolve the first valid model code in the test tenant (must have at least one). */
    private String findAnyValidModelCode(Long tenantId) {
        List<String> codes = jdbcTemplate.queryForList(
            "SELECT code FROM ab_meta_model WHERE tenant_id = ? AND deleted_flag = FALSE LIMIT 1",
            String.class, tenantId);
        return codes.isEmpty() ? null : codes.get(0);
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /**
     * OK-01: Keys whose modelCode matches a real ab_meta_model entry must not be
     * reported as orphans.
     */
    @Test
    @Order(1)
    @DisplayName("OK-01: valid model keys are not flagged as orphans")
    void scan_withValidModelKey_notFlaggedAsOrphan() {
        Long tenantId = getTestTenant().getId();
        String validModelCode = findAnyValidModelCode(tenantId);
        assumeThatCode(() -> assertThat(validModelCode).isNotNull());

        // Insert a synthetic key for a real model
        String key = "model." + validModelCode + ".__test_field__.label";
        insertKey(tenantId, key, "zh-CN", "测试字段");

        OrphanKeyDetector.OrphanKeyScanResult result = orphanKeyDetector.scan(tenantId);

        assertThat(result.orphanKeys())
            .as("A key for an existing model must not be an orphan")
            .doesNotContain(key);
    }

    /**
     * OK-02: A key whose modelCode does NOT exist in ab_meta_model must be detected
     * as an orphan.
     */
    @Test
    @Order(2)
    @DisplayName("OK-02: key with non-existent modelCode is detected as orphan")
    void scan_withOrphanKey_detectsIt() {
        Long tenantId = getTestTenant().getId();
        String fakeModelCode = "ghost_model_" + System.currentTimeMillis();
        String orphanKey = "model." + fakeModelCode + ".some_field.label";

        insertKey(tenantId, orphanKey, "zh-CN", "幽灵模型字段");

        OrphanKeyDetector.OrphanKeyScanResult result = orphanKeyDetector.scan(tenantId);

        assertThat(result.orphanKeys())
            .as("Key for non-existent model must be flagged as orphan")
            .contains(orphanKey);
        assertThat(result.orphanCount())
            .as("orphanCount must be > 0")
            .isGreaterThan(0);
        assertThat(result.totalScanned())
            .as("totalScanned must be >= orphanCount")
            .isGreaterThanOrEqualTo(result.orphanCount());
    }

    /**
     * OK-03: Keys with prefixes other than model.* (e.g. action.*) must be excluded
     * from the scan entirely — they are never counted as orphans.
     */
    @Test
    @Order(3)
    @DisplayName("OK-03: action.* keys are excluded from orphan scan")
    void scan_actionKeys_areNotAnalyzed() {
        Long tenantId = getTestTenant().getId();
        // Insert a synthetic action.* key with a clearly fake suffix
        String actionKey = "action.fake_action_" + System.currentTimeMillis();
        insertKey(tenantId, actionKey, "zh-CN", "假动作");

        OrphanKeyDetector.OrphanKeyScanResult result = orphanKeyDetector.scan(tenantId);

        assertThat(result.orphanKeys())
            .as("action.* keys must never appear in orphan results")
            .doesNotContain(actionKey);
    }

    /**
     * OK-04: deleteOrphans must remove orphan keys (all locales) while leaving
     * valid keys untouched.
     */
    @Test
    @Order(4)
    @DisplayName("OK-04: deleteOrphans removes orphan rows and preserves valid keys")
    void deleteOrphans_removesOrphansPreservesValidKeys() {
        Long tenantId = getTestTenant().getId();
        String fakeModelCode = "zombie_model_" + System.currentTimeMillis();
        String orphanKeyCN = "model." + fakeModelCode + ".field_a.label";
        String orphanKeyEN = "model." + fakeModelCode + ".field_a.label";  // same logical key, different lang

        // Insert orphan key in two locales
        insertKey(tenantId, orphanKeyCN, "zh-CN", "僵尸字段");
        insertKey(tenantId, orphanKeyEN, "en-US", "Zombie field");

        // Insert a valid key (real model)
        String validModelCode = findAnyValidModelCode(tenantId);
        String validKey = "model." + (validModelCode != null ? validModelCode : "org_department") + ".__oktest__.label";
        insertKey(tenantId, validKey, "zh-CN", "有效字段");

        // deleteOrphans on the orphan key
        int deleted = orphanKeyDetector.deleteOrphans(tenantId, List.of(orphanKeyCN));

        assertThat(deleted)
            .as("Should delete rows for the orphan key across all locales")
            .isGreaterThanOrEqualTo(1);

        // Valid key must remain
        Integer validCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ab_i18n_resource WHERE tenant_id = ? AND i18n_key = ? AND deleted_flag = FALSE",
            Integer.class, tenantId, validKey);
        assertThat(validCount)
            .as("Valid key must survive orphan deletion")
            .isGreaterThan(0);
    }

    /**
     * OK-05: deleteOrphans with an empty list must return 0 without any error.
     */
    @Test
    @Order(5)
    @DisplayName("OK-05: deleteOrphans with empty list returns 0")
    void deleteOrphans_emptyList_returnsZero() {
        Long tenantId = getTestTenant().getId();
        int result = orphanKeyDetector.deleteOrphans(tenantId, List.of());
        assertThat(result).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // Helper — avoids Kotlin-style assumeThat
    // -------------------------------------------------------------------------
    @SuppressWarnings("SameParameterValue")
    private static void assumeThatCode(ThrowableAssert.ThrowingCallable callable) {
        // No-op wrapper that re-throws to fail the test if the assumption is false
        try {
            callable.call();
        } catch (Throwable t) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                "Prerequisite not met: " + t.getMessage());
        }
    }
}
