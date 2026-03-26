package com.auraboot.framework.automation.typehandler;

import com.auraboot.framework.automation.entity.AutomationLog.ActionResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import org.postgresql.util.PGobject;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * TypeHandler for List<ActionResult> JSONB field
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@MappedTypes(List.class)
public class ActionResultsTypeHandler extends BaseTypeHandler<List<ActionResult>> {

    private static final ObjectMapper objectMapper;
    private static final TypeReference<List<ActionResult>> TYPE_REF = new TypeReference<>() {};

    static {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<ActionResult> parameter, JdbcType jdbcType)
            throws SQLException {
        try {
            String json = objectMapper.writeValueAsString(parameter);
            PGobject jsonObject = new PGobject();
            jsonObject.setType("jsonb");
            jsonObject.setValue(json);
            ps.setObject(i, jsonObject);
        } catch (JsonProcessingException e) {
            throw new SQLException("Error converting action results to JSON string", e);
        }
    }

    @Override
    public List<ActionResult> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        String json = rs.getString(columnName);
        return parseJson(json);
    }

    @Override
    public List<ActionResult> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String json = rs.getString(columnIndex);
        return parseJson(json);
    }

    @Override
    public List<ActionResult> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        String json = cs.getString(columnIndex);
        return parseJson(json);
    }

    private List<ActionResult> parseJson(String json) throws SQLException {
        if (json == null || json.trim().isEmpty()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, TYPE_REF);
        } catch (JsonProcessingException e) {
            throw new SQLException("Error parsing JSON string to action results: " + e.getMessage(), e);
        }
    }
}
