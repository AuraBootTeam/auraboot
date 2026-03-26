package com.auraboot.framework.application.typehandler;

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
 * 通用的Jackson类型处理器基类
 * 用于处理PostgreSQL的JSONB类型
 * 
 * @param <T> 要处理的Java类型
 */
public abstract class GenericJacksonTypeHandler<T> extends BaseTypeHandler<T> {
    
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final Class<T> type;
    
    public GenericJacksonTypeHandler(Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("Type argument cannot be null");
        }
        this.type = type;
    }
    
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, T parameter, JdbcType jdbcType) 
            throws SQLException {
        try {
            PGobject jsonObject = new PGobject();
            jsonObject.setType("jsonb");
            jsonObject.setValue(OBJECT_MAPPER.writeValueAsString(parameter));
            ps.setObject(i, jsonObject);
        } catch (JsonProcessingException e) {
            throw new SQLException("Error converting object to JSON: " + e.getMessage(), e);
        }
    }
    
    @Override
    public T getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parseJson(rs.getString(columnName));
    }
    
    @Override
    public T getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parseJson(rs.getString(columnIndex));
    }

    
    @Override
    public T getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parseJson(cs.getString(columnIndex));
    }
    
    private T parseJson(String json) throws SQLException {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new SQLException("Error parsing JSON to " + type.getSimpleName() + ": " + e.getMessage(), e);
        }
    }
}
