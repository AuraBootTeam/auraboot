package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for P5 metadata cache correctness.
 *
 * <p>The BOM/quote runtime repeatedly resolves {@link ModelDefinition}. If field binding mutations
 * leave {@code modelDefinitions} warm, later commands keep seeing stale fields or stale binding
 * attributes even though the database is already correct.
 */
@DisplayName("P5 metadata field binding mutations evict model definition caches")
class ModelFieldBindingCacheInvalidationIT extends BaseIntegrationTest {

    @Autowired
    private MetaModelService metaModelService;

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
    @DisplayName("bindFieldToModel evicts cached model definition")
    void bindFieldToModelEvictsCachedModelDefinition() {
        Model model = insertModel("p5_bind_model");
        Field field = insertField("p5_bind_field", false);

        assertThat(findFieldDefinition(model.getCode(), field.getCode()))
                .as("first read warms modelDefinitions without the new field")
                .isEmpty();

        metaModelService.bindFieldToModel(
                model.getId(),
                field.getId(),
                10,
                true,
                true,
                true,
                null,
                null,
                null,
                null
        );

        FieldDefinition refreshed = findFieldDefinition(model.getCode(), field.getCode()).orElseThrow();
        assertThat(refreshed.isRequired()).isTrue();
        assertThat(refreshed.getSortOrder()).isEqualTo(10);
    }

    @Test
    @DisplayName("updateFieldBinding evicts cached model definition and binding projections")
    void updateFieldBindingEvictsCachedModelDefinitionAndBindingProjection() {
        Model model = insertModel("p5_update_model");
        Field field = insertField("p5_update_field", false);
        metaModelService.bindFieldToModel(
                model.getId(),
                field.getId(),
                10,
                true,
                true,
                true,
                null,
                null,
                null,
                null
        );

        FieldDefinition warmedField = findFieldDefinition(model.getCode(), field.getCode()).orElseThrow();
        assertThat(warmedField.isRequired()).isTrue();
        assertThat(warmedField.getSortOrder()).isEqualTo(10);
        assertThat(findBinding(metaModelService.getModelFieldBindings(model.getId(), false), field.getId()))
                .extracting(ModelFieldBinding::getFieldOrder)
                .isEqualTo(10);

        ModelFieldBinding binding = metaModelService.getFieldBinding(model.getId(), field.getId()).orElseThrow();
        binding.setRequired(false);
        binding.setFieldOrder(25);
        metaModelService.updateFieldBinding(binding);

        FieldDefinition refreshedField = findFieldDefinition(model.getCode(), field.getCode()).orElseThrow();
        assertThat(refreshedField.isRequired()).isFalse();
        assertThat(refreshedField.getSortOrder()).isEqualTo(25);
        assertThat(findBinding(metaModelService.getModelFieldBindings(model.getId(), false), field.getId()))
                .extracting(ModelFieldBinding::getFieldOrder)
                .isEqualTo(25);
    }

    @Test
    @DisplayName("unbindFieldFromModel evicts cached model definition and binding projections")
    void unbindFieldFromModelEvictsCachedModelDefinitionAndBindingProjection() {
        Model model = insertModel("p5_unbind_model");
        Field field = insertField("p5_unbind_field", false);
        metaModelService.bindFieldToModel(
                model.getId(),
                field.getId(),
                10,
                true,
                true,
                true,
                null,
                null,
                null,
                null
        );

        assertThat(findFieldDefinition(model.getCode(), field.getCode())).isPresent();
        assertThat(findBinding(metaModelService.getModelFieldBindings(model.getId(), false), field.getId()))
                .isNotNull();

        assertThat(metaModelService.unbindFieldFromModel(model.getId(), field.getId())).isTrue();

        assertThat(findFieldDefinition(model.getCode(), field.getCode()))
                .as("modelDefinitions must not retain the unbound field")
                .isEmpty();
        assertThat(findBinding(metaModelService.getModelFieldBindings(model.getId(), false), field.getId()))
                .isNull();
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

    private Field insertField(String prefix, boolean fieldLevelRequired) {
        String code = prefix + "_" + System.currentTimeMillis() + "_" + System.nanoTime();
        Field field = new Field();
        field.setPid(code);
        field.setTenantId(getTestTenant().getId());
        field.setCode(code);
        field.setDataType("string");
        field.setExtension(extension("displayName", code));

        FieldFeatureBean feature = new FieldFeatureBean();
        feature.setRequired(fieldLevelRequired);
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

    private Optional<FieldDefinition> findFieldDefinition(String modelCode, String fieldCode) {
        return metaModelService.getModelDefinition(modelCode)
                .map(ModelDefinition::getFields)
                .stream()
                .flatMap(List::stream)
                .filter(field -> fieldCode.equals(field.getCode()))
                .findFirst();
    }

    private ModelFieldBinding findBinding(List<ModelFieldBinding> bindings, Long fieldId) {
        return bindings.stream()
                .filter(binding -> fieldId.equals(binding.getFieldId()))
                .findFirst()
                .orElse(null);
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
