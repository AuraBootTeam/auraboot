package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Regression coverage for current-version model mutations that must refresh modelDefinitions.
 */
@DisplayName("P5 metadata model mutations evict model definition caches")
class MetaDefinitionCacheInvalidationIT extends BaseIntegrationTest {

    @Autowired
    private MetaModelService metaModelService;

    @SpyBean
    private MetaModelMapper metaModelMapper;

    @SpyBean
    private MetaFieldMapper metaFieldMapper;

    @SpyBean
    private MetaModelFieldBindingMapper fieldBindingMapper;

    @BeforeEach
    void clearMetadataCacheBeforeTest() {
        metaModelService.clearAllCache();
    }

    @AfterEach
    void clearMetadataCacheAfterTest() {
        metaModelService.clearAllCache();
    }

    @Test
    @DisplayName("rollbackToVersion evicts cached current model definition")
    void rollbackToVersionEvictsCachedCurrentModelDefinition() {
        String code = "p5_model_rollback_" + System.currentTimeMillis() + "_" + System.nanoTime();
        insertModelVersion(code, 1, false, "Rollback Version One", "version-one");
        insertModelVersion(code, 2, true, "Rollback Version Two", "version-two");

        ModelDefinition warmed = metaModelService.getModelDefinition(code).orElseThrow();
        assertThat(warmed.getVersion()).isEqualTo(2);
        assertThat(warmed.getDisplayName()).isEqualTo("Rollback Version Two");

        metaModelService.rollbackToVersion(code, 1);

        ModelDefinition refreshed = metaModelService.getModelDefinition(code).orElseThrow();
        assertThat(refreshed.getVersion()).isEqualTo(1);
        assertThat(refreshed.getDisplayName()).isEqualTo("Rollback Version One");
        assertThat(refreshed.getDescription()).isEqualTo("version-one");
    }

    @Test
    @DisplayName("saveDefinition evicts cached current model definition")
    void saveDefinitionEvictsCachedCurrentModelDefinition() {
        String code = "p5_model_save_" + System.currentTimeMillis() + "_" + System.nanoTime();
        insertModelVersion(code, 1, true, "Original Saved Model", "original");

        ModelDefinition warmed = metaModelService.getModelDefinition(code).orElseThrow();
        assertThat(warmed.getDisplayName()).isEqualTo("Original Saved Model");

        metaModelService.saveDefinition(ModelDefinition.builder()
                .code(code)
                .sourceType("physical")
                .extension(Map.of(
                        "displayName", "Updated Saved Model",
                        "description", "updated"
                ))
                .build());

        ModelDefinition refreshed = metaModelService.getModelDefinition(code).orElseThrow();
        assertThat(refreshed.getDisplayName()).isEqualTo("Updated Saved Model");
        assertThat(refreshed.getDescription()).isEqualTo("updated");
    }

    @Test
    @DisplayName("delete evicts cached current model definition")
    void deleteEvictsCachedCurrentModelDefinition() {
        String code = "p5_model_delete_" + System.currentTimeMillis() + "_" + System.nanoTime();
        Model model = insertModelVersion(code, 1, true, "Delete Cached Model", "delete-target");

        assertThat(metaModelService.getModelDefinition(code)).isPresent();

        metaModelService.delete(model.getPid());

        assertThat(metaModelService.getModelDefinition(code)).isEmpty();
    }

    @Test
    @DisplayName("model field projection reuses the cached assembled definition")
    void modelFieldProjectionReusesCachedAssembledDefinition() {
        String code = "p5_model_projection_" + System.currentTimeMillis() + "_" + System.nanoTime();
        insertModelVersion(code, 1, true, "Cached Projection Model", "projection-cache");

        assertThat(metaModelService.getModelFields(code)).isNotEmpty();
        clearInvocations(metaModelMapper, metaFieldMapper, fieldBindingMapper);

        assertThat(metaModelService.getModelFields(code)).isNotEmpty();

        verifyNoInteractions(metaModelMapper, metaFieldMapper, fieldBindingMapper);
    }

    private Model insertModelVersion(String code, int version, boolean current, String displayName, String description) {
        Model model = new Model();
        model.setPid(code + "_v" + version);
        model.setTenantId(getTestTenant().getId());
        model.setCode(code);
        model.setExtension(extension(
                "displayName", displayName,
                "description", description,
                "modelType", "entity"
        ));
        model.setVersion(version);
        model.setIsCurrent(current);
        model.setStatus(StatusConstants.DRAFT);
        model.setCreatedAt(Instant.now());
        model.setUpdatedAt(Instant.now());
        model.setDeletedFlag(false);
        metaModelMapper.insert(model);
        return model;
    }

    private ExtensionBean extension(Object... entries) {
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            values.put((String) entries[i], entries[i + 1]);
        }
        ExtensionBean bean = new ExtensionBean();
        bean.setExtension(values);
        return bean;
    }
}
