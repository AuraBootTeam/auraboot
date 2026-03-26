package com.auraboot.framework.meta.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.auraboot.framework.meta.dto.PageSchemaSyncVersionDTO;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for mobile schema sync endpoints:
 * - getVersionsSince: lightweight version metadata query
 * - batchGetByKeys: batch fetch full schemas by page keys
 */
class PageSchemaVersionSyncIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PageSchemaService pageSchemaService;

    @Autowired
    private PageSchemaMapper pageSchemaMapper;

    @Test
    void getVersionsSince_returnsUpdatedSchemas() {
        // Arrange: record time before creating test data
        Instant beforeCreate = Instant.now().minus(1, ChronoUnit.SECONDS);

        String suffix = String.valueOf(System.currentTimeMillis());
        String pageKey1 = "sync_test_list_" + suffix;
        String pageKey2 = "sync_test_form_" + suffix;

        createPublishedSchema(pageKey1, "list", "sync_model_" + suffix);
        createPublishedSchema(pageKey2, "form", "sync_model_" + suffix);

        // Act
        List<PageSchemaSyncVersionDTO> versions = pageSchemaService.getVersionsSince(beforeCreate);

        // Assert: our two test schemas should appear
        List<String> returnedKeys = versions.stream()
                .map(PageSchemaSyncVersionDTO::getPageKey)
                .toList();
        assertThat(returnedKeys).contains(pageKey1, pageKey2);

        // Verify DTO fields are populated
        PageSchemaSyncVersionDTO found = versions.stream()
                .filter(v -> pageKey1.equals(v.getPageKey()))
                .findFirst()
                .orElseThrow();
        assertThat(found.getSchemaVersion()).isEqualTo(1);
        assertThat(found.getPageType()).isEqualTo("list");
        assertThat(found.getModelCode()).isEqualTo("sync_model_" + suffix);
        assertThat(found.getUpdatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isAfter(beforeCreate);
    }

    @Test
    void getVersionsSince_excludesDraftSchemas() {
        Instant beforeCreate = Instant.now().minus(1, ChronoUnit.SECONDS);

        String suffix = String.valueOf(System.currentTimeMillis());
        String draftKey = "sync_draft_" + suffix;

        // Create a draft (not published) schema
        createDraftSchema(draftKey, "list", "draft_model_" + suffix);

        // Act
        List<PageSchemaSyncVersionDTO> versions = pageSchemaService.getVersionsSince(beforeCreate);

        // Assert: draft should NOT appear
        List<String> returnedKeys = versions.stream()
                .map(PageSchemaSyncVersionDTO::getPageKey)
                .toList();
        assertThat(returnedKeys).doesNotContain(draftKey);
    }

    @Test
    void batchGetByKeys_returnsRequestedSchemas() {
        String suffix = String.valueOf(System.currentTimeMillis());
        String pageKey1 = "batch_test_list_" + suffix;
        String pageKey2 = "batch_test_form_" + suffix;
        String pageKey3 = "batch_test_detail_" + suffix;

        createPublishedSchema(pageKey1, "list", "batch_model_" + suffix);
        createPublishedSchema(pageKey2, "form", "batch_model_" + suffix);
        createPublishedSchema(pageKey3, "detail", "batch_model_" + suffix);

        // Act: request only two of the three
        List<PageSchemaDTO> results = pageSchemaService.batchGetByKeys(List.of(pageKey1, pageKey3));

        // Assert
        assertThat(results).hasSize(2);
        List<String> returnedKeys = results.stream()
                .map(PageSchemaDTO::getPageKey)
                .toList();
        assertThat(returnedKeys).containsExactlyInAnyOrder(pageKey1, pageKey3);

        // Verify full DTO fields
        PageSchemaDTO dto = results.stream()
                .filter(d -> pageKey1.equals(d.getPageKey()))
                .findFirst()
                .orElseThrow();
        assertThat(dto.getPageType()).isEqualTo("list");
        assertThat(dto.getModelCode()).isEqualTo("batch_model_" + suffix);
        assertThat(dto.getName()).isNotBlank();
    }

    @Test
    void batchGetByKeys_emptyList_returnsEmpty() {
        List<PageSchemaDTO> results = pageSchemaService.batchGetByKeys(List.of());
        assertThat(results).isEmpty();
    }

    @Test
    void batchGetByKeys_nonExistentKeys_returnsEmpty() {
        List<PageSchemaDTO> results = pageSchemaService.batchGetByKeys(
                List.of("nonexistent_key_1", "nonexistent_key_2"));
        assertThat(results).isEmpty();
    }

    // ==================== Helper Methods ====================

    private void createPublishedSchema(String pageKey, String pageType, String modelCode) {
        String pid = UniqueIdGenerator.generate();
        pageSchemaMapper.insertForPluginImport(
                pid,
                getTestTenant().getId(),
                "published",
                pageKey,
                modelCode,
                "model",
                "Test Page " + pageKey,
                "Test Title",
                "Test description",
                pageType,
                "{\"blocks\":[]}",
                1,
                false,
                null,
                Instant.now(),
                0,
                null
        );
    }

    private void createDraftSchema(String pageKey, String pageType, String modelCode) {
        String pid = UniqueIdGenerator.generate();
        pageSchemaMapper.insertForPluginImport(
                pid,
                getTestTenant().getId(),
                "draft",
                pageKey,
                modelCode,
                "model",
                "Draft Page " + pageKey,
                "Draft Title",
                "Draft description",
                pageType,
                "{\"blocks\":[]}",
                1,
                false,
                null,
                null,
                0,
                null
        );
    }
}
