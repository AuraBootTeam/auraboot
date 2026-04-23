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

    boolean tableExists(java.sql.Connection connection, String tableName) throws java.sql.SQLException;

    boolean columnExists(java.sql.Connection connection, String tableName, String columnName) throws java.sql.SQLException;

    String getColumnTypeDefinition(java.sql.Connection connection, String tableName, String columnName) throws java.sql.SQLException;

    boolean isColumnNullable(java.sql.Connection connection, String tableName, String columnName) throws java.sql.SQLException;

    boolean indexExists(java.sql.Connection connection, String tableName, String indexName) throws java.sql.SQLException;
}
