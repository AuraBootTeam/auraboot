package com.auraboot.framework.application.database.mybatis;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Database-agnostic JSON/JSONB type handler.
 * <p>
 * Handles JSONB (PostgreSQL) and JSON (MySQL) columns transparently.
 * <ul>
 *   <li>PostgreSQL: Uses PGobject with type "jsonb" when the PG driver is available</li>
 *   <li>MySQL: Uses setString() directly, since MySQL JSON columns accept plain strings</li>
 * </ul>
 */
public class JsonbStringTypeHandler extends BaseTypeHandler<String> {

    private static final boolean PG_DRIVER_PRESENT;

    static {
        boolean present;
        try {
            Class.forName("org.postgresql.util.PGobject");
            present = true;
        } catch (ClassNotFoundException e) {
            present = false;
        }
        PG_DRIVER_PRESENT = present;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
        if (PG_DRIVER_PRESENT && isPostgresConnection(ps)) {
            // Use PGobject for PostgreSQL to ensure proper JSONB handling
            org.postgresql.util.PGobject jsonObject = new org.postgresql.util.PGobject();
            jsonObject.setType("jsonb");
            jsonObject.setValue(parameter);
            ps.setObject(i, jsonObject);
        } else {
            // MySQL and other databases: plain string works for JSON columns
            ps.setString(i, parameter);
        }
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        Object obj = rs.getObject(columnName);
        return obj == null ? null : obj.toString();
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        Object obj = rs.getObject(columnIndex);
        return obj == null ? null : obj.toString();
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        Object obj = cs.getObject(columnIndex);
        return obj == null ? null : obj.toString();
    }

    /**
     * Check if the underlying connection is PostgreSQL.
     */
    private boolean isPostgresConnection(PreparedStatement ps) {
        try {
            String driverName = ps.getConnection().getMetaData().getDriverName();
            return driverName != null && driverName.toLowerCase().contains("postgresql");
        } catch (SQLException e) {
            // Fallback: if PG driver is on classpath, assume PG
            return true;
        }
    }
}
