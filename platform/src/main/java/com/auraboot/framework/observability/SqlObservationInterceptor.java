package com.auraboot.framework.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/** Links MyBatis execution to the active HTTP/service trace without recording SQL or values. */
@Intercepts({
    @Signature(type = Executor.class, method = "query",
            args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
    @Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class})
})
public final class SqlObservationInterceptor implements Interceptor {
    private final ObservationRegistry registry;

    public SqlObservationInterceptor(ObservationRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        MappedStatement statement = (MappedStatement) invocation.getArgs()[0];
        Observation observation = Observation.createNotStarted("mybatis.execution", registry)
                .contextualName("mybatis " + statement.getSqlCommandType().name())
                .lowCardinalityKeyValue("db.system", "postgresql")
                .lowCardinalityKeyValue("db.operation", statement.getSqlCommandType().name())
                .lowCardinalityKeyValue("mybatis.mapper", statement.getId())
                .start();
        try (Observation.Scope ignored = observation.openScope()) {
            return invocation.proceed();
        } catch (Throwable failure) {
            // Driver exceptions may embed SQL and parameter values. Preserve the original
            // exception for the caller, but export only a fixed diagnostic message.
            observation.error(new IllegalStateException("MyBatis execution failed"));
            throw failure;
        } finally {
            observation.stop();
        }
    }
}
