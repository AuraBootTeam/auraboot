package com.auraboot.framework.meta.ddl;

import com.auraboot.framework.meta.dto.FieldDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PostgresDdlDialect implements DdlDialect {

    private static final Logger log = LoggerFactory.getLogger(PostgresDdlDialect.class);

    @Override
    public String getName() {
        return "PostgreSQL";
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
                return "integer";
            case "long":
                return "bigint";
            case "decimal":
            case "money":
                Integer precision = field.getPrecision();
                Integer scale = field.getScale();
                if (precision != null && scale != null) {
                    return "DECIMAL(" + precision + "," + scale + ")";
                } else if (precision != null) {
                    // Runtime field metadata often uses `precision` as UI decimal places
                    // when `scale` is absent (for example moneyinput precision=2).
                    // Treat that shape as a display-scale hint and keep a safe total
                    // precision to avoid generating unusable DECIMAL(2,2) columns.
                    return "DECIMAL(19," + precision + ")";
                }
                return "DECIMAL(10,2)";
            case "boolean":
                return "boolean";
            case "date":
                return "date";
            case "datetime":
            case "timestamp":
                return "timestamptz";
            case "time":
                return "time";
            case "json":
            case "jsonb":
                return "jsonb";
            case "array":
                return "TEXT[]";
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

        if ("string".equalsIgnoreCase(dataType) || "text".equalsIgnoreCase(dataType)
                || "json".equalsIgnoreCase(dataType) || "jsonb".equalsIgnoreCase(dataType)) {
            return "'" + value.replace("'", "''") + "'";
        }

        if ("date".equalsIgnoreCase(dataType) || "datetime".equalsIgnoreCase(dataType) || "timestamp".equalsIgnoreCase(dataType)) {
            if ("NOW()".equalsIgnoreCase(value) || "current_timestamp".equalsIgnoreCase(value)) {
                return "current_timestamp";
            }
            return "'" + value + "'";
        }

        if ("boolean".equalsIgnoreCase(dataType)) {
            return Boolean.parseBoolean(value) ? "true" : "false";
        }

        return value;
    }

    @Override
    public String getTimestampType() {
        return "timestamptz";
    }

    @Override
    public String getVarcharType(int length) {
        return "VARCHAR(" + length + ")";
    }

    @Override
    public String getTableSuffix() {
        return "";
    }

    @Override
    public boolean tableExists(java.sql.Connection connection, String tableName) throws java.sql.SQLException {
        // Use information_schema directly — more reliable than JDBC metadata with connection pools
        String sql = "SELECT EXISTS(SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = ?)";
        try (java.sql.PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tableName.toLowerCase());
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    @Override
    public boolean columnExists(java.sql.Connection connection, String tableName, String columnName) throws java.sql.SQLException {
        String sql = "SELECT EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ? AND column_name = ?)";
        try (java.sql.PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tableName.toLowerCase());
            ps.setString(2, columnName.toLowerCase());
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    @Override
    public String getColumnTypeDefinition(java.sql.Connection connection, String tableName, String columnName) throws java.sql.SQLException {
        String sql = """
                SELECT data_type, character_maximum_length, numeric_precision, numeric_scale, udt_name
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """;
        try (java.sql.PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tableName.toLowerCase());
            ps.setString(2, columnName.toLowerCase());
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new java.sql.SQLException("Column not found: " + tableName + "." + columnName);
                }
                String dataType = rs.getString("data_type");
                Integer charLength = (Integer) rs.getObject("character_maximum_length");
                Integer precision = (Integer) rs.getObject("numeric_precision");
                Integer scale = (Integer) rs.getObject("numeric_scale");
                String udtName = rs.getString("udt_name");
                if ("character varying".equalsIgnoreCase(dataType) && charLength != null) {
                    return "VARCHAR(" + charLength + ")";
                }
                if (("numeric".equalsIgnoreCase(dataType) || "decimal".equalsIgnoreCase(dataType)) && precision != null) {
                    return scale != null ? "DECIMAL(" + precision + "," + scale + ")" : "DECIMAL(" + precision + ")";
                }
                if ("timestamp with time zone".equalsIgnoreCase(dataType)) {
                    return "TIMESTAMPTZ";
                }
                if ("ARRAY".equalsIgnoreCase(dataType) && udtName != null) {
                    return udtName.toUpperCase() + "[]";
                }
                return dataType != null ? dataType.toUpperCase() : null;
            }
        }
    }

    @Override
    public boolean isColumnNullable(java.sql.Connection connection, String tableName, String columnName) throws java.sql.SQLException {
        String sql = "SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ? AND column_name = ?";
        try (java.sql.PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tableName.toLowerCase());
            ps.setString(2, columnName.toLowerCase());
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return "YES".equalsIgnoreCase(rs.getString("is_nullable"));
                }
            }
        }
        throw new java.sql.SQLException("Column not found: " + tableName + "." + columnName);
    }

    @Override
    public boolean indexExists(java.sql.Connection connection, String tableName, String indexName) throws java.sql.SQLException {
        String sql = "SELECT EXISTS(SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND tablename = ? AND indexname = ?)";
        try (java.sql.PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tableName.toLowerCase());
            ps.setString(2, indexName.toLowerCase());
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }
}
