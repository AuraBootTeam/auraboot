package com.auraboot.framework.meta.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelCapabilities;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.exception.MetaServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1 Task 3: verify MetaModelService persists sourceType/sourceRef/capabilities and
 * normalizes field-level sortable/filterable flags into the capabilities whitelist.
 */
class MetaModelServiceVirtualIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private MetaModelService metaModelService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

    /**
     * P1 Followup Issue A: ModelDefinition.primaryKey must round-trip through
     * the extension JSONB column so callers don't need the capabilities.detailKeyField
     * workaround (T7/T9/T12).
     */
    @Test
    void save_and_reload_preserves_primaryKey() {
        String code = "p1_t13_pk_" + System.currentTimeMillis();
        ModelDefinition def = ModelDefinition.builder()
            .code(code)
            .displayName("PrimaryKey Roundtrip")
            .sourceType("namedQuery")
            .sourceRef("queries/pk_roundtrip.sql")
            .primaryKey("order_id")
            .capabilities(ModelCapabilities.virtualReadOnly())
            .build();

        metaModelService.saveDefinition(def);
        ModelDefinition reloaded = metaModelService.getDefinitionByCode(code);
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getPrimaryKey()).isEqualTo("order_id");
    }

    @Test
    void malformed_capabilities_json_fails_fast_on_reload() {
        // Directly insert a row with invalid JSON via JDBC to simulate corruption.
        long ts = System.currentTimeMillis();
        String code = "p1_t3_malformed_" + ts;
        jdbcTemplate.update(
            "INSERT INTO ab_meta_model (pid, tenant_id, code, source_type, source_ref, version, " +
            "status, is_current, capabilities) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
            "p1-t3-mal-" + ts,
            testTenant.getId(), code, "namedQuery", "queries/x.sql", 1,
            "draft", true,
            // Valid JSON at the PG level (so JSONB accepts it) but structurally wrong
            // for ModelCapabilities — sortableFields expects a List, not a String.
            "{\"list\": true, \"sortableFields\": \"not-an-array\"}");

        assertThatThrownBy(() -> metaModelService.getDefinitionByCode(code))
            .isInstanceOf(MetaServiceException.class)
            .hasMessageContaining("capabilities");
    }

    @Test
    void searchModels_filters_by_sourceType_on_server_side() {
        String ts = String.valueOf(System.currentTimeMillis());

        metaModelService.saveDefinition(ModelDefinition.builder()
            .code("p1_t3_phys_filter_" + ts)
            .displayName("Physical Filter")
            .tableName("mt_p1_t3_phys_filter_" + ts)
            .sourceType("physical")
            .primaryKey("id")
            .capabilities(ModelCapabilities.fullPhysical())
            .build());

        metaModelService.saveDefinition(ModelDefinition.builder()
            .code("p1_t3_named_query_filter_" + ts)
            .displayName("NamedQuery Filter")
            .sourceType("namedQuery")
            .sourceRef("queries/filter_" + ts + ".sql")
            .primaryKey("id")
            .capabilities(ModelCapabilities.virtualReadOnly())
            .build());

        PageResult<?> physicalPage = metaModelService.searchModels(
            1, 50, "p1_t3_", null, null, null, null, "physical", null, null, true
        );
        assertThat(physicalPage.getRecords())
            .extracting("code")
            .contains("p1_t3_phys_filter_" + ts)
            .doesNotContain("p1_t3_named_query_filter_" + ts);

        PageResult<?> namedQueryPage = metaModelService.searchModels(
            1, 50, "p1_t3_", null, null, null, null, "namedQuery", null, null, true
        );
        assertThat(namedQueryPage.getRecords())
            .extracting("code")
            .contains("p1_t3_named_query_filter_" + ts)
            .doesNotContain("p1_t3_phys_filter_" + ts);
    }
}
