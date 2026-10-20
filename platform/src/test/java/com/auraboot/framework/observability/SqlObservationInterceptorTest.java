package com.auraboot.framework.observability;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SqlObservationInterceptorTest {
    @Test
    void mapperExecutionIsAChildOfTheRequestAndDoesNotRecordSqlOrParameters() throws Throwable {
        ObservationRegistry registry = ObservationRegistry.create();
        List<Observation.Context> stopped = new ArrayList<>();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override public boolean supportsContext(Observation.Context context) { return true; }
            @Override public void onStop(Observation.Context context) { stopped.add(context); }
        });
        Executor executor = mock(Executor.class);
        MappedStatement statement = new MappedStatement.Builder(new Configuration(), "perf.list",
                parameter -> null, SqlCommandType.SELECT).build();
        var request = Observation.start("http.request", registry);
        try (var ignored = request.openScope()) {
            when(executor.query(eq(statement), any(), any(), any())).thenAnswer(call -> {
                assertThat(registry.getCurrentObservation()).isNotSameAs(request);
                return List.of("row");
            });
            var invocation = new Invocation(executor, Executor.class.getMethod("query", MappedStatement.class,
                    Object.class, RowBounds.class, ResultHandler.class),
                    new Object[] {statement, "secret-value", RowBounds.DEFAULT, null});
            assertThat(new SqlObservationInterceptor(registry).intercept(invocation)).isEqualTo(List.of("row"));
            assertThat(registry.getCurrentObservation()).isSameAs(request);
            assertThat(stopped).hasSize(1);
            var child = stopped.getFirst();
            assertThat(child.getParentObservation()).isSameAs(request);
            assertThat(child.getAllKeyValues().toString()).contains("perf.list", "SELECT").doesNotContain("secret-value");
            assertThat(child.getError()).isNull();
        } finally { request.stop(); }
        assertThat(registry.getCurrentObservation()).isNull();
    }

    @Test
    void failedExecutionClosesItsScopeAndRecordsTheFailure() throws Throwable {
        var registry = ObservationRegistry.create();
        List<Observation.Context> stopped = new ArrayList<>();
        registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
            @Override public boolean supportsContext(Observation.Context context) { return true; }
            @Override public void onStop(Observation.Context context) { stopped.add(context); }
        });
        Executor executor = mock(Executor.class);
        var statement = new MappedStatement.Builder(new Configuration(), "perf.update", p -> null, SqlCommandType.UPDATE).build();
        when(executor.update(eq(statement), any())).thenThrow(new IllegalStateException("write failed"));
        var invocation = new Invocation(executor, Executor.class.getMethod("update", MappedStatement.class, Object.class),
                new Object[] {statement, "private"});
        assertThatThrownBy(() -> new SqlObservationInterceptor(registry).intercept(invocation)).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(stopped).hasSize(1);
        assertThat(stopped.getFirst().getError()).isNotNull();
        assertThat(registry.getCurrentObservation()).isNull();
    }
}
