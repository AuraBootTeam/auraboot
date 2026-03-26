package com.auraboot.framework.integration.inbox;

import com.auraboot.framework.inbox.dto.BarcodeLookupResult;
import com.auraboot.framework.inbox.service.BarcodeLookupService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link BarcodeLookupService}.
 * <p>
 * Tests run against real PostgreSQL with real published models.
 * Data is rolled back after each test (inherited from BaseIntegrationTest).
 */
@Slf4j
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BarcodeLookupIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private BarcodeLookupService barcodeLookupService;

    @Autowired
    private MetaModelMapper metaModelMapper;

    @Autowired
    private MetaModelService metaModelService;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    // ==================== Null / blank input ====================

    @Test
    @Order(1)
    void lookup_nullCode_returnsNotFound() {
        BarcodeLookupResult result = barcodeLookupService.lookup(null);

        assertThat(result).isNotNull();
        assertThat(result.isFound()).isFalse();
        assertThat(result.getModelCode()).isNull();
        assertThat(result.getRecordId()).isNull();
    }

    @Test
    @Order(2)
    void lookup_blankCode_returnsNotFound() {
        BarcodeLookupResult result = barcodeLookupService.lookup("   ");

        assertThat(result).isNotNull();
        assertThat(result.isFound()).isFalse();
    }

    @Test
    @Order(3)
    void lookup_emptyCode_returnsNotFound() {
        BarcodeLookupResult result = barcodeLookupService.lookup("");

        assertThat(result).isNotNull();
        assertThat(result.isFound()).isFalse();
    }

    // ==================== Non-existent code ====================

    @Test
    @Order(4)
    void lookup_nonExistentCode_returnsNotFound() {
        BarcodeLookupResult result = barcodeLookupService.lookup("NONEXISTENT_BARCODE_" + System.currentTimeMillis());

        assertThat(result).isNotNull();
        assertThat(result.isFound()).isFalse();
        assertThat(result.getModelCode()).isNull();
        assertThat(result.getRecordId()).isNull();
        assertThat(result.getDeepLink()).isNull();
    }

    // ==================== Service wiring ====================

    @Test
    @Order(5)
    void service_isWired() {
        assertThat(barcodeLookupService).isNotNull();
    }

    // ==================== Result structure validation ====================

    @Test
    @Order(6)
    void notFoundResult_hasCorrectStructure() {
        BarcodeLookupResult result = BarcodeLookupResult.notFound();

        assertThat(result.isFound()).isFalse();
        assertThat(result.getModelCode()).isNull();
        assertThat(result.getRecordId()).isNull();
        assertThat(result.getTitle()).isNull();
        assertThat(result.getPageKey()).isNull();
        assertThat(result.getDeepLink()).isNull();
        assertThat(result.getFields()).isNull();
    }

    @Test
    @Order(7)
    void foundResult_hasCorrectStructure() {
        BarcodeLookupResult result = BarcodeLookupResult.builder()
                .found(true)
                .modelCode("test_model")
                .recordId(123L)
                .title("Test Record")
                .pageKey("test_model_list")
                .deepLink("auraboot://record/test_model_list/123/Test%20Record")
                .fields(java.util.Map.of("code", "ABC", "name", "Test"))
                .build();

        assertThat(result.isFound()).isTrue();
        assertThat(result.getModelCode()).isEqualTo("test_model");
        assertThat(result.getRecordId()).isEqualTo(123L);
        assertThat(result.getTitle()).isEqualTo("Test Record");
        assertThat(result.getPageKey()).isEqualTo("test_model_list");
        assertThat(result.getDeepLink()).contains("auraboot://record/");
        assertThat(result.getFields()).containsEntry("code", "ABC");
    }

    // ==================== Live data lookup (depends on seeded data) ====================

    @Test
    @Order(10)
    void lookup_publishedModelsExist() {
        // Verify the infrastructure: at least some published models should exist in the test DB
        var models = metaModelMapper.findByStatus("published");
        log.info("Found {} published models for barcode lookup test", models != null ? models.size() : 0);
        // This is informational — we don't fail if no models exist in test env,
        // but we verify the query doesn't throw
        assertThat(models).isNotNull();
    }

    @Test
    @Order(11)
    void lookup_specialCharactersInCode_doesNotThrow() {
        // SQL injection attempt should be harmless (parameterized query)
        BarcodeLookupResult result = barcodeLookupService.lookup("'; DROP TABLE mt_test; --");

        assertThat(result).isNotNull();
        assertThat(result.isFound()).isFalse();
    }

    @Test
    @Order(12)
    void lookup_veryLongCode_doesNotThrow() {
        String longCode = "A".repeat(2000);
        BarcodeLookupResult result = barcodeLookupService.lookup(longCode);

        assertThat(result).isNotNull();
        assertThat(result.isFound()).isFalse();
    }
}
