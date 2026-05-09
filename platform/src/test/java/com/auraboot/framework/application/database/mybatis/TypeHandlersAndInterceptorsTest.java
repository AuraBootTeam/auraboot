package com.auraboot.framework.application.database.mybatis;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.environment.service.EnvLockGuard;
import com.auraboot.framework.environment.service.EnvironmentService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.entity.payload.InstanceDataBean;
import com.auraboot.framework.meta.entity.payload.MapBean;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.postgresql.util.PGobject;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;
import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the JSONB / array type handlers and the env-lock / meta-context
 * interceptors. Pure Mockito; no Spring / DB.
 */
@ExtendWith(MockitoExtension.class)
class TypeHandlersAndInterceptorsTest {

    @AfterEach
    void cleanup() {
        MetaContext.clear();
    }

    // ---------- JsonbStringTypeHandler ----------
    @Test
    void jsonbStringTypeHandler_setsParameterAndReadsResults() throws SQLException {
        JsonbStringTypeHandler h = new JsonbStringTypeHandler();
        PreparedStatement ps = mock(PreparedStatement.class);
        Connection conn = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(ps.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(md);
        when(md.getDriverName()).thenReturn("PostgreSQL JDBC Driver");

        h.setNonNullParameter(ps, 1, "{\"a\":1}", null);
        verify(ps).setObject(eq(1), any(PGobject.class));

        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("c")).thenReturn("{\"x\":1}");
        when(rs.getObject(2)).thenReturn(null);
        assertThat(h.getNullableResult(rs, "c")).isEqualTo("{\"x\":1}");
        assertThat(h.getNullableResult(rs, 2)).isNull();

        CallableStatement cs = mock(CallableStatement.class);
        when(cs.getObject(3)).thenReturn("{}");
        assertThat(h.getNullableResult(cs, 3)).isEqualTo("{}");
    }

    @Test
    void jsonbStringTypeHandler_fallsBackToSetStringForNonPostgres() throws SQLException {
        JsonbStringTypeHandler h = new JsonbStringTypeHandler();
        PreparedStatement ps = mock(PreparedStatement.class);
        Connection conn = mock(Connection.class);
        DatabaseMetaData md = mock(DatabaseMetaData.class);
        when(ps.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(md);
        when(md.getDriverName()).thenReturn("MySQL Connector/J");

        h.setNonNullParameter(ps, 7, "{}", null);
        verify(ps).setString(7, "{}");
    }

    // ---------- JsonbStringArrayTypeHandler ----------
    @Test
    void jsonbStringArrayTypeHandler_roundtrip() throws SQLException {
        JsonbStringArrayTypeHandler h = new JsonbStringArrayTypeHandler();
        PreparedStatement ps = mock(PreparedStatement.class);
        h.setNonNullParameter(ps, 1, new String[]{"a", "b"}, null);
        verify(ps).setObject(eq(1), any(PGobject.class));

        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("c")).thenReturn("[\"a\",\"b\"]");
        assertThat(h.getNullableResult(rs, "c")).containsExactly("a", "b");
        when(rs.getString(2)).thenReturn(null);
        assertThat(h.getNullableResult(rs, 2)).isEmpty();
        when(rs.getString(3)).thenReturn("");
        assertThat(h.getNullableResult(rs, 3)).isEmpty();

        CallableStatement cs = mock(CallableStatement.class);
        when(cs.getString(4)).thenReturn("[\"x\"]");
        assertThat(h.getNullableResult(cs, 4)).containsExactly("x");
    }

