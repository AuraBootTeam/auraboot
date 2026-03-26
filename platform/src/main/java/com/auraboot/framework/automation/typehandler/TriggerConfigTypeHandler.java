package com.auraboot.framework.automation.typehandler;

import com.auraboot.framework.automation.entity.TriggerConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * TypeHandler for TriggerConfig JSONB field
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@MappedTypes(TriggerConfig.class)
@MappedJdbcTypes(JdbcType.OTHER)
public class TriggerConfigTypeHandler extends BaseTypeHandler<TriggerConfig> {

    private static final ObjectMapper objectMapper;

    static {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, TriggerConfig parameter, JdbcType jdbcType)
            throws SQLException {
        try {
            String json = objectMapper.writeValueAsString(parameter);
            PGobject jsonObject = new PGobject();
            jsonObject.setType("jsonb");
            jsonObject.setValue(json);
            ps.setObject(i, jsonObject);
        } catch (JsonProcessingException e) {
            throw new SQLException("Error converting TriggerConfig to JSON string", e);
        }
    }

    @Override
    public TriggerConfig getNullableResult(ResultSet rs, String columnName) throws SQLException {
        String json = rs.getString(columnName);
        return parseJson(json);
    }

    @Override
    public TriggerConfig getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String json = rs.getString(columnIndex);
        return parseJson(json);
    }

    @Override
    public TriggerConfig getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        String json = cs.getString(columnIndex);
        return parseJson(json);
    }

    private TriggerConfig parseJson(String json) throws SQLException {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, TriggerConfig.class);
        } catch (JsonProcessingException e) {
            throw new SQLException("Error parsing JSON string to TriggerConfig: " + e.getMessage(), e);
        }
    }
}
