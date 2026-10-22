package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.dto.MetaFieldUpdateRequest;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for field version changes that must refresh model-level projections.
 */
@DisplayName("P5 metadata field version mutations refresh model definitions")
class MetaFieldCurrentVersionCacheInvalidationIT extends BaseIntegrationTest {

    @Autowired
    private MetaModelService metaModelService;

    @Autowired
    private MetaFieldService metaFieldService;

    @Autowired
    private MetaModelMapper metaModelMapper;

    @Autowired
    private MetaFieldMapper metaFieldMapper;

    @BeforeEach
    void clearMetadataCacheBeforeTest() {
        metaModelService.clearAllCache();
    }

    @AfterEach
    void clearMetadataCacheAfterTest() {
        metaModelService.clearAllCache();
    }

    @Test
    @DisplayName("field update moves bindings to the new current field version and evicts model definitions")
    void fieldUpdateMovesBindingsToNewCurrentVersionAndEvictsModelDefinition() {
        Model model = insertModel("p5_field_update_model");
        Field field = insertField("p5_field_update", "string", "Original Field");
        metaModelService.bindFieldToModel(
                model.getId(),
                field.getId(),
                10,
                false,
                true,
                true,
                null,
                null,
                null,
                null
        );

        FieldDefinition warmed = findFieldDefinition(model.getCode(), field.getCode());
        assertThat(warmed.getDataType()).isEqualTo("string");
        assertThat(warmed.getDisplayName()).isEqualTo("Original Field");

        MetaFieldUpdateRequest request = new MetaFieldUpdateRequest();
        request.setDataType("text");
        request.setStatus(StatusConstants.PUBLISHED);
        request.setExtension(Map.of("displayName", "Updated Field"));
        MetaFieldDTO updated = metaFieldService.update(field.getPid(), request);

        assertThat(updated.getId()).isNotEqualTo(field.getId());
        assertThat(updated.getVersion()).isEqualTo(2);

        FieldDefinition refreshed = findFieldDefinition(model.getCode(), field.getCode());
        assertThat(refreshed.getDataType()).isEqualTo("text");
        assertThat(refreshed.getDisplayName()).isEqualTo("Updated Field");
        assertThat(metaModelService.getModelFieldBindings(model.getId(), false))
                .anySatisfy(binding -> assertThat(binding.getFieldId()).isEqualTo(updated.getId()));
    }

    private FieldDefinition findFieldDefinition(String modelCode, String fieldCode) {
        return metaModelService.getModelDefinition(modelCode)
                .map(ModelDefinition::getFields)
                .stream()
                .flatMap(List::stream)
                .filter(field -> fieldCode.equals(field.getCode()))
                .findFirst()
                .orElseThrow();
    }

    private Model insertModel(String prefix) {
        String code = prefix + "_" + System.currentTimeMillis() + "_" + System.nanoTime();
        Model model = new Model();
        model.setPid(code);
        model.setTenantId(getTestTenant().getId());
        model.setCode(code);
        model.setExtension(extension("displayName", code, "modelType", "entity"));
        model.setVersion(1);
        model.setIsCurrent(true);
        model.setStatus(StatusConstants.DRAFT);
        model.setCreatedAt(Instant.now());
        model.setUpdatedAt(Instant.now());
        model.setDeletedFlag(false);
        metaModelMapper.insert(model);
        return model;
    }

    private Field insertField(String prefix, String dataType, String displayName) {
        String code = prefix + "_" + System.currentTimeMillis() + "_" + System.nanoTime();
        Field field = new Field();
        field.setPid(code);
        field.setTenantId(getTestTenant().getId());
        field.setCode(code);
        field.setDataType(dataType);
        field.setExtension(extension("displayName", displayName));

        FieldFeatureBean feature = new FieldFeatureBean();
        feature.setRequired(false);
        feature.setUnique(false);
        field.setFeature(feature);

        field.setVersion(1);
        field.setIsCurrent(true);
        field.setStatus(StatusConstants.PUBLISHED);
        field.setCreatedAt(Instant.now());
        field.setUpdatedAt(Instant.now());
        field.setDeletedFlag(false);
        metaFieldMapper.insert(field);
        return field;
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
