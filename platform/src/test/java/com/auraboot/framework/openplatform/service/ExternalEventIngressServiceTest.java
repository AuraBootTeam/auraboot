package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.openplatform.entity.ApplicationInstallation;
import com.auraboot.framework.openplatform.mapper.ApplicationInstallationMapper;
import com.auraboot.framework.openplatform.mapper.OpenApiIdempotencyMapper;
import com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope;
import com.auraboot.framework.plugin.extension.integration.ReliableIntegrationAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.MDC;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
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
    private final MachinePrincipal principal = new MachinePrincipal(
            42L, "tok", "app", Set.of("automation.events.write"),
            "app", "inst", "production", "tok");

    @BeforeEach
    void setUp() {
        service = new ExternalEventIngressService(installationMapper, idempotencyMapper,
                accessor, codec, new ObjectMapper(), new OpenApiProtocolHash(new ObjectMapper(), codec));
        ApplicationInstallation installation = new ApplicationInstallation();
        installation.setId(9L);
        installation.setTenantId(42L);
        installation.setPid("inst");
        installation.setStatus("active");
        when(installationMapper.findByTenantAndPid(42L, "inst")).thenReturn(installation);
    }

    @AfterEach
    void clearTrace() { MDC.remove("requestId"); }

    @Test
    void claimsIdempotencyAndEnqueuesTenantBoundReliableEnvelope() {
        assertEquals("invalid_source_code", assertThrows(ExternalEventIngressService.InvalidExternalEventException.class,
                () -> service.accept(principal, "ERP", "idempotency-0001", body("evt-1"))).getMessage());
        assertEquals("invalid_idempotency_key", assertThrows(ExternalEventIngressService.InvalidExternalEventException.class,
                () -> service.accept(principal, "erp", null, body("evt-1"))).getMessage());
        var invalidBodies = java.util.Map.of(
                "invalid_event_body", "not-json",
                "invalid_id", body(" "),
                "invalid_event_type", body("evt-1").replace("order.created", "!"),
                "invalid_schema_version", body("evt-1").replace("schemaVersion\":1", "schemaVersion\":0"),
                "invalid_occurred_at", body("evt-1").replace("2026-09-14T08:00:00Z", "yesterday"),
                "invalid_pid", body("evt-1").replace("ord_1", ""),
                "invalid_sequence", body("evt-1").replace("\"data\":", "\"sequence\":-1,\"data\":"),
                "invalid_data", body("evt-1").replace("{\"amount\":10}", "[]"));
        invalidBodies.forEach((code, value) -> assertEquals(code,
                assertThrows(ExternalEventIngressService.InvalidExternalEventException.class,
                        () -> service.accept(principal, "erp", "idempotency-0001", value)).getMessage()));
        var installation = installationMapper.findByTenantAndPid(42L, "inst");
        installation.setStatus("disabled");
        assertEquals("installation_not_active", assertThrows(ExternalEventIngressService.InvalidExternalEventException.class,
                () -> service.accept(principal, "erp", "idempotency-0001", body("evt-1"))).getMessage());
        installation.setStatus("active");
        org.mockito.Mockito.verifyNoInteractions(idempotencyMapper, accessor);
        when(idempotencyMapper.claim(eq(42L), eq(9L), eq("external-event:erp"),
                eq("idempotency-0001"), anyString(), any())).thenReturn(1);

        MDC.put("requestId", "fresh-ingress-request");
        var result = service.accept(principal, "erp", "idempotency-0001", body("evt-1"));

        assertFalse(result.duplicate());
        ArgumentCaptor<IntegrationEventEnvelope> envelope = ArgumentCaptor.forClass(IntegrationEventEnvelope.class);
        verify(accessor).enqueue(envelope.capture());
        assertEquals(42L, envelope.getValue().tenantId());
        assertEquals("external.erp.order.created.v1", envelope.getValue().eventType());
        assertEquals("ext:inst:evt-1", envelope.getValue().eventId());
        assertEquals("inst", envelope.getValue().headers().get("installationPid"));
        assertEquals("fresh-ingress-request", envelope.getValue().correlationId());
        assertEquals("fresh-ingress-request", envelope.getValue().headers().get("requestId"));
        assertEquals(java.util.Map.of("amount", 10), envelope.getValue().payload());
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

    @Test
    void registeredNativeAcceptancePrecedesEnqueueAndReturnsItsRecord() {
        var installed=mock(InstalledIntegrationService.class);service.setInstalledIntegrations(installed);
        when(installed.registered("erp")).thenReturn(true);
        when(installed.accept(eq(principal),eq("erp"),any())).thenReturn(new InstalledIntegrationService.Acceptance("request-pid",false));
        when(idempotencyMapper.claim(eq(42L),eq(9L),anyString(),anyString(),anyString(),any())).thenReturn(1);
        when(idempotencyMapper.complete(anyLong(),anyLong(),anyString(),anyString(),anyString(),anyString())).thenReturn(1);
        var response=service.accept(principal,"erp","idempotency-0001",strictBody());
        assertEquals("request-pid",response.recordPid());
        var sequence=org.mockito.Mockito.inOrder(installed,accessor,idempotencyMapper);
        sequence.verify(installed).accept(eq(principal),eq("erp"),any());
        var envelope=ArgumentCaptor.forClass(IntegrationEventEnvelope.class);
        sequence.verify(accessor).enqueue(envelope.capture());
        assertEquals("order",envelope.getValue().headers().get("externalSubjectType"));
        assertEquals("ord_1",envelope.getValue().headers().get("externalSubjectPid"));
        sequence.verify(idempotencyMapper).complete(anyLong(),anyLong(),anyString(),anyString(),anyString(),anyString());
    }

    @Test
    void rejectedInstalledAcceptanceCannotEnqueueAndPermanentReplayDoesNotEnqueueAgain() {
        var installed=mock(InstalledIntegrationService.class);service.setInstalledIntegrations(installed);
        when(installed.registered("erp")).thenReturn(true);
        when(idempotencyMapper.claim(eq(42L),eq(9L),anyString(),anyString(),anyString(),any())).thenReturn(1);
        when(installed.accept(eq(principal),eq("erp"),any())).thenThrow(new SecurityException("denied"));
        assertThrows(SecurityException.class,()->service.accept(principal,"erp","idempotency-0001",strictBody()));
        verify(accessor,never()).enqueue(any());
        when(installed.accept(eq(principal),eq("erp"),any())).thenReturn(new InstalledIntegrationService.Acceptance("original-pid",true));
        when(idempotencyMapper.complete(anyLong(),anyLong(),anyString(),anyString(),anyString(),anyString())).thenReturn(1);
        var response=service.accept(principal,"erp","idempotency-0001",strictBody());
        assertTrue(response.duplicate());assertEquals("original-pid",response.recordPid());verify(accessor,never()).enqueue(any());
    }


    private InstalledIntegrationService businessProvider() {
        var installed=mock(InstalledIntegrationService.class);service.setInstalledIntegrations(installed);
        when(installed.registered("erp")).thenReturn(true);
        when(installed.prepareBusinessRequest(eq("erp"),any())).thenAnswer(call->{
            @SuppressWarnings("unchecked") var input=(java.util.Map<String,Object>)call.getArgument(1);
            return java.util.Optional.of(new com.auraboot.framework.plugin.extension.integration.ExternalEventIngressExtension.BusinessRequest(
                "external.erp.order.snapshot.v2","purchase-order","300001",java.time.Instant.parse(input.get("occurredAt").toString()),
                java.util.Map.of("snapshot",input)));
        });
        when(installed.accept(eq(principal),eq("erp"),any())).thenReturn(new InstalledIntegrationService.Acceptance("accepted-pid",false));
        when(idempotencyMapper.claim(anyLong(),anyLong(),anyString(),anyString(),anyString(),any())).thenReturn(1);
        when(idempotencyMapper.complete(anyLong(),anyLong(),anyString(),anyString(),anyString(),anyString())).thenReturn(1);
        return installed;
    }
    private String businessBody(){return "{\"occurredAt\":\"2026-11-10T00:00:00Z\",\"data\":{\"order\":{\"orderId\":\"300001\"}}}";}
    @Test void businessProviderNormalizesWithoutExternalEventIdentityOrIdempotencyHeader() {
        businessProvider();var result=service.accept(principal,"erp",null,businessBody());
        assertEquals(java.util.Map.of("recordPid","accepted-pid"),result.publicReceipt());
        var envelope=ArgumentCaptor.forClass(IntegrationEventEnvelope.class);verify(accessor).enqueue(envelope.capture());
        assertEquals("external.erp.order.snapshot.v2",envelope.getValue().eventType());
        assertEquals(0,envelope.getValue().sequence());
        assertEquals("purchase-order/300001",envelope.getValue().subject());
        assertEquals(new ObjectMapper().convertValue(readBusiness(),java.util.Map.class),envelope.getValue().payload().get("snapshot"));
    }
    private com.fasterxml.jackson.databind.JsonNode readBusiness() {
        try{return new ObjectMapper().readTree(businessBody());}catch(com.fasterxml.jackson.core.JsonProcessingException invalid){throw new AssertionError(invalid);}
    }
    @Test void optionalBusinessIdIsEchoedButBusinessDuplicateDecisionIsNotExposed() {
        var installed=businessProvider();
        when(installed.accept(eq(principal),eq("erp"),any())).thenReturn(new InstalledIntegrationService.Acceptance("accepted-pid",true));
        var result=service.accept(principal,"erp",null,businessBody().replace("{\"occurredAt\"","{\"id\":\"kd-1\",\"occurredAt\""));
        assertEquals(java.util.Map.of("recordPid","accepted-pid","eventId","kd-1"),result.publicReceipt());
        verify(accessor,never()).enqueue(any());
    }
    @Test void businessClaimsIgnoreJsonFormattingAndObjectKeyOrder() throws Exception {
        businessProvider();
        String original=businessBody().replace("{\"occurredAt\"","{\"id\":\"kd-1\",\"occurredAt\"");
        String reordered="{\"data\":{\"order\":{\"orderId\":\"300001\"}},\"id\":\"kd-1\",\"occurredAt\":\"2026-11-10T00:00:00Z\"}";
        service.accept(principal,"erp",null,original);
        service.accept(principal,"erp",null,new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(new ObjectMapper().readTree(reordered)));
        var hashes=ArgumentCaptor.forClass(String.class);
        verify(idempotencyMapper,org.mockito.Mockito.times(2)).claim(eq(42L),eq(9L),eq("external-event:erp"),eq("business:kd-1"),hashes.capture(),any());
        assertEquals(hashes.getAllValues().get(0),hashes.getAllValues().get(1));
        // Routing/orchestration assertion only; durable replay is tested through installed HTTP/PostgreSQL.
    }
    @Test void legacyHashMismatchRequiresExistingEventBoundReceiptAndNeverCreatesWork() {
        var installed=businessProvider();
        String body=businessBody().replace("{\"occurredAt\"","{\"id\":\"kd-1\",\"occurredAt\"");
        when(idempotencyMapper.claim(anyLong(),anyLong(),anyString(),anyString(),anyString(),any())).thenReturn(0);
        when(idempotencyMapper.findRequestHash(42L,9L,"external-event:erp","business:kd-1")).thenReturn(codec.sha256(body));
        when(idempotencyMapper.findResponse(42L,9L,"external-event:erp","business:kd-1")).thenReturn(
                new OpenApiIdempotencyMapper.StoredResponse(codec.sha256(body),"completed",202,"{\"eventId\":\"kd-1\",\"accepted\":true}",null,null,null,null));
        when(installed.verifyAcceptedReplay(eq(principal),eq("erp"),any())).thenReturn(new InstalledIntegrationService.Acceptance("original-pid",true));
        assertEquals(java.util.Map.of("eventId","kd-1","recordPid","original-pid"),service.accept(principal,"erp",null,body).publicReceipt());
        verify(installed).verifyAcceptedReplay(eq(principal),eq("erp"),any());
        verify(installed,never()).accept(any(),anyString(),any());
        verify(accessor,never()).enqueue(any());
        verify(idempotencyMapper,never()).complete(anyLong(),anyLong(),anyString(),anyString(),anyString(),anyString());
        when(idempotencyMapper.findResponse(42L,9L,"external-event:erp","business:kd-1")).thenReturn(
                new OpenApiIdempotencyMapper.StoredResponse(codec.sha256(body),"completed",202,"{\"eventId\":\"other-event\",\"accepted\":true}",null,null,null,null));
        assertThrows(ExternalEventIngressService.IdempotencyConflictException.class,()->service.accept(principal,"erp",null,body));
        when(idempotencyMapper.findResponse(42L,9L,"external-event:erp","business:kd-1")).thenReturn(null);
        assertThrows(ExternalEventIngressService.IdempotencyConflictException.class,()->service.accept(principal,"erp",null,body));
    }
    @Test void businessBodiesRejectRoutingFieldsNonUtcDuplicateKeysAndTrailingTokens() {
        businessProvider();
        for(String body:java.util.List.of(businessBody().replace("{\"occurredAt\"","{\"sequence\":1,\"occurredAt\""),
                businessBody().replace("2026-11-10T00:00:00Z","2026-11-10T08:00:00+08:00"),
                businessBody()+" {}",businessBody().replace("\"orderId\":\"300001\"","\"orderId\":\"300002\",\"orderId\":\"300001\"")))
            assertThrows(ExternalEventIngressService.InvalidExternalEventException.class,()->service.accept(principal,"erp",null,body));
        verifyNoClaim();verify(accessor,never()).enqueue(any());
    }
    @Test void simplifiedInputRequiresExplicitProviderSupportAndLegacyStillRequiresItsHeader() {
        var installed=businessProvider();
        when(installed.prepareBusinessRequest(eq("erp"),any())).thenReturn(java.util.Optional.empty());
        assertEquals("business_contract_not_registered",assertThrows(ExternalEventIngressService.InvalidExternalEventException.class,
            ()->service.accept(principal,"erp",null,businessBody())).getMessage());
        assertEquals("invalid_idempotency_key",assertThrows(ExternalEventIngressService.InvalidExternalEventException.class,
            ()->service.accept(principal,"erp",null,strictBody())).getMessage());
        verifyNoClaim();verify(accessor,never()).enqueue(any());
    }
    @Test void missingNativeAcceptanceStopsBeforeEnqueueAndCompletion() {
        var installed=businessProvider();when(installed.accept(eq(principal),eq("erp"),any())).thenReturn(null);
        assertThrows(IllegalStateException.class,()->service.accept(principal,"erp",null,businessBody()));
        verify(accessor,never()).enqueue(any());
        verify(idempotencyMapper,never()).complete(anyLong(),anyLong(),anyString(),anyString(),anyString(),anyString());
    }
    @Test void missingBusinessAcceptanceRecordCannotBecomeAPublicSuccess() {
        assertThrows(IllegalStateException.class,()->new ExternalEventIngressService.IngressResult("id",false,null,true,false).publicReceipt());
        assertEquals(java.util.Map.of("eventId","id","duplicate",false),new ExternalEventIngressService.IngressResult("id",false).publicReceipt());
    }

    private String strictBody() { return body("evt-1").replace("\"data\":", "\"sequence\":0,\"data\":"); }

    @Test
    void installedEnvelopeRejectsCoercionUnknownFieldsAndTrailingJsonBeforeClaim() throws Exception {
        var installed=mock(InstalledIntegrationService.class);service.setInstalledIntegrations(installed);
        when(installed.registered("erp")).thenReturn(true);
        var json=new ObjectMapper();
        for (String field : java.util.List.of("id","type","occurredAt")) {
            var node=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(strictBody());node.put(field,1);
            assertThrows(ExternalEventIngressService.InvalidExternalEventException.class,()->service.accept(principal,"erp","idempotency-0001",node.toString()));
        }
        for (String field : java.util.List.of("schemaVersion","sequence")) {
            var node=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(strictBody());node.put(field,"1");
            assertThrows(ExternalEventIngressService.InvalidExternalEventException.class,()->service.accept(principal,"erp","idempotency-0001",node.toString()));
            node.put(field,true);
            assertThrows(ExternalEventIngressService.InvalidExternalEventException.class,()->service.accept(principal,"erp","idempotency-0001",node.toString()));
        }
        var unknown=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(strictBody());unknown.put("tenantId",42);
        assertThrows(ExternalEventIngressService.InvalidExternalEventException.class,()->service.accept(principal,"erp","idempotency-0001",unknown.toString()));
        unknown.remove("tenantId");((com.fasterxml.jackson.databind.node.ObjectNode)unknown.get("subject")).put("extra",true);
        assertThrows(ExternalEventIngressService.InvalidExternalEventException.class,()->service.accept(principal,"erp","idempotency-0001",unknown.toString()));
        assertThrows(ExternalEventIngressService.InvalidExternalEventException.class,()->service.accept(principal,"erp","idempotency-0001",strictBody()+" {}"));
        assertThrows(ExternalEventIngressService.InvalidExternalEventException.class,()->service.accept(principal,"erp","idempotency-0001",
                strictBody().replace("\"id\":\"evt-1\"","\"id\":\"other\",\"id\":\"evt-1\"")));
        verifyNoClaim();verify(accessor,never()).enqueue(any());
    }

    private void verifyNoClaim() { verify(idempotencyMapper,never()).claim(anyLong(),anyLong(),anyString(),anyString(),anyString(),any()); }

    private String body(String id) {
        return """
                {"id":"%s","type":"order.created","schemaVersion":1,
                 "occurredAt":"2026-09-14T08:00:00Z",
                 "subject":{"type":"order","pid":"ord_1"},"data":{"amount":10}}
                """.formatted(id);
    }
}
