package com.auraboot.framework.meta.ddl;

import com.auraboot.framework.meta.dto.FieldDefinition;

/**
 * Database DDL dialect abstraction for dynamic schema generation.
 */
public interface DdlDialect {

    String getName();

    String mapDataType(FieldDefinition field);

    String formatDefaultValue(Object defaultValue, String dataType);

    String getTimestampType();

    String getVarcharType(int length);

    String getTableSuffix();

    /**
     * The USING clause of an {@code ALTER TABLE t ALTER COLUMN c TYPE targetType} statement, with
     * its leading space, or an empty string when the database converts the stored values itself.
     *
     * @param columnName  the column being converted; it is placed in the clause as it is
     * @param currentType the column's type as {@link #getColumnTypeDefinition} reports it, or null
     *                    when it is not known
     * @param targetType  the type as {@link #mapDataType} names it
     */
    default String getAlterColumnTypeUsing(String columnName, String currentType, String targetType) {
        return "";
    }

    boolean tableExists(java.sql.Connection connection, String tableName) throws java.sql.SQLException;

    boolean columnExists(java.sql.Connection connection, String tableName, String columnName) throws java.sql.SQLException;

    String getColumnTypeDefinition(java.sql.Connection connection, String tableName, String columnName) throws java.sql.SQLException;

    boolean isColumnNullable(java.sql.Connection connection, String tableName, String columnName) throws java.sql.SQLException;

    boolean indexExists(java.sql.Connection connection, String tableName, String indexName) throws java.sql.SQLException;
}
