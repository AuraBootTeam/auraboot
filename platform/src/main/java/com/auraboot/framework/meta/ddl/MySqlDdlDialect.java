package com.auraboot.framework.meta.ddl;

import com.auraboot.framework.meta.dto.FieldDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MySqlDdlDialect implements DdlDialect {

    private static final Logger log = LoggerFactory.getLogger(MySqlDdlDialect.class);

    @Override
    public String getName() {
        return "MySQL";
    }

    @Override
    public String mapDataType(FieldDefinition field) {
        String dataType = field.getDataType();
        if (dataType == null) {
            return getVarcharType(255);
        }

        switch (dataType.toLowerCase()) {
            case "string":
                Integer maxLength = field.getMaxLength();
                if (maxLength != null && maxLength > 0) {
                    return getVarcharType(maxLength);
                }
                return getVarcharType(255);
            case "text":
                return "text";
            case "integer":
                return "int";
            case "long":
                return "bigint";
            case "decimal":
                Integer precision = field.getPrecision();
                Integer scale = field.getScale();
                if (precision != null && scale != null) {
                    return "DECIMAL(" + precision + "," + scale + ")";
                } else if (precision != null) {
                    return "DECIMAL(19," + precision + ")";
                }
                return "DECIMAL(10,2)";
            case "boolean":
                return "TINYINT(1)";
            case "date":
                return "date";
            case "datetime":
            case "timestamp":
                return "datetime";
            case "time":
                return "time";
            case "json":
                return "json";
            default:
                log.warn("Unknown data type: {}, using VARCHAR(255)", dataType);
                return getVarcharType(255);
        }
    }

    @Override
    public String formatDefaultValue(Object defaultValue, String dataType) {
        if (defaultValue == null) {
            return "null";
        }

        String value = defaultValue.toString();

        if ("string".equalsIgnoreCase(dataType) || "text".equalsIgnoreCase(dataType) || "json".equalsIgnoreCase(dataType)) {
            return "'" + value.replace("'", "''") + "'";
        }

        if ("date".equalsIgnoreCase(dataType) || "datetime".equalsIgnoreCase(dataType) || "timestamp".equalsIgnoreCase(dataType)) {
            if ("NOW()".equalsIgnoreCase(value) || "current_timestamp".equalsIgnoreCase(value)) {
                return "current_timestamp";
            }
            return "'" + value + "'";
        }

        if ("boolean".equalsIgnoreCase(dataType)) {
            return Boolean.parseBoolean(value) ? "1" : "0";
        }

        return value;
    }

    @Override
    public String getTimestampType() {
        return "datetime";
    }

    @Override
    public String getVarcharType(int length) {
        return "VARCHAR(" + length + ")";
    }

    @Override
    public String getTableSuffix() {
        return " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
    }

    @Override
    public boolean tableExists(java.sql.Connection connection, String tableName) throws java.sql.SQLException {
        java.sql.DatabaseMetaData metaData = connection.getMetaData();
        try (java.sql.ResultSet tables = metaData.getTables(connection.getCatalog(), null, tableName, new String[] { "table" })) {
            if (tables.next()) {
                return true;
            }
        }
        try (java.sql.ResultSet tables = metaData.getTables(connection.getCatalog(), null, tableName.toUpperCase(), new String[] { "table" })) {
            return tables.next();
        }
    }

    @Override
    public boolean columnExists(java.sql.Connection connection, String tableName, String columnName) throws java.sql.SQLException {
        java.sql.DatabaseMetaData metaData = connection.getMetaData();
        try (java.sql.ResultSet columns = metaData.getColumns(connection.getCatalog(), null, tableName, columnName)) {
            if (columns.next()) {
                return true;
            }
        }
        try (java.sql.ResultSet columns = metaData.getColumns(connection.getCatalog(), null, tableName.toUpperCase(), columnName.toUpperCase())) {
            return columns.next();
        }
    }

    @Override
    public String getColumnTypeDefinition(java.sql.Connection connection, String tableName, String columnName) throws java.sql.SQLException {
        java.sql.DatabaseMetaData metaData = connection.getMetaData();
        try (java.sql.ResultSet columns = metaData.getColumns(connection.getCatalog(), null, tableName, columnName)) {
            String type = mapColumnType(columns, tableName, columnName);
            if (type != null) return type;
        }
        try (java.sql.ResultSet columns = metaData.getColumns(connection.getCatalog(), null, tableName.toUpperCase(), columnName.toUpperCase())) {
            String type = mapColumnType(columns, tableName, columnName);
            if (type != null) return type;
        }
        throw new java.sql.SQLException("Column not found: " + tableName + "." + columnName);
    }

    @Override
    public boolean isColumnNullable(java.sql.Connection connection, String tableName, String columnName) throws java.sql.SQLException {
        java.sql.DatabaseMetaData metaData = connection.getMetaData();
        try (java.sql.ResultSet columns = metaData.getColumns(connection.getCatalog(), null, tableName, columnName)) {
            if (columns.next()) {
                return columns.getInt("nullable") != java.sql.DatabaseMetaData.columnNoNulls;
            }
        }
        try (java.sql.ResultSet columns = metaData.getColumns(connection.getCatalog(), null, tableName.toUpperCase(), columnName.toUpperCase())) {
            if (columns.next()) {
                return columns.getInt("nullable") != java.sql.DatabaseMetaData.columnNoNulls;
            }
        }
        throw new java.sql.SQLException("Column not found: " + tableName + "." + columnName);
    }

    @Override
    public boolean indexExists(java.sql.Connection connection, String tableName, String indexName) throws java.sql.SQLException {
        java.sql.DatabaseMetaData metaData = connection.getMetaData();
        try (java.sql.ResultSet indexes = metaData.getIndexInfo(connection.getCatalog(), null, tableName, false, false)) {
            while (indexes.next()) {
                String name = indexes.getString("index_name");
                if (name != null && name.equalsIgnoreCase(indexName)) {
                    return true;
                }
            }
        }
        try (java.sql.ResultSet indexes = metaData.getIndexInfo(connection.getCatalog(), null, tableName.toUpperCase(), false, false)) {
            while (indexes.next()) {
                String name = indexes.getString("index_name");
                if (name != null && name.equalsIgnoreCase(indexName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String mapColumnType(java.sql.ResultSet columns, String tableName, String columnName) throws java.sql.SQLException {
        if (!columns.next()) {
            return null;
        }
        String typeName = columns.getString("TYPE_NAME");
        int columnSize = columns.getInt("COLUMN_SIZE");
        int decimalDigits = columns.getInt("DECIMAL_DIGITS");
        if (typeName == null) {
            throw new java.sql.SQLException("Column type unavailable: " + tableName + "." + columnName);
        }
        String normalized = typeName.toUpperCase();
        if (normalized.startsWith("VARCHAR")) {
            return "VARCHAR(" + columnSize + ")";
        }
        if (normalized.startsWith("DECIMAL") || normalized.startsWith("NUMERIC")) {
            return "DECIMAL(" + columnSize + "," + decimalDigits + ")";
        }
        return normalized;
    }
}
