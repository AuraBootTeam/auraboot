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
}
