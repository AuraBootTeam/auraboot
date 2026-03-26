package com.auraboot.framework.application.database.mybatis;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public abstract class GenericJavaTypeJacksonTypeHandler<T> extends BaseTypeHandler<T> {

    protected static final ObjectMapper objectMapper = new ObjectMapper();

    private final JavaType javaType;

    protected GenericJavaTypeJacksonTypeHandler(JavaType javaType) {
        this.javaType = javaType;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, T parameter, JdbcType jdbcType) throws SQLException {
        try {
            String json = objectMapper.writeValueAsString(parameter);

            org.postgresql.util.PGobject jsonObject = new org.postgresql.util.PGobject();
            jsonObject.setType("jsonb");
            jsonObject.setValue(json);
            ps.setObject(i, jsonObject);

        } catch (JsonProcessingException e) {
            throw new SQLException("Error converting object to JSON string", e);
        } catch (SQLException e) {
            throw new SQLException("Error setting JSONB parameter", e);
        }
    }

    @Override
    public T getNullableResult(ResultSet rs, String columnName) throws SQLException {
        String json = rs.getString(columnName);
        return parseJson(json);
    }

    @Override
    public T getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String json = rs.getString(columnIndex);
        return parseJson(json);
    }

    @Override
    public T getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        String json = cs.getString(columnIndex);
        return parseJson(json);
    }

    private T parseJson(String json) throws SQLException {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }

        try {
            return objectMapper.readValue(json, javaType);
        } catch (JsonProcessingException e) {
            throw new SQLException("Error parsing JSON string to object: " + e.getMessage(), e);
        }
    }
}
