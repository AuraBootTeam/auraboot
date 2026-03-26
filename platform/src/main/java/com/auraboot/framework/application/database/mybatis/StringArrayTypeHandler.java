package com.auraboot.framework.application.database.mybatis;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MyBatis TypeHandler for String[] to PostgreSQL text[] array
 */
@MappedTypes(String[].class)
public class StringArrayTypeHandler extends BaseTypeHandler<String[]> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String[] parameter, JdbcType jdbcType)
            throws SQLException {
        // Convert String[] to PostgreSQL array format
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        for (int j = 0; j < parameter.length; j++) {
            if (j > 0) {
                sb.append(",");
            }
            // Escape quotes and wrap in quotes
            String escaped = parameter[j].replace("\"", "\\\"");
            sb.append("\"").append(escaped).append("\"");
        }
        sb.append("}");
        
        PGobject arrayObject = new PGobject();
        arrayObject.setType("text[]");
        arrayObject.setValue(sb.toString());
        ps.setObject(i, arrayObject);
    }

    @Override
    public String[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parseArray(rs.getArray(columnName));
    }

    @Override
    public String[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parseArray(rs.getArray(columnIndex));
    }

    @Override
    public String[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parseArray(cs.getArray(columnIndex));
    }

    private String[] parseArray(java.sql.Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return new String[0];
        }
        
        try {
            Object[] array = (Object[]) sqlArray.getArray();
            if (array == null) {
                return new String[0];
            }
            
            String[] result = new String[array.length];
            for (int i = 0; i < array.length; i++) {
                result[i] = array[i] != null ? array[i].toString() : null;
            }
            return result;
        } catch (Exception e) {
            throw new SQLException("Failed to parse array", e);
        }
    }
}