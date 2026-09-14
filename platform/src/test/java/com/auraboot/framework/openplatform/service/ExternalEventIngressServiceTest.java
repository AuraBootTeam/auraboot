package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.security.ExternalApiKeyAuthenticator.ExternalApiKeyPrincipal;
import com.auraboot.framework.openplatform.entity.ApplicationInstallation;
import com.auraboot.framework.openplatform.mapper.ApplicationInstallationMapper;
import com.auraboot.framework.openplatform.mapper.OpenApiIdempotencyMapper;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.plugin.extension.integration.ReliableIntegrationAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalEventIngressServiceTest {
    private final ApplicationInstallationMapper installationMapper = mock(ApplicationInstallationMapper.class);
    private final OpenApiIdempotencyMapper idempotencyMapper = mock(OpenApiIdempotencyMapper.class);
    private final ReliableIntegrationAccessor accessor = mock(ReliableIntegrationAccessor.class);
    private final OpenPlatformSecretCodec codec = new OpenPlatformSecretCodec();
    private ExternalEventIngressService service;
    private final ExternalApiKeyPrincipal principal = new ExternalApiKeyPrincipal(
            42L, "tok", "app", Set.of("automation.events.write"),
            "app", "inst", "production", "tok");

    @BeforeEach
    void setUp() {
        service = new ExternalEventIngressService(installationMapper, idempotencyMapper,
                accessor, codec, new ObjectMapper());
        ApplicationInstallation installation = new ApplicationInstallation();
        installation.setId(9L);
        installation.setTenantId(42L);
        installation.setPid("inst");
        installation.setStatus("active");
        when(installationMapper.findByTenantAndPid(42L, "inst")).thenReturn(installation);
    }

    @Test
    void claimsIdempotencyAndEnqueuesTenantBoundReliableEnvelope() {
        when(idempotencyMapper.claim(eq(42L), eq(9L), eq("external-event:erp"),
                eq("idempotency-0001"), anyString(), any())).thenReturn(1);

        var result = service.accept(principal, "erp", "idempotency-0001", body("evt-1"));

        assertFalse(result.duplicate());
        ArgumentCaptor<IntegrationEventEnvelope> envelope = ArgumentCaptor.forClass(IntegrationEventEnvelope.class);
        verify(accessor).enqueue(envelope.capture());
        assertEquals(42L, envelope.getValue().tenantId());
        assertEquals("external.erp.order.created.v1", envelope.getValue().eventType());
        assertEquals("ext:inst:evt-1", envelope.getValue().eventId());
        assertEquals("inst", envelope.getValue().headers().get("installationPid"));
    }

    @Test
    void exactDuplicateReturnsAcceptedWithoutSecondEnqueue() {
        String body = body("evt-1");
        when(idempotencyMapper.claim(eq(42L), eq(9L), eq("external-event:erp"),
                eq("idempotency-0001"), anyString(), any())).thenReturn(0);
        when(idempotencyMapper.findRequestHash(42L, 9L, "external-event:erp", "idempotency-0001"))
                .thenReturn(codec.sha256(body));

        var result = service.accept(principal, "erp", "idempotency-0001", body);

        assertTrue(result.duplicate());
        verify(accessor, never()).enqueue(any());
    }

    @Test
    void sameKeyWithDifferentBodyIsConflict() {
        when(idempotencyMapper.claim(eq(42L), eq(9L), eq("external-event:erp"),
                eq("idempotency-0001"), anyString(), any())).thenReturn(0);
        when(idempotencyMapper.findRequestHash(42L, 9L, "external-event:erp", "idempotency-0001"))
                .thenReturn(codec.sha256(body("other")));

        assertThrows(ExternalEventIngressService.IdempotencyConflictException.class,
                () -> service.accept(principal, "erp", "idempotency-0001", body("evt-1")));
    }

    private String body(String id) {
        return """
                {"id":"%s","type":"order.created","schemaVersion":1,
                 "occurredAt":"2026-09-14T08:00:00Z",
                 "subject":{"type":"order","pid":"ord_1"},"data":{"amount":10}}
                """.formatted(id);
    }
}
