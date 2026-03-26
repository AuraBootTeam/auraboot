package com.auraboot.framework.application.database.mybatis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

/**
 * PostgreSQL JSONB type handler for List&lt;String&gt;.
 * Converts between List&lt;String&gt; and JSONB array format.
 */
public class JsonbListTypeHandler extends BaseTypeHandler<List<String>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> TYPE_REF = new TypeReference<>() {};

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<String> parameter, JdbcType jdbcType)
            throws SQLException {
        PGobject jsonObject = new PGobject();
        jsonObject.setType("jsonb");
        try {
            jsonObject.setValue(MAPPER.writeValueAsString(parameter));
        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to convert List<String> to JSON", e);
        }
        ps.setObject(i, jsonObject);
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parseJsonList(rs.getString(columnName));
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parseJsonList(rs.getString(columnIndex));
    }

    @Override
    public List<String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parseJsonList(cs.getString(columnIndex));
    }

    private List<String> parseJsonList(String json) throws SQLException {
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return MAPPER.readValue(json, TYPE_REF);
        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to parse JSON list: " + json, e);
        }
    }
}
