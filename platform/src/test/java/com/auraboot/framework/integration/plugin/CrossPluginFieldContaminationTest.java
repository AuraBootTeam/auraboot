package com.auraboot.framework.integration.plugin;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.dto.MetaModelCreateRequest;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.ModelFieldBinding;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.plugin.dto.imports.FieldDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.dto.imports.ModelFieldBindingDTO;
import com.auraboot.framework.plugin.service.impl.PluginResourceImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GAP-259 regression test: guard against field-level cross-plugin contamination.
 *
 * <p>Root cause background: {@code PluginResourceImporterImpl.buildFieldFeature()} previously
 * propagated {@code FieldDefinitionDTO.constraints.required} into the global
 * {@code FieldFeatureBean.required} column on {@code ab_meta_field}. That column is read by
 * downstream code (e.g. {@code SchemaManagementServiceImpl}) to decide NOT NULL semantics for
 * every model bound to the field. Because field codes share a global namespace across plugins,
 * a later plugin re-importing the same field code would silently flip required-ness on every
 * model already bound by an earlier plugin — the exact failure mode that leaked via
 * {@code plugins/page-manager/config/fields.json} (shared {@code model_code} field) and
 * broke unrelated plugins in production.
 *
 * <p>Design intent asserted here:
 * <ul>
 *   <li>Required-ness is a <b>per-binding</b> concept stored on
 *       {@code ab_meta_model_field_binding.is_required}. Plugin-A and plugin-B may bind the
 *       same shared field code to different models with independent {@code required} values.</li>
 *   <li>The <b>field-global</b> {@code feature.required} column must remain untouched by the
 *       importer. Re-importing the same field with a different {@code constraints.required}
 *       must NOT mutate the field's global feature.</li>
 *   <li>Other field-intrinsic constraints ({@code unique}, length/pattern validation) may
 *       still live on the field because they map to DB-level uniqueness / validators that
 *       are conceptually field-global.</li>
 * </ul>
 */
@DisplayName("GAP-259 cross-plugin field contamination regression")
class CrossPluginFieldContaminationTest extends BaseIntegrationTest {

    @Autowired
    private PluginResourceImporter importer;

    @Autowired
    private MetaFieldService metaFieldService;

    @Autowired
    private MetaFieldMapper metaFieldMapper;

    @Autowired
    private MetaModelService metaModelService;

