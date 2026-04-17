package com.auraboot.framework.meta.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelCapabilities;
import com.auraboot.framework.meta.dto.ModelDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1 Task 3: verify MetaModelService persists sourceType/sourceRef/capabilities and
 * normalizes field-level sortable/filterable flags into the capabilities whitelist.
 */
class MetaModelServiceVirtualIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MetaModelService metaModelService;

    @Test
    void save_and_reload_namedQuery_virtual_model_preserves_source_type_and_ref() {
        String code = "p1_t3_vq_" + System.currentTimeMillis();
        ModelDefinition def = ModelDefinition.builder()
            .code(code)
            .displayName("Sales Summary P1 T3")
            .sourceType("namedQuery")
            .sourceRef("queries/sales_summary_test.sql")
            .primaryKey("id")
            .capabilities(ModelCapabilities.virtualReadOnly())
            .build();

        ModelDefinition saved = metaModelService.saveDefinition(def);
        assertThat(saved.getSourceType()).isEqualTo("namedQuery");
        assertThat(saved.getSourceRef()).isEqualTo("queries/sales_summary_test.sql");

        ModelDefinition reloaded = metaModelService.getDefinitionByCode(code);
        assertThat(reloaded.getSourceType()).isEqualTo("namedQuery");
        assertThat(reloaded.getSourceRef()).isEqualTo("queries/sales_summary_test.sql");
        assertThat(reloaded.getCapabilities().isList()).isTrue();
        assertThat(reloaded.getCapabilities().isCreate()).isFalse();
    }

    @Test
    void field_level_sortable_flags_are_normalized_into_capabilities_whitelist() {
        String code = "p1_t3_norm_" + System.currentTimeMillis();
        ModelDefinition def = ModelDefinition.builder()
            .code(code)
            .displayName("Normalize Test P1 T3")
            .sourceType("sqlView")
            .sourceRef("v_test_norm")
            .primaryKey("id")
            .fields(List.of(
                FieldDefinition.builder().code("id").sortable(true).filterable(false).build(),
                FieldDefinition.builder().code("name").sortable(true).filterable(true).build(),
                FieldDefinition.builder().code("created_at").sortable(true).filterable(true).build()
            ))
            .capabilities(ModelCapabilities.builder().list(true).detail(true).sort(true).filter(true).build())
            .build();

        ModelDefinition saved = metaModelService.saveDefinition(def);

        assertThat(saved.getCapabilities().getSortableFields())
            .containsExactlyInAnyOrder("id", "name", "created_at");
        assertThat(saved.getCapabilities().getFilterableFields())
            .containsExactlyInAnyOrder("name", "created_at");
    }

    @Test
    void caller_supplied_whitelist_is_overridden_by_normalization() {
        String code = "p1_t3_override_" + System.currentTimeMillis();
        ModelDefinition def = ModelDefinition.builder()
            .code(code)
            .displayName("Override Test P1 T3")
            .sourceType("namedQuery")
            .sourceRef("queries/override.sql")
            .primaryKey("id")
            .fields(List.of(
                FieldDefinition.builder().code("name").sortable(true).build()
            ))
            // caller supplies a lie — should be overridden by normalization
            .capabilities(ModelCapabilities.builder()
                .sort(true)
                .sortableFields(List.of("some_other_field"))
                .build())
            .build();

        ModelDefinition saved = metaModelService.saveDefinition(def);
        assertThat(saved.getCapabilities().getSortableFields())
            .containsExactly("name");
    }

    @Test
    void physical_model_save_still_works() {
        String code = "p1_t3_phys_" + System.currentTimeMillis();
        ModelDefinition def = ModelDefinition.builder()
            .code(code)
            .displayName("Physical Backward Compat")
            .tableName("mt_" + code)
            .sourceType("physical")
            .primaryKey("id")
            .capabilities(ModelCapabilities.fullPhysical())
            .build();

        ModelDefinition saved = metaModelService.saveDefinition(def);
        assertThat(saved.getSourceType()).isEqualTo("physical");
        ModelDefinition reloaded = metaModelService.getDefinitionByCode(code);
        assertThat(reloaded.getSourceType()).isEqualTo("physical");
        assertThat(reloaded.getCapabilities().isCreate()).isTrue();
    }
}
