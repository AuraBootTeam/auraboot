package com.auraboot.framework.automation.typehandler;

import com.auraboot.framework.automation.entity.TriggerConfig;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TriggerConfigTypeHandler.
 */
class TriggerConfigTypeHandlerTest {

    private TriggerConfigTypeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TriggerConfigTypeHandler();
    }

    // =========================================================
    // getNullableResult via ResultSet (by column name)
    // =========================================================

    @Test
    void getNullableResult_byColumnName_validJson_parsesObject() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("trigger_config")).thenReturn(
                "{\"modelCode\":\"crm_lead\",\"watchFields\":[\"status\"]}");

        TriggerConfig result = handler.getNullableResult(rs, "trigger_config");

        assertThat(result).isNotNull();
        assertThat(result.getModelCode()).isEqualTo("crm_lead");
        assertThat(result.getWatchFields()).containsExactly("status");
    }

    @Test
    void getNullableResult_byColumnName_null_returnsNull() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("trigger_config")).thenReturn(null);

        TriggerConfig result = handler.getNullableResult(rs, "trigger_config");

        assertThat(result).isNull();
    }

    @Test
    void getNullableResult_byColumnName_empty_returnsNull() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("trigger_config")).thenReturn("  ");

        TriggerConfig result = handler.getNullableResult(rs, "trigger_config");

        assertThat(result).isNull();
    }

    @Test
    void getNullableResult_byColumnName_invalidJson_throwsSQLException() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("trigger_config")).thenReturn("{not valid json");

        assertThatThrownBy(() -> handler.getNullableResult(rs, "trigger_config"))
                .isInstanceOf(SQLException.class);
    }

    // =========================================================
    // getNullableResult via ResultSet (by column index)
    // =========================================================

    @Test
    void getNullableResult_byIndex_validJson_parsesObject() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("{\"cron\":\"0 0 * * *\"}");

        TriggerConfig result = handler.getNullableResult(rs, 1);

        assertThat(result).isNotNull();
        assertThat(result.getCron()).isEqualTo("0 0 * * *");
    }

    @Test
    void getNullableResult_byIndex_null_returnsNull() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn(null);

        TriggerConfig result = handler.getNullableResult(rs, 1);

        assertThat(result).isNull();
    }

    // =========================================================
    // getNullableResult via CallableStatement
    // =========================================================

    @Test
    void getNullableResult_callableStatement_validJson_parsesObject() throws SQLException {
        CallableStatement cs = mock(CallableStatement.class);
        when(cs.getString(1)).thenReturn("{\"modelCode\":\"invoice\"}");

        TriggerConfig result = handler.getNullableResult(cs, 1);

        assertThat(result).isNotNull();
        assertThat(result.getModelCode()).isEqualTo("invoice");
    }

    @Test
    void getNullableResult_callableStatement_null_returnsNull() throws SQLException {
        CallableStatement cs = mock(CallableStatement.class);
        when(cs.getString(1)).thenReturn(null);

        TriggerConfig result = handler.getNullableResult(cs, 1);

        assertThat(result).isNull();
    }

    // =========================================================
    // setNonNullParameter
    // =========================================================

    @Test
    void setNonNullParameter_setsJsonbObject() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        TriggerConfig config = new TriggerConfig();
        config.setModelCode("crm_lead");
        config.setWatchFields(List.of("status", "priority"));

        // Should not throw
        assertThatCode(() -> handler.setNonNullParameter(ps, 1, config, JdbcType.OTHER))
                .doesNotThrowAnyException();

        verify(ps).setObject(eq(1), any());
    }

    // =========================================================
    // Unknown fields (FAIL_ON_UNKNOWN_PROPERTIES = false)
    // =========================================================

    @Test
    void getNullableResult_unknownFields_ignoredGracefully() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("trigger_config")).thenReturn(
                "{\"modelCode\":\"order\",\"unknownFieldXyz\":\"ignored\"}");

        TriggerConfig result = handler.getNullableResult(rs, "trigger_config");

        assertThat(result).isNotNull();
        assertThat(result.getModelCode()).isEqualTo("order");
    }
}
