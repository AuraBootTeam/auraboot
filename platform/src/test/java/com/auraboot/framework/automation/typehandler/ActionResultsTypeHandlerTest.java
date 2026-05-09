package com.auraboot.framework.automation.typehandler;

import com.auraboot.framework.automation.entity.AutomationLog.ActionResult;
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

class ActionResultsTypeHandlerTest {

    private ActionResultsTypeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ActionResultsTypeHandler();
    }

    @Test
    void getNullableResult_byColumnName_validJson() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("results")).thenReturn(
                "[{\"sequence\":1,\"actionType\":\"send\",\"status\":\"success\"}]");
        List<ActionResult> r = handler.getNullableResult(rs, "results");
        assertThat(r).hasSize(1);
        assertThat(r.get(0).getActionType()).isEqualTo("send");
    }

    @Test
    void getNullableResult_byColumnName_null_returnsEmptyList() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("results")).thenReturn(null);
        assertThat(handler.getNullableResult(rs, "results")).isEmpty();
    }

    @Test
    void getNullableResult_byColumnName_blank_returnsEmptyList() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("results")).thenReturn("   ");
        assertThat(handler.getNullableResult(rs, "results")).isEmpty();
    }

    @Test
    void getNullableResult_byIndex_invalidJson_throwsSQLException() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("nope");
        assertThatThrownBy(() -> handler.getNullableResult(rs, 1)).isInstanceOf(SQLException.class);
    }

    @Test
    void getNullableResult_callableStatement_validJson() throws SQLException {
        CallableStatement cs = mock(CallableStatement.class);
        when(cs.getString(1)).thenReturn("[]");
        assertThat(handler.getNullableResult(cs, 1)).isEmpty();
    }

    @Test
    void setNonNullParameter_writesJsonbObject() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        ActionResult r = new ActionResult();
        r.setSequence(1);
        r.setActionType("notify");
        r.setStatus("success");
        handler.setNonNullParameter(ps, 1, List.of(r), JdbcType.OTHER);
        verify(ps).setObject(eq(1), any());
    }
}
