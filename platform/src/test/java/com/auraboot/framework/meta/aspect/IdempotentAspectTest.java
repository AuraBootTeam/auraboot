package com.auraboot.framework.meta.aspect;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.annotation.Idempotent;
import com.auraboot.framework.meta.entity.IdempotentKey;
import com.auraboot.framework.meta.exception.IdempotentException;
import com.auraboot.framework.meta.mapper.IdempotentKeyMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotentAspectTest {

    @Mock private IdempotentKeyMapper mapper;
    @Mock private ProceedingJoinPoint joinPoint;
    @Mock private MethodSignature signature;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private IdempotentAspect aspect;

    // Sample target method to resolve via MethodSignature
    static class Sample {
        public String op(String commandCode, String payload) { return "result"; }
    }

    @BeforeEach
    void setUp() throws Exception {
        aspect = new IdempotentAspect(mapper, objectMapper);
        MetaContext.setContext(7L, 99L, "u-1", "alice");
    }

    @AfterEach
    void clear() {
        MetaContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    private Method sampleMethod() throws NoSuchMethodException {
        return Sample.class.getMethod("op", String.class, String.class);
    }

    private void wireSignature(Object[] args) throws NoSuchMethodException {
        Method m = sampleMethod();
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(m);
        when(joinPoint.getArgs()).thenReturn(args);
        lenient().when(signature.getReturnType()).thenAnswer(inv -> m.getReturnType());
    }

    private Idempotent ann(String keyExpr, boolean includeBodyHash) {
        return new Idempotent() {
            @Override public Class<? extends java.lang.annotation.Annotation> annotationType() { return Idempotent.class; }
            @Override public long ttl() { return 60; }
            @Override public String keyExpression() { return keyExpr; }
            @Override public boolean includeBodyHash() { return includeBodyHash; }
            @Override public String message() { return "Duplicate request detected"; }
        };
    }

    // ===== No key resolved -> just proceed =====
    @Test
    void no_key_no_body_hash_just_proceeds() throws Throwable {
        wireSignature(new Object[]{"cmd1", "payload"});
        when(joinPoint.proceed()).thenReturn("ok");

        Object r = aspect.aroundIdempotent(joinPoint, ann("", false));

        assertThat(r).isEqualTo("ok");
        verify(mapper, never()).insertIfAbsent(any());
    }

    // ===== Header-based key, INSERT succeeds, method succeeds =====
    @Test
    void header_key_insert_success_runs_method_and_records_completed() throws Throwable {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Idempotent-Key", "abc-123");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        wireSignature(new Object[]{"cmd1", "payload"});
        when(joinPoint.proceed()).thenReturn("result");
        when(mapper.insertIfAbsent(any(IdempotentKey.class))).thenReturn(1);

        Object r = aspect.aroundIdempotent(joinPoint, ann("", false));

        assertThat(r).isEqualTo("result");
        ArgumentCaptor<IdempotentKey> kc = ArgumentCaptor.forClass(IdempotentKey.class);
        verify(mapper).insertIfAbsent(kc.capture());
        IdempotentKey claim = kc.getValue();
        assertThat(claim.getIdempotentKey()).isEqualTo("abc-123");
        assertThat(claim.getStatus()).isEqualTo(IdempotentKey.STATUS_PROCESSING);
        assertThat(claim.getTenantId()).isEqualTo(7L);
        assertThat(claim.getCommandCode()).isEqualTo("cmd1");
        verify(mapper).updateStatusAndResponse(eq("abc-123"), eq(7L), eq(IdempotentKey.STATUS_COMPLETED), anyString());
    }

    // ===== INSERT fails on duplicate, existing COMPLETED -> replay =====
    @Test
    void duplicate_completed_returns_cached_response_without_proceeding() throws Throwable {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Idempotent-Key", "k1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        wireSignature(new Object[]{"cmd1", "p"});
        when(mapper.insertIfAbsent(any(IdempotentKey.class))).thenReturn(0);
        IdempotentKey existing = new IdempotentKey();
        existing.setStatus(IdempotentKey.STATUS_COMPLETED);
        existing.setResponseData("\"cached-result\"");
        when(mapper.findByKey(eq("k1"), eq(7L))).thenReturn(existing);

        Object r = aspect.aroundIdempotent(joinPoint, ann("", false));

        assertThat(r).isEqualTo("cached-result");
        verify(joinPoint, never()).proceed();
    }

    // ===== INSERT fails, existing PROCESSING -> reject =====
    @Test
    void duplicate_processing_throws_idempotent_exception() throws Throwable {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Idempotent-Key", "k2");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        wireSignature(new Object[]{"cmd1", "p"});
        when(mapper.insertIfAbsent(any(IdempotentKey.class))).thenReturn(0);
        IdempotentKey existing = new IdempotentKey();
        existing.setStatus(IdempotentKey.STATUS_PROCESSING);
        when(mapper.findByKey(eq("k2"), eq(7L))).thenReturn(existing);

        assertThatThrownBy(() -> aspect.aroundIdempotent(joinPoint, ann("", false)))
            .isInstanceOf(IdempotentException.class);
    }

    // ===== INSERT fails, existing EXPIRED -> proceed =====
    @Test
    void duplicate_expired_proceeds() throws Throwable {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Idempotent-Key", "k3");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        wireSignature(new Object[]{"cmd1", "p"});
        when(mapper.insertIfAbsent(any(IdempotentKey.class))).thenReturn(0);
        IdempotentKey existing = new IdempotentKey();
        existing.setStatus(IdempotentKey.STATUS_EXPIRED);
        when(mapper.findByKey(eq("k3"), eq(7L))).thenReturn(existing);
        when(joinPoint.proceed()).thenReturn("retry-ok");

        assertThat(aspect.aroundIdempotent(joinPoint, ann("", false))).isEqualTo("retry-ok");
    }

    // ===== INSERT fails, existing record vanished (race) -> proceed =====
    @Test
    void duplicate_findByKey_returns_null_proceeds() throws Throwable {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Idempotent-Key", "k4");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        wireSignature(new Object[]{"cmd1", "p"});
        when(mapper.insertIfAbsent(any(IdempotentKey.class))).thenReturn(0);
        when(mapper.findByKey(eq("k4"), eq(7L))).thenReturn(null);
        when(joinPoint.proceed()).thenReturn("race-result");

        assertThat(aspect.aroundIdempotent(joinPoint, ann("", false))).isEqualTo("race-result");
    }

    // ===== Method throws -> mark expired and rethrow =====
    @Test
    void method_failure_marks_key_expired() throws Throwable {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Idempotent-Key", "kf");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        wireSignature(new Object[]{"cmd1", "p"});
        when(mapper.insertIfAbsent(any(IdempotentKey.class))).thenReturn(1);
        RuntimeException boom = new RuntimeException("boom");
        when(joinPoint.proceed()).thenThrow(boom);

        assertThatThrownBy(() -> aspect.aroundIdempotent(joinPoint, ann("", false))).isSameAs(boom);
        verify(mapper).markExpired(eq("kf"), eq(7L));
        verify(mapper, never()).updateStatusAndResponse(anyString(), anyLong(), anyString(), anyString());
    }

    // ===== SpEL expression key resolution =====
    @Test
    void spel_expression_resolves_key_from_args() throws Throwable {
        wireSignature(new Object[]{"cmd1", "spelkey"});
        when(mapper.insertIfAbsent(any(IdempotentKey.class))).thenReturn(1);
        when(joinPoint.proceed()).thenReturn("v");

        aspect.aroundIdempotent(joinPoint, ann("#p1", false));

        ArgumentCaptor<IdempotentKey> cap = ArgumentCaptor.forClass(IdempotentKey.class);
        verify(mapper).insertIfAbsent(cap.capture());
        assertThat(cap.getValue().getIdempotentKey()).isEqualTo("spelkey");
    }

    @Test
    void spel_expression_failure_falls_through_to_no_key() throws Throwable {
        wireSignature(new Object[]{"cmd1", "p"});
        when(joinPoint.proceed()).thenReturn("v");

        // Bad SpEL — evaluation throws and resolveKey returns null; with no header & no body hash, proceed-only path.
        Object r = aspect.aroundIdempotent(joinPoint, ann("T(java.lang.IllegalStateException).bogus()", false));

        assertThat(r).isEqualTo("v");
        verify(mapper, never()).insertIfAbsent(any());
    }

    // ===== Body hash fallback =====
    @Test
    void body_hash_fallback_when_no_other_key() throws Throwable {
        wireSignature(new Object[]{"cmd1", "payload-content"});
        when(mapper.insertIfAbsent(any(IdempotentKey.class))).thenReturn(1);
        when(joinPoint.proceed()).thenReturn("v");

        aspect.aroundIdempotent(joinPoint, ann("", true));

        ArgumentCaptor<IdempotentKey> cap = ArgumentCaptor.forClass(IdempotentKey.class);
        verify(mapper).insertIfAbsent(cap.capture());
        // The composite key for body-hash fallback is hash + ":" + hash (since key == bodyHash)
        IdempotentKey k = cap.getValue();
        assertThat(k.getIdempotentKey()).contains(":");
        assertThat(k.getRequestHash()).isNotNull();
    }

    // ===== Composite key with header + body hash =====
    @Test
    void composite_key_combines_header_and_hash_when_includeBodyHash_true() throws Throwable {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Idempotent-Key", "header-key");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        wireSignature(new Object[]{"cmd1", "payload"});
        when(mapper.insertIfAbsent(any(IdempotentKey.class))).thenReturn(1);
        when(joinPoint.proceed()).thenReturn("v");

        aspect.aroundIdempotent(joinPoint, ann("", true));

        ArgumentCaptor<IdempotentKey> cap = ArgumentCaptor.forClass(IdempotentKey.class);
        verify(mapper).insertIfAbsent(cap.capture());
        IdempotentKey k = cap.getValue();
        assertThat(k.getIdempotentKey()).startsWith("header-key:");
        assertThat(k.getRequestHash()).isNotBlank();
    }
}
