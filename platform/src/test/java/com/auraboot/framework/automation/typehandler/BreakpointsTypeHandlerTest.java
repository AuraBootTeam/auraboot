package com.auraboot.framework.automation.typehandler;

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

class BreakpointsTypeHandlerTest {

    private BreakpointsTypeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new BreakpointsTypeHandler();
    }

    @Test
    void getNullableResult_validJson() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("bp")).thenReturn("[1,2,3]");
        assertThat(handler.getNullableResult(rs, "bp")).containsExactly(1, 2, 3);
    }

    @Test
    void getNullableResult_null_emptyList() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("bp")).thenReturn(null);
        assertThat(handler.getNullableResult(rs, "bp")).isEmpty();
    }

    @Test
    void getNullableResult_byIndex_blank_emptyList() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn(" ");
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
        handler.setNonNullParameter(ps, 1, List.of(1, 5), JdbcType.OTHER);
        verify(ps).setObject(eq(1), any());
    }
}
