package com.auraboot.framework.integration.meta;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.entity.payload.ExtensionBean;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.meta.service.ModelFieldBindingService;
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
 * GAP-265 regression test: verify that {@link MetaModelService#getModelFields(String)}
 * returns {@link FieldDefinition#isRequired()} sourced from the per-binding required flag,
 * not from the field-level {@code FieldFeatureBean.required}.
 *
 * <p>After GAP-259, {@code constraints.required} no longer propagates to the global
 * {@code FieldFeatureBean.required} (it would cause cross-model pollution). All downstream
 * readers ({@code SchemaManagementServiceImpl} DDL emission, {@code ExcelImportService}
 * template + validation, {@code BpmFormBindingController} field metadata,
 * {@code DynamicController} page meta, {@code PluginGeneratorService}) consume
 * {@code FieldDefinition.isRequired()} which is now overlaid from the binding.
 */
@DisplayName("GAP-265 — FieldDefinition.isRequired() honors binding-level required")
class FieldRequiredBindingOverlayIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MetaModelService metaModelService;

    @Autowired
    private MetaModelMapper metaModelMapper;

    @Autowired
    private MetaFieldMapper metaFieldMapper;

    @Autowired
    private ModelFieldBindingService bindingService;

    private Model testModel;
    private Field optionalAtFieldLevel;
    private Field requiredAtFieldLevel;

    @BeforeEach
    void setUp() {
        setupTenantContext();
        long ts = System.currentTimeMillis();

        testModel = newModel("gap265_model_" + ts);
        metaModelMapper.insert(testModel);

        // field-level required = false (post GAP-259, this is the only allowed state)
        optionalAtFieldLevel = newField("gap265_field_opt_" + ts, false);
        metaFieldMapper.insert(optionalAtFieldLevel);

        // field-level required = true (legacy data — should NOT win over binding)
        requiredAtFieldLevel = newField("gap265_field_legacy_" + ts, true);
        metaFieldMapper.insert(requiredAtFieldLevel);
    }

    @Test
    @DisplayName("binding.required=true makes FieldDefinition.required=true even when field-level=false")
    void binding_required_true_overrides_field_level_false() {
        bindingService.bindFieldToModel(
            testModel.getPid(),
            optionalAtFieldLevel.getPid(),
            1,
            true,   // binding-level required = TRUE
            false,
            true
        );

        // bust cache and re-read
        List<FieldDefinition> fields = metaModelService
            .getModelDefinitionFromDb(testModel.getCode())
            .orElseThrow()
            .getFields();

        FieldDefinition fd = fields.stream()
            .filter(f -> f.getCode().equals(optionalAtFieldLevel.getCode()))
            .findFirst()
            .orElseThrow();

        assertThat(fd.isRequired())
            .as("binding.required=true must propagate to FieldDefinition.required (used by DDL/validation/Excel/BPM/page meta)")
            .isTrue();
    }

    @Test
    @DisplayName("binding.required=false makes FieldDefinition.required=false even when legacy field-level=true")
    void binding_required_false_overrides_legacy_field_level_true() {
        bindingService.bindFieldToModel(
            testModel.getPid(),
            requiredAtFieldLevel.getPid(),
            1,
            false,  // binding-level required = FALSE
            false,
            true
        );

        List<FieldDefinition> fields = metaModelService
            .getModelDefinitionFromDb(testModel.getCode())
            .orElseThrow()
            .getFields();

        FieldDefinition fd = fields.stream()
            .filter(f -> f.getCode().equals(requiredAtFieldLevel.getCode()))
            .findFirst()
            .orElseThrow();

        assertThat(fd.isRequired())
            .as("binding.required=false must override legacy field-level required=true; binding is authoritative")
            .isFalse();
    }

    // --- helpers ---

    private Model newModel(String code) {
        Model m = new Model();
        m.setPid(code);
        m.setTenantId(getTestTenant().getId());
        m.setCode(code);

        Map<String, Object> ext = new HashMap<>();
        ext.put("displayName", "GAP-265 Test Model");
        ext.put("modelType", "entity");
        ExtensionBean eb = new ExtensionBean();
        eb.setExtension(ext);
        m.setExtension(eb);

        m.setVersion(1);
        m.setIsCurrent(true);
        m.setStatus("published");
        m.setCreatedAt(Instant.now());
        m.setUpdatedAt(Instant.now());
        m.setDeletedFlag(false);
        return m;
    }

    private Field newField(String code, boolean fieldLevelRequired) {
        Field f = new Field();
        f.setPid(code);
        f.setTenantId(getTestTenant().getId());
        f.setCode(code);
        f.setDataType("string");

        Map<String, Object> ext = new HashMap<>();
        ext.put("displayName", code);
        ExtensionBean eb = new ExtensionBean();
        eb.setExtension(ext);
        f.setExtension(eb);

        FieldFeatureBean feature = new FieldFeatureBean();
        feature.setRequired(fieldLevelRequired);
        feature.setUnique(false);
        f.setFeature(feature);

        f.setVersion(1);
        f.setIsCurrent(true);
        f.setStatus("published");
        f.setCreatedAt(Instant.now());
        f.setUpdatedAt(Instant.now());
        f.setDeletedFlag(false);
        return f;
    }
}
