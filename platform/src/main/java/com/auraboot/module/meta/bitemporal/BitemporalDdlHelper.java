package com.auraboot.module.meta.bitemporal;

import org.springframework.stereotype.Component;

/**
 * Helper for generating DDL statements for bitemporal tables.
 * Provides column definitions, exclusion constraints, and current-view SQL.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Component
public class BitemporalDdlHelper {

    /**
     * Generate the standard bitemporal column definitions to append to a CREATE TABLE.
     *
     * @return SQL fragment with column definitions (no leading comma)
     */
    public String generateBitemporalColumns() {
        return """
                entity_key BIGINT NOT NULL,
                valid_from DATE NOT NULL DEFAULT CURRENT_DATE,
                valid_to DATE NOT NULL DEFAULT '9999-12-31',
                txn_from TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                txn_to TIMESTAMPTZ,
                superseded_by BIGINT,
                tenant_id BIGINT NOT NULL""";
    }

    /**
     * Generate a GiST exclusion constraint that prevents overlapping valid-time ranges
     * for the same entity_key within the same tenant, considering only current (non-superseded) rows.
     *
     * <p>Requires the btree_gist extension:
     * {@code CREATE EXTENSION IF NOT EXISTS btree_gist;}
     *
     * @param tableName the table name (must be a safe SQL identifier)
     * @return the ALTER TABLE SQL for the exclusion constraint
     * @throws IllegalArgumentException if tableName contains unsafe characters
     */
    public String generateExclusionConstraint(String tableName) {
        validateTableName(tableName);
        return "ALTER TABLE " + tableName + " ADD CONSTRAINT " + tableName + "_no_overlap "
                + "EXCLUDE USING gist ("
                + "entity_key WITH =, "
                + "tenant_id WITH =, "
                + "daterange(valid_from, valid_to) WITH &&"
                + ") WHERE (txn_to IS NULL)";
    }

    /**
     * Generate a CREATE VIEW statement that shows only the current versions.
     * "Current" means txn_to IS NULL (not superseded) and today falls within [valid_from, valid_to).
     *
     * @param tableName the base table name (must be a safe SQL identifier)
     * @return the CREATE OR REPLACE VIEW SQL
     * @throws IllegalArgumentException if tableName contains unsafe characters
     */
    public String generateCurrentView(String tableName) {
        validateTableName(tableName);
        String viewName = tableName + "_current";
        return "CREATE OR REPLACE VIEW " + viewName + " AS "
                + "SELECT * FROM " + tableName
                + " WHERE txn_to IS NULL"
                + " AND valid_from <= CURRENT_DATE"
                + " AND valid_to > CURRENT_DATE";
    }

    /**
     * Validate that a table name contains only safe characters to prevent SQL injection.
     */
    private void validateTableName(String tableName) {
        if (tableName == null || !tableName.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Invalid table name: " + tableName);
        }
    }
}
