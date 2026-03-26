package com.auraboot.framework.application.database.mybatis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 通用Jackson JSON类型处理器
 * 用于处理数据库JSON字段与Java对象之间的转换
 *
 * @param <T> 要处理的对象类型
 * @author AuraBoot Team
 * @since 1.0.0
 */
public abstract class GenericJacksonTypeHandler<T> extends BaseTypeHandler<T> {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final Class<T> type;

    protected GenericJacksonTypeHandler(Class<T> type) {
        this.type = type;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, T parameter, JdbcType jdbcType) throws SQLException {
        try {
            String json = objectMapper.writeValueAsString(parameter);
            // 对于PostgreSQL的JSONB类型，需要使用setObject而不是setString

                // 使用PGobject来处理JSONB类型
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

    /**
     * 解析JSON字符串为对象
     *
     * @param json JSON字符串
     * @return 解析后的对象
     * @throws SQLException SQL异常
     */
    private T parseJson(String json) throws SQLException {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }

        try {
            T t = objectMapper.readValue(json, type);
            return t;
        } catch (JsonProcessingException e) {
            throw new SQLException("Error parsing JSON string to object: " + e.getMessage(), e);
        }
    }
}