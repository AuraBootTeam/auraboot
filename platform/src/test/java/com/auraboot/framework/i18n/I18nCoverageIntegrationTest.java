package com.auraboot.framework.i18n;

import com.auraboot.framework.i18n.dto.I18nCoverageResponse;
import com.auraboot.framework.i18n.entity.I18nResource;
import com.auraboot.framework.i18n.service.I18nCoverageService;
import com.auraboot.framework.i18n.service.I18nResourceService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for {@link I18nCoverageService}.
 *
 * <p>Each test inserts its own seed data into the test tenant so the assertions are
 * independent of any pre-existing platform data.
 *
 * <p>Test scenarios:
 * <ul>
 *   <li>COV-01: computeCoverage returns locales list with zh-CN first at 100%</li>
 *   <li>COV-02: a key added only to zh-CN shows in missingKeys for another locale</li>
 *   <li>COV-03: a key added to both zh-CN and en-US is NOT in missingKeys for en-US</li>
 *   <li>COV-04: totalKeys count is positive after inserting seed data</li>
 * </ul>
 */
@Slf4j
@DisplayName("I18nCoverageService Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class I18nCoverageIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private I18nCoverageService i18nCoverageService;

    @Autowired
    private I18nResourceService i18nResourceService;

    /** Unique prefix isolates keys from other test runs */
    private final String pfx = "cov-" + System.currentTimeMillis();

    // =========================================================================
    // Helpers
    // =========================================================================

    private I18nResource buildResource(String key, String lang, String value) {
        return I18nResource.builder()
            .i18nKey(key)
            .lang(lang)
            .value(value)
            .source("test")
            .status("approved")
            .build();
    }

    private void safeDelete(String key, String lang) {
        try {
            I18nResource r = i18nResourceService.findByKeyAndLang(key, lang);
            if (r != null) i18nResourceService.delete(r.getPid());
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }

    // =========================================================================
    // COV-01: base locale is first and reports 100%
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("COV-01: computeCoverage with seed data — zh-CN first at 100%")
    void COV_01_computeCoverage_baseLocaleFirst100Percent() {
        // Seed: insert 3 keys in zh-CN so the tenant has coverage data
        String key1 = pfx + ".cov01.field1.label";
        String key2 = pfx + ".cov01.field2.label";
        String key3 = pfx + ".cov01.field3.label";

        i18nResourceService.create(buildResource(key1, "zh-CN", "字段一"));
        i18nResourceService.create(buildResource(key2, "zh-CN", "字段二"));
        i18nResourceService.create(buildResource(key3, "zh-CN", "字段三"));
        // Translate 2 into en-US (leave key3 missing)
        i18nResourceService.create(buildResource(key1, "en-US", "Field One"));
        i18nResourceService.create(buildResource(key2, "en-US", "Field Two"));

        try {
            I18nCoverageResponse response = i18nCoverageService.computeCoverage();

            assertThat(response).isNotNull();
            assertThat(response.getBaseLocale()).isEqualTo("zh-CN");
            assertThat(response.getTotalKeys()).isPositive();
            assertThat(response.getLocales()).isNotEmpty();

            // zh-CN must be the first entry (base locale sorted first)
            I18nCoverageResponse.LocaleCoverage base = response.getLocales().get(0);
            assertThat(base.getLocale()).isEqualTo("zh-CN");
            assertThat(base.getCoverage()).isEqualTo(100.0);
            assertThat(base.getMissing()).isEqualTo(0L);
        } finally {
            for (String lang : List.of("zh-CN", "en-US")) {
                for (String k : List.of(key1, key2, key3)) safeDelete(k, lang);
            }
        }
    }

    // =========================================================================
    // COV-02: missing key detection
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("COV-02: a key present only in zh-CN appears in missingKeys for en-US")
    void COV_02_missingKeyAppearsForOtherLocale() {
        String uniqueKey = pfx + ".cov02.missing-test.label";

        // Insert key only in zh-CN, ensure en-US locale exists so it shows up
        i18nResourceService.create(buildResource(uniqueKey, "zh-CN", "缺失测试标签"));
        // Seed a dummy en-US key so the locale is tracked
        String dummyKey = pfx + ".cov02.dummy.label";
        i18nResourceService.create(buildResource(dummyKey, "zh-CN", "占位符"));
        i18nResourceService.create(buildResource(dummyKey, "en-US", "Placeholder"));

        try {
            I18nCoverageResponse response = i18nCoverageService.computeCoverage();

            // uniqueKey should appear in missingKeys since en-US exists but doesn't have it
            List<I18nCoverageResponse.MissingKeyEntry> missingKeys = response.getMissingKeys();
            Optional<I18nCoverageResponse.MissingKeyEntry> entry = missingKeys.stream()
                .filter(e -> uniqueKey.equals(e.getKey()))
                .findFirst();

            assertThat(entry)
                .as("Key '%s' should be reported as missing in some locale", uniqueKey)
                .isPresent();
            assertThat(entry.get().getMissingIn())
                .as("Locales where the key is missing should include en-US")
                .contains("en-US");
        } finally {
            for (String lang : List.of("zh-CN", "en-US")) {
                safeDelete(uniqueKey, lang);
                safeDelete(dummyKey, lang);
            }
        }
    }

    // =========================================================================
    // COV-03: translated key is NOT flagged as missing
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("COV-03: a key present in both zh-CN and en-US is not flagged missing for en-US")
    void COV_03_translatedKeyNotInMissingList() {
        String uniqueKey = pfx + ".cov03.translated-test.label";

        i18nResourceService.create(buildResource(uniqueKey, "zh-CN", "已翻译标签"));
        i18nResourceService.create(buildResource(uniqueKey, "en-US", "Translated label"));

        try {
            I18nCoverageResponse response = i18nCoverageService.computeCoverage();

            boolean foundAsEnMissing = response.getMissingKeys().stream()
                .anyMatch(e -> uniqueKey.equals(e.getKey()) && e.getMissingIn().contains("en-US"));

            assertThat(foundAsEnMissing)
                .as("Key '%s' should NOT be flagged as missing in en-US", uniqueKey)
                .isFalse();
        } finally {
            for (String lang : List.of("zh-CN", "en-US")) safeDelete(uniqueKey, lang);
        }
    }

    // =========================================================================
    // COV-04: totalKeys after seed insert
    // =========================================================================

    @Test
    @Order(4)
    @DisplayName("COV-04: totalKeys is positive after inserting zh-CN seed data")
    void COV_04_totalKeysPositiveAfterSeed() {
        String key = pfx + ".cov04.check.label";
        i18nResourceService.create(buildResource(key, "zh-CN", "检查"));

        try {
            I18nCoverageResponse response = i18nCoverageService.computeCoverage();
            assertThat(response.getTotalKeys())
                .as("After inserting a zh-CN key, totalKeys must be >= 1")
                .isPositive();
        } finally {
            safeDelete(key, "zh-CN");
        }
    }
}
