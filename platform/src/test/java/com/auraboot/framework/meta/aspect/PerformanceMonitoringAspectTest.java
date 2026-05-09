package com.auraboot.framework.meta.aspect;

import com.auraboot.framework.meta.monitor.MetaPerformanceMonitor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PerformanceMonitoringAspectTest {

    @Mock private MetaPerformanceMonitor monitor;
    @Mock private ProceedingJoinPoint joinPoint;
    @Mock private Signature signature;

    private PerformanceMonitoringAspect aspect;

    @BeforeEach
    void setUp() throws Exception {
        aspect = new PerformanceMonitoringAspect();
        // Field is @Autowired private; inject via reflection.
        Field f = PerformanceMonitoringAspect.class.getDeclaredField("performanceMonitor");
        f.setAccessible(true);
        f.set(aspect, monitor);

        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getDeclaringTypeName()).thenReturn("com.example.Foo");
        when(signature.getName()).thenReturn("bar");
    }

    @Test
    void monitorApiPerformance_records_success() throws Throwable {
        when(joinPoint.proceed()).thenReturn("ok");

        Object result = aspect.monitorApiPerformance(joinPoint);

        assertThat(result).isEqualTo("ok");
        ArgumentCaptor<Duration> dur = ArgumentCaptor.forClass(Duration.class);
        verify(monitor).recordApiRequest(eq("com.example.Foo.bar"), dur.capture(), eq(true));
        assertThat(dur.getValue()).isNotNull();
    }

    @Test
    void monitorApiPerformance_records_failure_and_rethrows() throws Throwable {
        RuntimeException boom = new RuntimeException("boom");
        when(joinPoint.proceed()).thenThrow(boom);

        assertThatThrownBy(() -> aspect.monitorApiPerformance(joinPoint))
            .isSameAs(boom);
        verify(monitor).recordApiRequest(eq("com.example.Foo.bar"), any(Duration.class), eq(false));
    }

    @Test
    void monitorPermissionCheck_uses_boolean_result_for_granted() throws Throwable {
        when(joinPoint.proceed()).thenReturn(Boolean.FALSE);
        Object r = aspect.monitorPermissionCheckPerformance(joinPoint);
        assertThat(r).isEqualTo(false);
        verify(monitor).recordPermissionCheck(eq("com.example.Foo.bar"), any(Duration.class), eq(false));
    }

    @Test
    void monitorPermissionCheck_non_boolean_result_treated_as_granted() throws Throwable {
        when(joinPoint.proceed()).thenReturn("anything");
        aspect.monitorPermissionCheckPerformance(joinPoint);
        verify(monitor).recordPermissionCheck(eq("com.example.Foo.bar"), any(Duration.class), eq(true));
    }

    @Test
    void monitorPermissionCheck_failure_marks_not_granted_and_rethrows() throws Throwable {
        RuntimeException boom = new RuntimeException();
        when(joinPoint.proceed()).thenThrow(boom);
        assertThatThrownBy(() -> aspect.monitorPermissionCheckPerformance(joinPoint))
            .isSameAs(boom);
        verify(monitor).recordPermissionCheck(eq("com.example.Foo.bar"), any(Duration.class), eq(false));
    }

    @Test
    void monitorQueryExecution_records_success_with_method_name_only() throws Throwable {
        when(joinPoint.proceed()).thenReturn(42);
        aspect.monitorQueryExecutionPerformance(joinPoint);
        verify(monitor).recordQueryExecution(eq("bar"), any(Duration.class), eq(true));
    }

    @Test
    void monitorQueryExecution_records_failure() throws Throwable {
        RuntimeException boom = new RuntimeException();
        when(joinPoint.proceed()).thenThrow(boom);
        assertThatThrownBy(() -> aspect.monitorQueryExecutionPerformance(joinPoint))
            .isSameAs(boom);
        verify(monitor).recordQueryExecution(eq("bar"), any(Duration.class), eq(false));
        verifyNoMoreInteractions(monitor);
    }
}
