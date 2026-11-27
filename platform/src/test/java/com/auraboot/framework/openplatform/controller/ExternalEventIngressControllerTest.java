package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.openplatform.service.ExternalEventIngressService;
import com.auraboot.framework.openplatform.service.InstalledIntegrationService;
import com.auraboot.framework.openplatform.security.OpenApiRequestIdFilter;
import com.auraboot.framework.plugin.extension.integration.ExternalEventIngressExtension.FieldError;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ExternalEventIngressControllerTest {
    private final ExternalEventIngressService service = mock(ExternalEventIngressService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ExternalEventIngressController(service, mock(InstalledIntegrationService.class)))
            .setCustomArgumentResolvers(new org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver())
            .addFilters(new OpenApiRequestIdFilter(new ObjectMapper(), org.mockito.Mockito.mock(com.auraboot.framework.openplatform.mapper.OpenApiCallAuditMapper.class)))
            .setMessageConverters(new org.springframework.http.converter.StringHttpMessageConverter(),
                    new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(new ObjectMapper())).build();
    private static final String PATH = "/api/open/v1/event-sources/kingdee-procurement-orders/events";

    @Test void fieldErrorsAndRequestIdAreReturnedTogether() throws Exception {
        when(service.accept(any(), eq("kingdee-procurement-orders"), isNull(), anyString())).thenThrow(
                new ExternalEventIngressService.InvalidExternalEventException("invalid_business_request", "Correct the listed fields.", List.of(
                        new FieldError("data.orderIds", "required", "A nonempty array is required."),
                        new FieldError("data.order", "unsupported_field", "Send document identities, not business facts."))));
        mvc.perform(post(PATH).header("X-Request-Id", "request-123").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(header().string("X-Request-Id", "request-123"))
                .andExpect(jsonPath("$.code").value("invalid_business_request"))
                .andExpect(jsonPath("$.message").value("Correct the listed fields."))
                .andExpect(jsonPath("$.requestId").value("request-123"))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].field").value("data.orderIds"))
                .andExpect(jsonPath("$.errors[1].code").value("unsupported_field"));
    }
    @Test void conflictRemains409WithActionableExplanation() throws Exception {
        when(service.accept(any(), anyString(), any(), anyString())).thenThrow(new ExternalEventIngressService.IdempotencyConflictException());
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("idempotency_conflict"))
                .andExpect(jsonPath("$.message").isString()).andExpect(jsonPath("$.errors[0].code").value("conflict"))
                .andExpect(jsonPath("$.requestId").isString());
    }
    @Test void forbiddenResponseDoesNotDisclosePrivateBindingReason() throws Exception {
        when(service.accept(any(), anyString(), any(), anyString())).thenThrow(new SecurityException("private SQL / token / installation config"));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("installation_binding_denied"))
                .andExpect(jsonPath("$.requestId").isString())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private SQL"))));
    }
    @Test void acceptanceKeepsEveryExistingResponseField() throws Exception {
        when(service.accept(any(), anyString(), any(), anyString())).thenReturn(new ExternalEventIngressService.IngressResult("generated-id", false, "request-pid"));
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.eventId").value("generated-id"))
                .andExpect(jsonPath("$.duplicate").value(false)).andExpect(jsonPath("$.recordPid").value("request-pid"));
    }
}
