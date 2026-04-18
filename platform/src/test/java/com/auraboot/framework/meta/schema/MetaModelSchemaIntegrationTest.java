package com.auraboot.framework.meta.schema;

import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1 Task 1: verify ab_meta_model virtual-model DDL additions
 * (source_type / source_ref / capabilities columns + chk_model_source constraint).
 */
class MetaModelSchemaIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void ab_meta_model_has_source_type_column_with_default_physical() {
        String defaultExpr = jdbc.queryForObject(
            "SELECT column_default FROM information_schema.columns " +
            "WHERE table_name='ab_meta_model' AND column_name='source_type'",
            String.class
        );
        assertThat(defaultExpr).contains("physical");
    }

    @Test
    void ab_meta_model_has_source_ref_and_capabilities_columns() {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*)::int FROM information_schema.columns " +
            "WHERE table_name='ab_meta_model' " +
            "AND column_name IN ('source_ref','capabilities')",
            Integer.class
        );
        assertThat(count).isEqualTo(2);
    }

    @Test
    void check_constraint_rejects_physical_without_table_name() {
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO ab_meta_model (pid, tenant_id, code, source_type, version) " +
            "VALUES ('p1-t1-invalid-physical', 1, 'p1_t1_invalid_physical', 'physical', 1)"
        )).hasMessageContaining("chk_model_source");
    }

    @Test
    void check_constraint_rejects_virtual_without_source_ref() {
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO ab_meta_model (pid, tenant_id, code, source_type, table_name, version) " +
            "VALUES ('p1-t1-invalid-virtual', 1, 'p1_t1_invalid_virtual', 'namedQuery', 'unused', 1)"
        )).hasMessageContaining("chk_model_source");
    }

    @Test
    void check_constraint_accepts_valid_physical_model() {
        jdbc.update(
            "INSERT INTO ab_meta_model (pid, tenant_id, code, table_name, source_type, version) " +
            "VALUES ('p1-t1-valid-physical', 1, 'p1_t1_valid_physical', 'mt_test', 'physical', 1)"
        );
        jdbc.update("DELETE FROM ab_meta_model WHERE pid='p1-t1-valid-physical'");
    }

    @Test
    void check_constraint_accepts_valid_virtual_model() {
        jdbc.update(
            "INSERT INTO ab_meta_model (pid, tenant_id, code, source_type, source_ref, version) " +
            "VALUES ('p1-t1-valid-virtual', 1, 'p1_t1_valid_virtual', 'namedQuery', 'queries/test.sql', 1)"
        );
        jdbc.update("DELETE FROM ab_meta_model WHERE pid='p1-t1-valid-virtual'");
    }

    /**
     * P1 Followup Issue B (soft constraint):
     * legacy physical rows that lack table_name but carry any extension.modelType hint
     * (flat or nested under extension.extension.modelType) are tolerated so pre-existing
     * fixtures survive reset_db cleanly. Genuinely malformed rows (no hint at all) stay rejected.
     */
    @Test
    void check_constraint_accepts_legacy_physical_without_table_when_flat_modelType_present() {
        jdbc.update(
            "INSERT INTO ab_meta_model (pid, tenant_id, code, source_type, version, extension) " +
            "VALUES ('p1-t13-legacy-flat', 1, 'p1_t13_legacy_flat', 'physical', 1, " +
            "'{\"modelType\":\"view\"}'::jsonb)"
        );
        jdbc.update("DELETE FROM ab_meta_model WHERE pid='p1-t13-legacy-flat'");
    }

    @Test
    void check_constraint_accepts_legacy_physical_without_table_when_nested_modelType_present() {
        // 217 pre-existing rows store modelType at extension.extension.modelType (nested form).
        jdbc.update(
            "INSERT INTO ab_meta_model (pid, tenant_id, code, source_type, version, extension) " +
            "VALUES ('p1-t13-legacy-nested', 1, 'p1_t13_legacy_nested', 'physical', 1, " +
            "'{\"extension\":{\"modelType\":\"entity\"}}'::jsonb)"
        );
        jdbc.update("DELETE FROM ab_meta_model WHERE pid='p1-t13-legacy-nested'");
    }

    @Test
    void check_constraint_still_rejects_physical_without_table_and_with_empty_extension() {
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO ab_meta_model (pid, tenant_id, code, source_type, version, extension) " +
            "VALUES ('p1-t13-truly-broken', 1, 'p1_t13_truly_broken', 'physical', 1, " +
            "'{}'::jsonb)"
        )).hasMessageContaining("chk_model_source");
    }
}
