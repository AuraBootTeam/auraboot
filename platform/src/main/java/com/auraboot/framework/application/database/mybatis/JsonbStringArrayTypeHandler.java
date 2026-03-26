package com.auraboot.framework.application.database.mybatis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * PostgreSQL JSONB type handler for String[] arrays.
 * Converts between String[] and JSONB array format.
 */
public class JsonbStringArrayTypeHandler extends BaseTypeHandler<String[]> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String[] parameter, JdbcType jdbcType) throws SQLException {
        PGobject jsonObject = new PGobject();
        jsonObject.setType("jsonb");
        try {
            jsonObject.setValue(MAPPER.writeValueAsString(parameter));
        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to convert String[] to JSON", e);
        }
        ps.setObject(i, jsonObject);
    }

    @Override
    public String[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parseJsonArray(rs.getString(columnName));
    }

    @Override
    public String[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parseJsonArray(rs.getString(columnIndex));
    }

    @Override
    public String[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parseJsonArray(cs.getString(columnIndex));
    }

    private String[] parseJsonArray(String json) throws SQLException {
        if (json == null || json.isEmpty()) {
            return new String[0];
        }
        try {
            return MAPPER.readValue(json, String[].class);
        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to parse JSON array: " + json, e);
        }
    }
}
