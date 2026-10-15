package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.openplatform.dto.IntegrationTokenDtos.*;
import com.auraboot.framework.openplatform.service.IntegrationTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class IntegrationTokenControllerTest {
    private final IntegrationTokenService service = mock(IntegrationTokenService.class);
    private MockMvc mvc;
    private final String path = "/api/open-platform/installations/install-fixture/integration-tokens";
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new IntegrationTokenController(service))
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(
                        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()))
                .setControllerAdvice(new IntegrationTokenErrorHandler()).build();
    }
    @Test void secretResponseIsNotCacheableAndCarriesExplicitPermanentMetadata() throws Exception {
        var view = new View("token-fixture", "ERP", "active", "never", Instant.now(), null, null, null, null, null);
        when(service.create(eq("install-fixture"), any(), eq("management-intent")))
                .thenReturn(new Secret("app-fixture", "install-fixture", "opaque-once", view));
        mvc.perform(post(path).header("Idempotency-Key", "management-intent").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ERP\",\"expirationMode\":\"never\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.appId").value("app-fixture"))
                .andExpect(jsonPath("$.data.token").value("opaque-once"))
                .andExpect(jsonPath("$.data.metadata.expirationMode").value("never"));
    }
    @Test void invalidLifetimeAndMalformedJsonAreHttp400WithoutCallingService() throws Exception {
        mvc.perform(post(path).header("Idempotency-Key", "management-intent").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"ERP\",\"expirationMode\":\"forever-typo\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path).header("Idempotency-Key", "management-intent").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void deniedAdministrationDoesNotReturnSecretOrPrivateReason() throws Exception {
        when(service.list("install-fixture")).thenThrow(new AccessDeniedException("private-policy-detail"));
        mvc.perform(get(path)).andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-policy-detail"))));
    }
}