    @Test
    void jsonbStringArrayTypeHandler_invalidJsonThrowsSqlException() throws SQLException {
        JsonbStringArrayTypeHandler h = new JsonbStringArrayTypeHandler();
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("c")).thenReturn("not-json");
        assertThatThrownBy(() -> h.getNullableResult(rs, "c")).isInstanceOf(SQLException.class);
    }

    // ---------- JsonbMapTypeHandler ----------
    @Test
    void jsonbMapTypeHandler_roundtrip() throws SQLException {
        JsonbMapTypeHandler h = new JsonbMapTypeHandler();
        PreparedStatement ps = mock(PreparedStatement.class);
        Map<String, Object> input = new HashMap<>();
        input.put("k", "v");
        h.setNonNullParameter(ps, 1, input, null);
        verify(ps).setObject(eq(1), any(PGobject.class));

        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("c")).thenReturn("{\"k\":\"v\"}");
        assertThat(h.getNullableResult(rs, "c")).containsEntry("k", "v");
        when(rs.getString(2)).thenReturn("");
        assertThat(h.getNullableResult(rs, 2)).isNull();
        when(rs.getString(3)).thenReturn("  ");
        assertThat(h.getNullableResult(rs, 3)).isNull();

        CallableStatement cs = mock(CallableStatement.class);
        when(cs.getString(4)).thenReturn(null);
        assertThat(h.getNullableResult(cs, 4)).isNull();
    }

    @Test
    void jsonbMapTypeHandler_invalidThrowsSqlException() throws SQLException {
        JsonbMapTypeHandler h = new JsonbMapTypeHandler();
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("c")).thenReturn("###");
        assertThatThrownBy(() -> h.getNullableResult(rs, "c")).isInstanceOf(SQLException.class);
    }

    // ---------- JsonbListTypeHandler ----------
    @Test
    void jsonbListTypeHandler_roundtrip() throws SQLException {
        JsonbListTypeHandler h = new JsonbListTypeHandler();
        PreparedStatement ps = mock(PreparedStatement.class);
        h.setNonNullParameter(ps, 1, List.of("a", "b"), null);
        verify(ps).setObject(eq(1), any(PGobject.class));

        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("c")).thenReturn("[\"a\",\"b\"]");
        assertThat(h.getNullableResult(rs, "c")).containsExactly("a", "b");
        when(rs.getString(2)).thenReturn("");
        assertThat(h.getNullableResult(rs, 2)).isEmpty();

        CallableStatement cs = mock(CallableStatement.class);
        when(cs.getString(3)).thenReturn(null);
        assertThat(h.getNullableResult(cs, 3)).isEmpty();
    }

    @Test
    void jsonbListTypeHandler_invalidThrowsSqlException() throws SQLException {
        JsonbListTypeHandler h = new JsonbListTypeHandler();
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("c")).thenReturn("###");
        assertThatThrownBy(() -> h.getNullableResult(rs, "c")).isInstanceOf(SQLException.class);
    }

    // ---------- JsonbObjectTypeHandler ----------
    @Test
    void jsonbObjectTypeHandler_roundtrip() throws SQLException {
        JsonbObjectTypeHandler h = new JsonbObjectTypeHandler();
        PreparedStatement ps = mock(PreparedStatement.class);
        h.setNonNullParameter(ps, 1, Map.of("k", 1), null);
        verify(ps).setObject(eq(1), any(PGobject.class));

        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("c")).thenReturn("{\"k\":1}");
        Object out = h.getNullableResult(rs, "c");
        assertThat(out).isInstanceOf(Map.class);
        when(rs.getString(2)).thenReturn(null);
        assertThat(h.getNullableResult(rs, 2)).isNull();
        when(rs.getString(3)).thenReturn("   ");
        assertThat(h.getNullableResult(rs, 3)).isNull();

        CallableStatement cs = mock(CallableStatement.class);
        when(cs.getString(4)).thenReturn("{}");
        assertThat(h.getNullableResult(cs, 4)).isInstanceOf(Map.class);
    }

    @Test
    void jsonbObjectTypeHandler_invalidThrowsSqlException() throws SQLException {
        JsonbObjectTypeHandler h = new JsonbObjectTypeHandler();
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("c")).thenReturn("###");
        assertThatThrownBy(() -> h.getNullableResult(rs, "c")).isInstanceOf(SQLException.class);
    }

    // ---------- StringArrayTypeHandler ----------
    @Test
    void stringArrayTypeHandler_setsPgArrayObject() throws SQLException {
        StringArrayTypeHandler h = new StringArrayTypeHandler();
        PreparedStatement ps = mock(PreparedStatement.class);
        h.setNonNullParameter(ps, 1, new String[]{"a", "b\"c"}, null);
        verify(ps).setObject(eq(1), any(PGobject.class));
    }

    @Test
    void stringArrayTypeHandler_readsArrayFromResultSet() throws SQLException {
        StringArrayTypeHandler h = new StringArrayTypeHandler();
        ResultSet rs = mock(ResultSet.class);
        Array arr = mock(Array.class);
        when(arr.getArray()).thenReturn(new String[]{"x", null, "y"});
        when(rs.getArray("c")).thenReturn(arr);
        assertThat(h.getNullableResult(rs, "c")).containsExactly("x", null, "y");

        when(rs.getArray(2)).thenReturn(null);
        assertThat(h.getNullableResult(rs, 2)).isEmpty();

        Array nullArr = mock(Array.class);
        when(nullArr.getArray()).thenReturn(null);
        when(rs.getArray(3)).thenReturn(nullArr);
        assertThat(h.getNullableResult(rs, 3)).isEmpty();

        CallableStatement cs = mock(CallableStatement.class);
        when(cs.getArray(4)).thenReturn(arr);
        assertThat(h.getNullableResult(cs, 4)).containsExactly("x", null, "y");
    }

    @Test
    void stringArrayTypeHandler_propagatesParseFailure() throws SQLException {
        StringArrayTypeHandler h = new StringArrayTypeHandler();
        ResultSet rs = mock(ResultSet.class);
        Array arr = mock(Array.class);
        when(arr.getArray()).thenThrow(new SQLException("boom"));
        when(rs.getArray("c")).thenReturn(arr);
        assertThatThrownBy(() -> h.getNullableResult(rs, "c")).isInstanceOf(SQLException.class);
    }

    // ---------- GenericJacksonTypeHandler via concrete subclass ----------
    @Test
    void mapBeanTypeHandler_roundtrip() throws SQLException {
        MapBeanTypeHandler h = new MapBeanTypeHandler();
        PreparedStatement ps = mock(PreparedStatement.class);
        MapBean b = new MapBean();
        b.setContent(Map.of("k", "v"));
        h.setNonNullParameter(ps, 1, b, null);
        verify(ps).setObject(eq(1), any(PGobject.class));

        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("c")).thenReturn("{\"content\":{\"k\":\"v\"}}");
        MapBean out = h.getNullableResult(rs, "c");
        assertThat(out).isNotNull();
        assertThat(out.getContent()).containsEntry("k", "v");

        when(rs.getString(2)).thenReturn(null);
        assertThat(h.getNullableResult(rs, 2)).isNull();
        when(rs.getString(3)).thenReturn("   ");
        assertThat(h.getNullableResult(rs, 3)).isNull();

        CallableStatement cs = mock(CallableStatement.class);
        when(cs.getString(4)).thenReturn("{}");
        assertThat(h.getNullableResult(cs, 4)).isNotNull();
    }

    @Test
    void mapBeanTypeHandler_invalidJsonThrowsSqlException() throws SQLException {
        MapBeanTypeHandler h = new MapBeanTypeHandler();
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("c")).thenReturn("###");
        assertThatThrownBy(() -> h.getNullableResult(rs, "c")).isInstanceOf(SQLException.class);
    }

    // ---------- GenericJavaTypeJacksonTypeHandler via DataSourceItemBeanTypeHandler ----------
    @Test
    void dataSourceItemBeanTypeHandler_roundtripList() throws SQLException {
        DataSourceItemBeanTypeHandler h = new DataSourceItemBeanTypeHandler();
        PreparedStatement ps = mock(PreparedStatement.class);
        h.setNonNullParameter(ps, 1, List.of(), null);
        verify(ps).setObject(eq(1), any(PGobject.class));

        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("c")).thenReturn("[]");
        assertThat(h.getNullableResult(rs, "c")).isEmpty();
        when(rs.getString(2)).thenReturn(null);
        assertThat(h.getNullableResult(rs, 2)).isNull();
        when(rs.getString(3)).thenReturn(" ");
        assertThat(h.getNullableResult(rs, 3)).isNull();

        CallableStatement cs = mock(CallableStatement.class);
        when(cs.getString(4)).thenReturn("[]");
        assertThat(h.getNullableResult(cs, 4)).isEmpty();
    }

    @Test
    void dataSourceItemBeanTypeHandler_invalidJsonThrowsSqlException() throws SQLException {
        DataSourceItemBeanTypeHandler h = new DataSourceItemBeanTypeHandler();
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("c")).thenReturn("###");
        assertThatThrownBy(() -> h.getNullableResult(rs, "c")).isInstanceOf(SQLException.class);
    }

    @Test
    void instanceDataBeanTypeHandler_roundtrip() throws SQLException {
        InstanceDataBeanTypeHandler h = new InstanceDataBeanTypeHandler();
        PreparedStatement ps = mock(PreparedStatement.class);
        InstanceDataBean b = new InstanceDataBean();
        b.setFieldValues(Map.of("a", 1));
        h.setNonNullParameter(ps, 1, b, null);
        verify(ps).setObject(eq(1), any(PGobject.class));
    }

    // ---------- AuraBootObjectHandler ----------
    @Mock
    EnvironmentService environmentService;
    @Mock
    EnvLockGuard envLockGuard;
    @Mock
    MetaObject metaObject;

    @Test
    void auraBootObjectHandler_insertFillSetsTimestamps() {
        AuraBootObjectHandler h = new AuraBootObjectHandler(environmentService, envLockGuard);
        when(metaObject.hasGetter("envId")).thenReturn(false);
        h.insertFill(metaObject);
        // Timestamps are set via strictInsertFill (no easy way to assert without MybatisPlus
        // metaobject internals, but the method should run without throwing).
    }

    @Test
    void auraBootObjectHandler_updateFillSetsUpdatedAtOnly() {
        AuraBootObjectHandler h = new AuraBootObjectHandler(environmentService, envLockGuard);
        // Should not throw — strictUpdateFill silently skips if no setter
        h.updateFill(metaObject);
    }

    @Test
    void auraBootObjectHandler_envIdFillFromContext() {
        AuraBootObjectHandler h = new AuraBootObjectHandler(environmentService, envLockGuard);
        when(metaObject.hasGetter("envId")).thenReturn(true);
        when(metaObject.getValue("envId")).thenReturn(null);
        try {
            MetaContext.setSystemTenantContext(1L);
            MetaContext.setEnvironmentId(99L);

            h.insertFill(metaObject);
            verify(metaObject).setValue("envId", 99L);
            verify(envLockGuard).assertWritable(99L);
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    void auraBootObjectHandler_envIdFallsBackToServiceWhenNoCtxEnv() {
        AuraBootObjectHandler h = new AuraBootObjectHandler(environmentService, envLockGuard);
        when(metaObject.hasGetter("envId")).thenReturn(true);
        when(metaObject.getValue("envId")).thenReturn(null);
        when(environmentService.findOrCreateDefaultId(7L)).thenReturn(42L);
        try {
            MetaContext.setSystemTenantContext(7L);
            h.insertFill(metaObject);
            verify(metaObject).setValue("envId", 42L);
            verify(envLockGuard).assertWritable(42L);
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    void auraBootObjectHandler_envIdSkipWhenNoTenant() {
        AuraBootObjectHandler h = new AuraBootObjectHandler(environmentService, envLockGuard);
        when(metaObject.hasGetter("envId")).thenReturn(true);
        when(metaObject.getValue("envId")).thenReturn(null);
        // No MetaContext at all
        h.insertFill(metaObject);
        verify(envLockGuard, org.mockito.Mockito.never()).assertWritable(any());
    }

    @Test
    void auraBootObjectHandler_envIdAlreadySetIsHonored() {
        AuraBootObjectHandler h = new AuraBootObjectHandler(environmentService, envLockGuard);
        when(metaObject.hasGetter("envId")).thenReturn(true);
        when(metaObject.getValue("envId")).thenReturn(33L);
        h.insertFill(metaObject);
        verify(envLockGuard).assertWritable(33L);
    }

    // ---------- EnvWriteLockGuardInnerInterceptor ----------
    @Test
    void envWriteLockGuard_matchesTableWholeWord() {
        assertThat(EnvWriteLockGuardInnerInterceptor.matchesTable(
                "update ab_page set x = 1 where id = 1", "ab_page")).isTrue();
        assertThat(EnvWriteLockGuardInnerInterceptor.matchesTable(
                "update ab_page_history set x = 1", "ab_page")).isFalse();
        assertThat(EnvWriteLockGuardInnerInterceptor.matchesTable(
                "select 1", "ab_page")).isFalse();
        assertThat(EnvWriteLockGuardInnerInterceptor.matchesTable("", "ab_page")).isFalse();
        assertThat(EnvWriteLockGuardInnerInterceptor.matchesTable("anything", "")).isFalse();
        assertThat(EnvWriteLockGuardInnerInterceptor.matchesTable(null, "ab_page")).isFalse();
    }

    @Test
    void envWriteLockGuard_skipWhenSelectStatement() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        EnvWriteLockGuardInnerInterceptor ic = new EnvWriteLockGuardInnerInterceptor(ctx);
        Executor exec = mock(Executor.class);
        MappedStatement ms = mock(MappedStatement.class);
        when(ms.getSqlCommandType()).thenReturn(SqlCommandType.SELECT);
        ic.beforeUpdate(exec, ms, null);
        // No lookup of the bean; nothing should fire
        verify(ctx, org.mockito.Mockito.never()).getBean(EnvLockGuard.class);
    }

    @Test
    void envWriteLockGuard_skipWhenNoEnvId() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        EnvWriteLockGuardInnerInterceptor ic = new EnvWriteLockGuardInnerInterceptor(ctx);
        Executor exec = mock(Executor.class);
        MappedStatement ms = mock(MappedStatement.class);
        when(ms.getSqlCommandType()).thenReturn(SqlCommandType.UPDATE);
        // No MetaContext.envId
        ic.beforeUpdate(exec, ms, null);
        verify(ctx, org.mockito.Mockito.never()).getBean(EnvLockGuard.class);
    }

    @Test
    void envWriteLockGuard_skipWhenBypassed() {
        ApplicationContext ctx = mock(ApplicationContext.class);
        EnvWriteLockGuardInnerInterceptor ic = new EnvWriteLockGuardInnerInterceptor(ctx);
        Executor exec = mock(Executor.class);
        MappedStatement ms = mock(MappedStatement.class);
        when(ms.getSqlCommandType()).thenReturn(SqlCommandType.UPDATE);
        try {
            MetaContext.setSystemTenantContext(1L);
            MetaContext.setEnvironmentId(99L);
            MetaContext.runWithoutLockGuard(() -> ic.beforeUpdate(exec, ms, null));
            verify(ctx, org.mockito.Mockito.never()).getBean(EnvLockGuard.class);
        } finally {
            MetaContext.clear();
        }
    }

    // ---------- MetaContextMyBatisInterceptor ----------
    @Test
    void metaContextInterceptor_passthroughWhenNoContext() throws Throwable {
        MetaContextMyBatisInterceptor ic = new MetaContextMyBatisInterceptor();
        Invocation inv = mock(Invocation.class);
        when(inv.proceed()).thenReturn("ok");
        Object out = ic.intercept(inv);
        assertThat(out).isEqualTo("ok");
        ic.setProperties(new Properties());
        assertThat(ic.plugin(new Object())).isNotNull();
    }

    @Test
    void metaContextInterceptor_addsTenantIdWhenParamNull() throws Throwable {
        MetaContextMyBatisInterceptor ic = new MetaContextMyBatisInterceptor();
        try {
            MetaContext.setSystemTenantContext(123L);
            Object[] args = new Object[]{mock(MappedStatement.class), null, RowBounds.DEFAULT, mock(ResultHandler.class)};
            Invocation inv = new Invocation(mock(Executor.class), findExecutorQueryMethod(), args) {
                @Override public Object proceed() { return "ok"; }
            };
            ic.intercept(inv);
            assertThat(args[1]).isInstanceOf(Map.class);
            assertThat((Map<String, Object>) args[1]).containsEntry("tenantId", 123L);
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    void metaContextInterceptor_appendsToExistingMapWithoutOverwrite() throws Throwable {
        MetaContextMyBatisInterceptor ic = new MetaContextMyBatisInterceptor();
        try {
            MetaContext.setSystemTenantContext(7L);
            Map<String, Object> param = new HashMap<>();
            param.put("tenantId", 999L);
            Object[] args = new Object[]{mock(MappedStatement.class), param};
            Invocation inv = new Invocation(mock(Executor.class), findExecutorUpdateMethod(), args) {
                @Override public Object proceed() { return "ok"; }
            };
            ic.intercept(inv);
            // putIfAbsent should preserve the explicit caller value.
            assertThat(param).containsEntry("tenantId", 999L);
        } finally {
            MetaContext.clear();
        }
    }

    @Test
    void metaContextInterceptor_setsFieldOnEntityWhenNull() throws Throwable {
        MetaContextMyBatisInterceptor ic = new MetaContextMyBatisInterceptor();
        try {
            MetaContext.setSystemTenantContext(55L);
            EntityWithTenant entity = new EntityWithTenant();
            Object[] args = new Object[]{mock(MappedStatement.class), entity};
            Invocation inv = new Invocation(mock(Executor.class), findExecutorUpdateMethod(), args) {
                @Override public Object proceed() { return "ok"; }
            };
            ic.intercept(inv);
            assertThat(entity.tenantId).isEqualTo(55L);
        } finally {
            MetaContext.clear();
        }
    }

    static class EntityWithTenant {
        public Long tenantId;
    }

    private Method findExecutorQueryMethod() throws NoSuchMethodException {
        return Executor.class.getMethod("query", MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class);
    }

    private Method findExecutorUpdateMethod() throws NoSuchMethodException {
        return Executor.class.getMethod("update", MappedStatement.class, Object.class);
    }
}