    @Test
    @DisplayName("Per-binding required must not cross-pollinate between plugins sharing a field code")
    void fieldLevelRequiredMustNotCrossPollinate() {
        // Code columns are VARCHAR(26); keep test codes compact.
        String suffix = Long.toString(System.nanoTime() % 1_000_000L);
        String sharedFieldCode = "g259f_" + suffix;        // <= 26
        String modelACode = "g259ma_" + suffix;
        String modelBCode = "g259mb_" + suffix;
        String pluginAPid = "g259pa_" + suffix;
        String pluginBPid = "g259pb_" + suffix;
        String importIdA = "g259ia_" + suffix;
        String importIdB = "g259ib_" + suffix;
        Long tenantId = MetaContext.getCurrentTenantId();

        // Given: two draft models owned by two different plugins (not published, so no
        // schema sync runs — we only care about metadata contamination here).
        MetaModelDTO modelA = createDraftModel(modelACode, "GAP-259 Model A");
        MetaModelDTO modelB = createDraftModel(modelBCode, "GAP-259 Model B");

        // When: plugin-A imports `shared_field` WITHOUT any constraints (it only cares about
        // the field schema, not required-ness — required is a per-binding decision).
        FieldDefinitionDTO fieldA = FieldDefinitionDTO.builder()
                .code(sharedFieldCode)
                .displayName("Shared Field")
                .dataType("string")
                // NOTE: deliberately no constraints — plugin-A expresses no opinion on required.
                .build();
        importer.importField(fieldA, pluginAPid, importIdA, tenantId,
                ImportRequest.ConflictStrategy.OVERWRITE, false);

        ModelFieldBindingDTO bindingA = ModelFieldBindingDTO.builder()
                .modelCode(modelACode)
                .fieldCode(sharedFieldCode)
                .sequence(1)
                .required(false)              // model_a: optional in this model context
                .visible(true)
                .editable(true)
                .isSystemBinding(false)
                .build();
        importer.importModelFieldBinding(bindingA, pluginAPid, importIdA, tenantId,
                ImportRequest.ConflictStrategy.OVERWRITE);

        // And: plugin-B later re-imports the SAME field code WITH constraints.required=true
        // (same mistake page-manager made: treating field-level constraints as the truth).
        // This is the cross-plugin reimport path (updateFieldForReimport) that previously
        // mutated the field-global FieldFeatureBean and silently flipped NOT NULL semantics
        // for every model already bound — including plugin-A's model_a, whose binding has
        // required=false.
        FieldDefinitionDTO fieldB = FieldDefinitionDTO.builder()
                .code(sharedFieldCode)
                .displayName("Shared Field")
                .dataType("string")
                .constraints(FieldDefinitionDTO.FieldConstraints.builder()
                        .required(true)             // <-- the contaminating value
                        .build())
                .build();
        importer.importField(fieldB, pluginBPid, importIdB, tenantId,
                ImportRequest.ConflictStrategy.OVERWRITE, false);

        ModelFieldBindingDTO bindingB = ModelFieldBindingDTO.builder()
                .modelCode(modelBCode)
                .fieldCode(sharedFieldCode)
                .sequence(1)
                .required(true)               // model_b: required in this model context
                .visible(true)
                .editable(true)
                .isSystemBinding(false)
                .build();
        importer.importModelFieldBinding(bindingB, pluginBPid, importIdB, tenantId,
                ImportRequest.ConflictStrategy.OVERWRITE);

        // Then: per-binding required is honored independently — model_a stays required=false,
        // model_b sees required=true.
        ModelFieldBinding bindA = metaModelService
                .getFieldBinding(modelA.getId(), findFieldId(sharedFieldCode))
                .orElseThrow(() -> new AssertionError("binding A missing"));
        ModelFieldBinding bindB = metaModelService
                .getFieldBinding(modelB.getId(), findFieldId(sharedFieldCode))
                .orElseThrow(() -> new AssertionError("binding B missing"));

        assertFalse(Boolean.TRUE.equals(bindA.getRequired()),
                "model_a binding must stay required=false — plugin-B's field-level "
                        + "constraints.required=true must NOT flip plugin-A's binding");
        assertTrue(Boolean.TRUE.equals(bindB.getRequired()),
                "model_b binding must honor its own required=true");

        // And: the field-global FieldFeatureBean.required must not have been mutated by
        // either import. GAP-259 fix: buildFieldFeature() no longer propagates
        // constraints.required to the global feature bean. Asserting getRequired() is null
        // or false catches regressions where the propagation is re-introduced.
        Field rawFieldAfterBoth = metaFieldMapper.findCurrentByCode(sharedFieldCode);
        assertNotNull(rawFieldAfterBoth, "shared field entity missing");
        FieldFeatureBean featureAfterBoth = rawFieldAfterBoth.getFeature();
        boolean leakedRequiredAfterBoth = featureAfterBoth != null
                && Boolean.TRUE.equals(featureAfterBoth.getRequired());
        assertFalse(leakedRequiredAfterBoth,
                "field-global FieldFeatureBean.required must stay unset — required is a "
                        + "per-binding concept, not a field-level attribute. If this fails, "
                        + "PluginResourceImporterImpl.buildFieldFeature() is leaking "
                        + "constraints.required into the shared field, recreating GAP-259.");

        // Sanity: DTO-level isRequired() reflects the same — it must not flip between
        // plugin imports. (Currently MetaFieldDTO does not surface feature at all, so
        // this is always false; kept as belt-and-suspenders against future DTO changes.)
        MetaFieldDTO sharedField = metaFieldService.findCurrentByCode(sharedFieldCode)
                .orElseThrow(() -> new AssertionError("shared field missing"));
        assertNotNull(sharedField);
        assertFalse(sharedField.isRequired(),
                "MetaFieldDTO.isRequired() must remain false across plugin reimports");

        // And: downstream resolution of model_a's per-binding required still reads false
        // even though the most recent importer (plugin-B) declared constraints.required=true —
        // proving the binding row is the source of truth, independent of which plugin last
        // touched the field.
        assertEquals(false, bindA.getRequired(),
                "model_a's required must stay false regardless of plugin-B's reimport order");
    }

    private MetaModelDTO createDraftModel(String code, String displayName) {
        MetaModelCreateRequest req = new MetaModelCreateRequest();
        req.setCode(code);
        req.setDisplayName(displayName);
        req.setDescription("GAP-259 regression fixture");
        req.setModelType("entity");
        req.setTenantId(MetaContext.getCurrentTenantId());
        return metaModelService.create(req);
    }

    private Long findFieldId(String fieldCode) {
        return metaFieldService.findCurrentByCode(fieldCode)
                .orElseThrow(() -> new AssertionError("field missing: " + fieldCode))
                .getId();
    }
}
