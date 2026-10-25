package com.auraboot.framework.i18n;

import com.auraboot.framework.i18n.entity.I18nResource;
import com.auraboot.framework.i18n.service.I18nResourceService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for I18nResourceService.
 * Tests batch operations, queries, and model/field sync.
 */
@Slf4j
@DisplayName("I18nResourceService Integration Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class I18nResourceServiceTest extends BaseIntegrationTest {

    @Autowired
    private I18nResourceService i18nResourceService;

    // ==================== batchUpsert ====================

    @Test
    @Order(1)
    @DisplayName("batchUpsert should create new i18n records")
    void batchUpsertShouldCreateNewRecords() {
        List<I18nResource> resources = buildTestResources(5);

        int count = i18nResourceService.batchUpsert(resources);

        assertThat(count).isEqualTo(5);

        // Verify records exist in DB
        I18nResource found = i18nResourceService.findByKeyAndLang("test.batch.key1", "zh-CN");
        assertThat(found).isNotNull();
        assertThat(found.getValue()).isEqualTo("Value 1");
        assertThat(found.getSource()).isEqualTo("test");
    }

    @Test
    @Order(2)
    @DisplayName("batchUpsert should update existing records (upsert semantics)")
    void batchUpsertShouldUpdateExistingRecords() {
        // Insert initial records
        List<I18nResource> initial = new ArrayList<>();
        initial.add(buildResource("test.upsert.key1", "zh-CN", "Old Value 1", "test"));
        initial.add(buildResource("test.upsert.key2", "zh-CN", "Old Value 2", "test"));
        i18nResourceService.batchUpsert(initial);

        // Upsert with updated values
        List<I18nResource> updated = new ArrayList<>();
        updated.add(buildResource("test.upsert.key1", "zh-CN", "New Value 1", "test"));
        updated.add(buildResource("test.upsert.key2", "zh-CN", "New Value 2", "test"));
        int count = i18nResourceService.batchUpsert(updated);

        assertThat(count).isEqualTo(2);

        // Verify values were updated
        I18nResource found = i18nResourceService.findByKeyAndLang("test.upsert.key1", "zh-CN");
        assertThat(found).isNotNull();
        assertThat(found.getValue()).isEqualTo("New Value 1");
    }

    // ==================== findByKeyAndLang ====================

    @Test
    @Order(3)
    @DisplayName("findByKeyAndLang should return exact match")
    void findByKeyAndLangShouldReturnExactMatch() {
        i18nResourceService.batchUpsert(List.of(
                buildResource("test.find.exact", "zh-CN", "中文", "test"),
                buildResource("test.find.exact", "en-US", "English", "test")
        ));

        I18nResource zhResult = i18nResourceService.findByKeyAndLang("test.find.exact", "zh-CN");
        assertThat(zhResult).isNotNull();
        assertThat(zhResult.getValue()).isEqualTo("中文");

        I18nResource enResult = i18nResourceService.findByKeyAndLang("test.find.exact", "en-US");
        assertThat(enResult).isNotNull();
        assertThat(enResult.getValue()).isEqualTo("English");
    }

    @Test
    @Order(4)
    @DisplayName("findByKeyAndLang should return null for non-existent key")
    void findByKeyAndLangShouldReturnNullForNonExistent() {
        I18nResource result = i18nResourceService.findByKeyAndLang("non.existent.key", "zh-CN");
        assertThat(result).isNull();
    }

    // ==================== findByKeyPrefix ====================

    @Test
    @Order(5)
    @DisplayName("findByKeyPrefix should return matching records")
    void findByKeyPrefixShouldReturnMatchingRecords() {
        i18nResourceService.batchUpsert(List.of(
                buildResource("model.store.name.label", "zh-CN", "店铺名称", "test"),
                buildResource("model.store.code.label", "zh-CN", "店铺编码", "test"),
                buildResource("model.store.status.label", "zh-CN", "状态", "test"),
                buildResource("model.product.name.label", "zh-CN", "产品名称", "test")
        ));

        List<I18nResource> results = i18nResourceService.findByKeyPrefix("zh-CN", "model.store.");

        assertThat(results)
                .isNotNull()
                .hasSizeGreaterThanOrEqualTo(3)
                .allSatisfy(r -> assertThat(r.getI18nKey()).startsWith("model.store."));
    }

    // ==================== syncFromModel ====================

    @Test
    @Order(6)
    @DisplayName("syncFromModel should generate model label i18n key")
    void syncFromModelShouldGenerateLabelKey() {
        Long modelId = 99999L;
        String modelCode = "test_device";
        String displayName = "测试设备";

        i18nResourceService.syncFromModel(modelId, modelCode, displayName);

        I18nResource result = i18nResourceService.findByKeyAndLang(
                "model.test_device._meta.label", "zh-CN");

        assertThat(result).isNotNull();
        assertThat(result.getValue()).isEqualTo("测试设备");
        assertThat(result.getSource()).isEqualTo(I18nResource.SOURCE_MODEL);
        assertThat(result.getRefType()).isEqualTo(I18nResource.REF_TYPE_MODEL);
        assertThat(result.getRefId()).isEqualTo(modelId);
    }

    @Test
    @Order(7)
    @DisplayName("syncFromModel should skip when displayName is blank")
    void syncFromModelShouldSkipBlankDisplayName() {
        i18nResourceService.syncFromModel(99998L, "test_blank", "");

        I18nResource result = i18nResourceService.findByKeyAndLang(
                "model.test_blank._meta.label", "zh-CN");
        assertThat(result).isNull();
    }

    // ==================== syncFromField ====================

    @Test
    @Order(8)
    @DisplayName("syncFromField should generate label, placeholder, and description keys")
    void syncFromFieldShouldGenerateAllKeys() {
        Long fieldId = 88888L;

        i18nResourceService.syncFromField(
                fieldId, "test_model", "test_field",
                "测试字段", "请输入", "字段描述");

        // Check label
        I18nResource label = i18nResourceService.findByKeyAndLang(
                "model.test_model.test_field.label", "zh-CN");
        assertThat(label).isNotNull();
        assertThat(label.getValue()).isEqualTo("测试字段");

        // Check placeholder
        I18nResource placeholder = i18nResourceService.findByKeyAndLang(
                "model.test_model.test_field.placeholder", "zh-CN");
        assertThat(placeholder).isNotNull();
        assertThat(placeholder.getValue()).isEqualTo("请输入");

        // Check description
        I18nResource description = i18nResourceService.findByKeyAndLang(
                "model.test_model.test_field.description", "zh-CN");
        assertThat(description).isNotNull();
        assertThat(description.getValue()).isEqualTo("字段描述");
    }

    @Test
    @Order(9)
    @DisplayName("syncFromField should only generate keys for non-null values")
    void syncFromFieldShouldSkipNullValues() {
        Long fieldId = 88887L;

        i18nResourceService.syncFromField(
                fieldId, "test_model2", "test_field2",
                "只有标签", null, null);

        // Label should exist
        I18nResource label = i18nResourceService.findByKeyAndLang(
                "model.test_model2.test_field2.label", "zh-CN");
        assertThat(label).isNotNull();

        // Placeholder should not exist
        I18nResource placeholder = i18nResourceService.findByKeyAndLang(
                "model.test_model2.test_field2.placeholder", "zh-CN");
        assertThat(placeholder).isNull();

        // Description should not exist
        I18nResource description = i18nResourceService.findByKeyAndLang(
                "model.test_model2.test_field2.description", "zh-CN");
        assertThat(description).isNull();
    }

    // ==================== Helpers ====================

    private List<I18nResource> buildTestResources(int count) {
        List<I18nResource> resources = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            resources.add(buildResource("test.batch.key" + i, "zh-CN", "Value " + i, "test"));
        }
        return resources;
    }

    private I18nResource buildResource(String key, String lang, String value, String source) {
        return I18nResource.builder()
                .i18nKey(key)
                .lang(lang)
                .value(value)
                .source(source)
                .status(I18nResource.STATUS_APPROVED)
                .build();
    }
}
