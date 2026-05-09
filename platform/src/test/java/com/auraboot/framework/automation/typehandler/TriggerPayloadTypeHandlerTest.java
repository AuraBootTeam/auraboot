package com.auraboot.framework.automation.typehandler;

import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TriggerPayloadTypeHandlerTest {

    private TriggerPayloadTypeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TriggerPayloadTypeHandler();
    }

    @Test
    void getNullableResult_validMap() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("payload")).thenReturn("{\"a\":1,\"b\":\"x\"}");
        Map<String, Object> r = handler.getNullableResult(rs, "payload");
        assertThat(r).containsKeys("a", "b");
    }

    @Test
    void getNullableResult_null_emptyMap() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("payload")).thenReturn(null);
        assertThat(handler.getNullableResult(rs, "payload")).isEmpty();
    }

    @Test
    void getNullableResult_byIndex_blank_emptyMap() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("");
        assertThat(handler.getNullableResult(rs, 1)).isEmpty();
    }

    @Test
    void getNullableResult_callableStatement_invalid_throws() throws SQLException {
        CallableStatement cs = mock(CallableStatement.class);
        when(cs.getString(1)).thenReturn("not json");
        assertThatThrownBy(() -> handler.getNullableResult(cs, 1)).isInstanceOf(SQLException.class);
    }

    @Test
    void setNonNullParameter_setsJsonb() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        handler.setNonNullParameter(ps, 1, Map.of("k", "v"), JdbcType.OTHER);
        verify(ps).setObject(eq(1), any());
    }
}
