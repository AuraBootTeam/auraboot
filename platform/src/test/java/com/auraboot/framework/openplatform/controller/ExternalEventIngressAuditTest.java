package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.openplatform.security.OpenApiBusinessAudit;
import com.auraboot.framework.openplatform.service.ExternalEventIngressService;
import com.auraboot.framework.openplatform.service.InstalledIntegrationService;
import com.auraboot.framework.openplatform.service.OpenApiCapabilityRegistry.Capability;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalEventIngressAuditTest {
    private static final String SOURCE="kingdee-procurement-orders";
    private static final String PID="01M76YEKYJB7VBH2DGC2K1C0B8";
    private final ExternalEventIngressService ingress=mock(ExternalEventIngressService.class);
    private final InstalledIntegrationService installed=mock(InstalledIntegrationService.class);
    private final ExternalEventIngressController controller=new ExternalEventIngressController(ingress,installed);
    private MockHttpServletRequest request;

    @BeforeEach void request() {
        request=new MockHttpServletRequest("POST","/api/open/v1/event-sources/"+SOURCE+"/events");
        request.addHeader("Authorization","Bearer synthetic-sensitive-token");
        var capability=mock(Capability.class);when(capability.code()).thenReturn("procurement.order.ingress.accept");
        OpenApiBusinessAudit.capture(request,capability);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    @AfterEach void clear() {RequestContextHolder.resetRequestAttributes();}

    @Test void acceptedReceiptLinksToAuditWithoutChangingThePublicBodyOrCapturingInput() {
        String raw="{\"sensitiveBusinessInput\":\"do-not-audit\"}";
        when(ingress.accept(null,SOURCE,null,raw)).thenReturn(new ExternalEventIngressService.IngressResult("original-event",false,PID,true,true));
        var response=controller.accept(null,SOURCE,null,raw);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isEqualTo(Map.of("eventId","original-event","recordPid",PID));
        var audit=OpenApiBusinessAudit.details(request);
        assertThat(audit.path("resource").asText()).isEqualTo(SOURCE);
        assertThat(audit.path("operation").asText()).isEqualTo("accept");
        assertThat(audit.path("recordPid").asText()).isEqualTo(PID);
        assertThat(audit.path("idempotentReplay").booleanValue()).isFalse();
        assertThat(audit.toString()).doesNotContain("do-not-audit","synthetic-sensitive-token","sensitiveBusinessInput");
    }

    @Test void exactReplayIsAuditedAsReplayWithTheOriginalReceipt() {
        when(ingress.accept(null,SOURCE,null,"original")).thenReturn(new ExternalEventIngressService.IngressResult("original-event",true,PID,true,true));
        controller.accept(null,SOURCE,null,"original");
        var audit=OpenApiBusinessAudit.details(request);
        assertThat(audit.path("idempotentReplay").booleanValue()).isTrue();
        assertThat(audit.path("recordPid").asText()).isEqualTo(PID);
    }

    @Test void rejectionKeepsAttemptContextWithoutClaimingARecordOrReplaySuccess() {
        when(ingress.accept(null,SOURCE,null,"conflict")).thenThrow(new ExternalEventIngressService.IdempotencyConflictException());
        assertThatThrownBy(()->controller.accept(null,SOURCE,null,"conflict"))
                .isInstanceOf(ExternalEventIngressService.IdempotencyConflictException.class);
        var audit=OpenApiBusinessAudit.details(request);
        assertThat(audit.path("resource").asText()).isEqualTo(SOURCE);
        assertThat(audit.path("operation").asText()).isEqualTo("accept");
        assertThat(audit.has("recordPid")).isFalse();assertThat(audit.has("idempotentReplay")).isFalse();
        assertThat(controller.conflict().getStatusCode().value()).isEqualTo(409);
    }

    @Test void resultReadLinksTheOriginalRecordWithoutInventingReplayMetadata() {
        var result=Map.<String,Object>of("state","completed","recordPid",PID);
        when(installed.result(null,SOURCE,"original-event")).thenReturn(result);
        assertThat(controller.result(null,SOURCE,"original-event")).isSameAs(result);
        var audit=OpenApiBusinessAudit.details(request);
        assertThat(audit.path("operation").asText()).isEqualTo("result");
        assertThat(audit.path("recordPid").asText()).isEqualTo(PID);
        assertThat(audit.has("idempotentReplay")).isFalse();
    }
}
