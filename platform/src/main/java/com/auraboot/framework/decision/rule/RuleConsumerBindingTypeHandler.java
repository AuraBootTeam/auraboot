package com.auraboot.framework.decision.rule;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * JSONB mapper for the platform rule-consumer binding contract.
 */
public class RuleConsumerBindingTypeHandler extends BaseTypeHandler<RuleConsumerBinding> {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    @Override
    public void setNonNullParameter(
            PreparedStatement ps,
            int i,
            RuleConsumerBinding parameter,
            JdbcType jdbcType) throws SQLException {
        try {
            PGobject json = new PGobject();
            json.setType("jsonb");
            json.setValue(OBJECT_MAPPER.writeValueAsString(parameter));
            ps.setObject(i, json);
        } catch (JsonProcessingException e) {
            throw new SQLException("Error converting RuleConsumerBinding to JSON: " + e.getMessage(), e);
        }
    }

    @Override
    public RuleConsumerBinding getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parseJson(rs.getString(columnName));
    }

    @Override
    public RuleConsumerBinding getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parseJson(rs.getString(columnIndex));
    }

    @Override
    public RuleConsumerBinding getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parseJson(cs.getString(columnIndex));
    }

    private RuleConsumerBinding parseJson(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, RuleConsumerBinding.class);
        } catch (JsonProcessingException e) {
            throw new SQLException("Error parsing RuleConsumerBinding JSON: " + e.getMessage(), e);
        }
    }
}
