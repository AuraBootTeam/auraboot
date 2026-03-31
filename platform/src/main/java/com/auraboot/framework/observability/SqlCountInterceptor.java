package com.auraboot.framework.observability;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.util.Properties;

/**
 * Native MyBatis {@link Interceptor} that increments {@link SqlCountHolder}
 * on every query/update execution.
 *
 * <p>Intercepts the same Executor methods as {@link SlowQueryInterceptor} —
 * both SELECT (query) and INSERT/UPDATE/DELETE (update) statements.
 *
 * <p>Registered as a Spring bean — MyBatis auto-detects any {@link Interceptor}
 * bean in the application context and plugs it into the executor chain.
 */
@Intercepts({
    @Signature(type = Executor.class, method = "query",
        args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
    @Signature(type = Executor.class, method = "update",
        args = {MappedStatement.class, Object.class})
})
public class SqlCountInterceptor implements Interceptor {

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        SqlCountHolder.increment();
        return invocation.proceed();
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        // Configuration is injected via constructor from Spring; not used here.
    }
}
