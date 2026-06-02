package com.auraboot.framework.plugin.rest;

import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.service.IdempotencyService;
import com.auraboot.framework.meta.service.impl.CommandEffectExecutor;
import com.auraboot.framework.plugin.extension.PluginHttpRequest;
import com.auraboot.framework.plugin.extension.PluginHttpResponse;
import com.auraboot.framework.plugin.extension.PluginRequestContext;
import com.auraboot.framework.plugin.extension.RestEndpointExtension;
import com.auraboot.framework.plugin.extension.RestRoute;
import com.auraboot.framework.plugin.pf4j.RestEndpointRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RestEndpointPipelineTest {

    private final IdempotencyService idempotency = mock(IdempotencyService.class);
    private final CommandEffectExecutor effectExecutor = mock(CommandEffectExecutor.class);
    private final RestSchemaValidator schemaValidator = new RestSchemaValidator(new ObjectMapper());

    /** A no-op tx manager: unit tests cover orchestration; real rollback is covered by the docker IT. */
    private final PlatformTransactionManager txManager = new PlatformTransactionManager() {
        @Override public TransactionStatus getTransaction(TransactionDefinition d) { return new SimpleTransactionStatus(); }
        @Override public void commit(TransactionStatus status) { }
        @Override public void rollback(TransactionStatus status) { }
    };

    private final RestEndpointPipeline pipeline =
            new RestEndpointPipeline(txManager, idempotency, schemaValidator, effectExecutor);

    private PluginRequestContext ctx() {
        PluginRequestContext c = mock(PluginRequestContext.class);
        when(c.tenantId()).thenReturn(7L);
        when(c.userId()).thenReturn(42L);
        when(c.zoneId()).thenReturn(ZoneId.of("UTC"));
        return c;
    }

    private PluginHttpRequest req(String method, byte[] body, String idemKey) {
        PluginHttpRequest r = mock(PluginHttpRequest.class);
        when(r.method()).thenReturn(method);
        when(r.body()).thenReturn(body == null ? new byte[0] : body);
        when(r.header("Idempotency-Key")).thenReturn(idemKey);
        return r;
    }

    private RestEndpointRegistry.Match match(RestRoute route, RestEndpointExtension ext) {
        return new RestEndpointRegistry.Match(ext, route, Map.of());
    }

    /** Extension that records whether it ran and writes a 201 body. */
    static final class RecordingExt implements RestEndpointExtension {
        boolean ran = false;
        RuntimeException toThrow;
        @Override public String namespace() { return "probe"; }
        @Override public List<RestRoute> routes() { return List.of(); }
        @Override public void handle(PluginHttpRequest req, PluginHttpResponse res, PluginRequestContext ctx) throws Exception {
            ran = true;
            if (toThrow != null) throw toThrow;
            res.status(201).contentType("application/json");
            res.out().write("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void idempotentReplay_returnsCachedResponse_withoutRunningHandler() {
        RecordingExt ext = new RecordingExt();
        RestRoute route = new RestRoute("POST", "/echo", "probe.echo.write", null, true, false, null);
        BufferingPluginHttpResponse prior = new BufferingPluginHttpResponse();
        prior.status(201).contentType("application/json").header("X-Gamma-Probe", "echo");
        prior.bodyBytes("{\"replayed\":true}".getBytes(StandardCharsets.UTF_8));
        when(idempotency.checkIdempotency("key-1", 7L)).thenReturn(prior.toOutcomeMap());

        BufferingPluginHttpResponse out =
                pipeline.execute(match(route, ext), req("POST", "{}".getBytes(), "key-1"), ctx());

        assertThat(ext.ran).as("handler must NOT run on idempotent replay").isFalse();
        assertThat(out.status()).isEqualTo(201);
        assertThat(new String(out.body(), StandardCharsets.UTF_8)).isEqualTo("{\"replayed\":true}");
        verify(idempotency, never()).recordOutcome(anyString(), anyString(), anyMap(), anyMap(), anyLong());
    }

    @Test
    void schemaViolation_throwsBeforeHandlerRuns_andAuditsFailure() {
        RecordingExt ext = new RecordingExt();
        String schema = "{\"type\":\"object\",\"required\":[\"text\"]}";
        RestRoute route = new RestRoute("POST", "/echo", "probe.echo.write", null, false, false, schema);

        assertThatThrownBy(() -> pipeline.execute(match(route, ext), req("POST", "{}".getBytes(), null), ctx()))
                .isInstanceOf(ValidationException.class);

        assertThat(ext.ran).as("handler must NOT run when schema validation fails").isFalse();
        verify(effectExecutor).saveAuditLog(eq(7L), anyString(), any(), eq(42L), anyMap(), any(),
                eq(false), anyString(), anyLong(), anyString(), anyMap());
    }

    @Test
    void success_runsHandler_recordsIdempotency_andAuditsSuccess() {
        RecordingExt ext = new RecordingExt();
        RestRoute route = new RestRoute("POST", "/echo", "probe.echo.write", null, true, false, null);
        when(idempotency.checkIdempotency("key-2", 7L)).thenReturn(null);

        BufferingPluginHttpResponse out =
                pipeline.execute(match(route, ext), req("POST", "{\"text\":\"hi\"}".getBytes(), "key-2"), ctx());

        assertThat(ext.ran).isTrue();
        assertThat(out.status()).isEqualTo(201);
        verify(idempotency).recordOutcome(eq("key-2"), anyString(), anyMap(), anyMap(), eq(7L));
        verify(effectExecutor).saveAuditLog(eq(7L), anyString(), any(), eq(42L), anyMap(), any(),
                eq(true), any(), anyLong(), anyString(), anyMap());
    }

    @Test
    void handlerThrows_auditsFailure_andRethrows() {
        RecordingExt ext = new RecordingExt();
        ext.toThrow = new IllegalStateException("boom");
        RestRoute route = new RestRoute("POST", "/boom", "probe.boom.write", null, false, false, null);

        assertThatThrownBy(() -> pipeline.execute(match(route, ext), req("POST", new byte[0], null), ctx()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("boom");

        assertThat(ext.ran).isTrue();
        verify(effectExecutor).saveAuditLog(eq(7L), anyString(), any(), eq(42L), anyMap(), any(),
                eq(false), anyString(), anyLong(), anyString(), anyMap());
        verify(idempotency, never()).recordOutcome(anyString(), anyString(), anyMap(), anyMap(), anyLong());
    }
}
