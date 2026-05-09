package com.auraboot.framework.automation.typehandler;

import com.auraboot.framework.automation.entity.AutomationAction;
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

class ActionsTypeHandlerTest {

    private ActionsTypeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ActionsTypeHandler();
    }

    @Test
    void getNullableResult_byColumnName_validJson() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("actions")).thenReturn("[{\"sequence\":1,\"type\":\"SEND\"}]");
        List<AutomationAction> result = handler.getNullableResult(rs, "actions");
        assertThat(result).hasSize(1);
    }

    @Test
    void getNullableResult_null_emptyList() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("actions")).thenReturn(null);
        assertThat(handler.getNullableResult(rs, "actions")).isEmpty();
    }

    @Test
    void getNullableResult_byIndex() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(2)).thenReturn("[]");
        assertThat(handler.getNullableResult(rs, 2)).isEmpty();
    }

    @Test
    void getNullableResult_callableStatement_invalid_throws() throws SQLException {
        CallableStatement cs = mock(CallableStatement.class);
        when(cs.getString(1)).thenReturn("{not}");
        assertThatThrownBy(() -> handler.getNullableResult(cs, 1)).isInstanceOf(SQLException.class);
    }

    @Test
    void setNonNullParameter_setsJsonb() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        handler.setNonNullParameter(ps, 1, List.of(new AutomationAction()), JdbcType.OTHER);
        verify(ps).setObject(eq(1), any());
    }
}
