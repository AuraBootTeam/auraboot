package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.openplatform.service.OpenApiPublicationService;
import com.auraboot.framework.openplatform.service.OpenApiQueryPublicationService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Hermetic MVC boundary tests; persistence and authorization are separately real-stack tested. */
class OpenApiPublicationErrorHandlerTest {
    private final OpenApiPublicationService resources = mock(OpenApiPublicationService.class);
    private final OpenApiQueryPublicationService queries = mock(OpenApiQueryPublicationService.class);
    private final com.auraboot.framework.openplatform.service.OpenPlatformManagementService management = mock(com.auraboot.framework.openplatform.service.OpenPlatformManagementService.class);
    private final org.springframework.test.web.servlet.MockMvc http = MockMvcBuilders.standaloneSetup(
            new OpenApiPublicationController(resources), new OpenApiQueryPublicationController(queries),
            new OpenPlatformManagementController(management, mock(com.auraboot.framework.openplatform.service.OpenApiCapabilityRegistry.class),
                    mock(com.auraboot.framework.openplatform.service.OpenApiEventCatalog.class)))
            .setControllerAdvice(new OpenApiPublicationErrorHandler(), new OpenPlatformManagementErrorHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter()).build();

    @Test void invalidResourceAndQueryContractsAreClientErrors() throws Exception {
        when(resources.publishedForAdministration("parts")).thenThrow(new IllegalArgumentException("Published resource required"));
        when(queries.publishedForAdministration("summary")).thenThrow(new IllegalArgumentException("Published query required"));
        for (String path : new String[]{"/publications/parts/openapi", "/query-publications/summary/types"}) {
            http.perform(get("/api/open-platform" + path)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("400"));
        }
    }

    @Test void staleResourceAndQueryRevisionsRetainConflictStatus() throws Exception {
        when(resources.publish("parts", 1)).thenThrow(new OpenApiPublicationService.PublicationConflictException("Publication draft revision changed"));
        when(queries.publish("summary", 1)).thenThrow(new OpenApiPublicationService.PublicationConflictException("Query publication draft or schema revision changed"));
        for (String path : new String[]{"/publications/parts/publish", "/query-publications/summary/publish"}) {
            http.perform(post("/api/open-platform" + path).contentType("application/json").content("{\"expectedRevision\":1}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("409"));
        }
    }

    @Test void deniedAdministrationDoesNotExposePrivateExceptionDetails() throws Exception {
        when(resources.list()).thenThrow(new AccessDeniedException("private actor context"));
        when(queries.list()).thenThrow(new AccessDeniedException("private actor context"));
        for (String path : new String[]{"/publications", "/query-publications"}) {
            http.perform(get("/api/open-platform" + path)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("403"))
                    .andExpect(jsonPath("$.message").value("Open Platform administrator permission required"));
        }
    }

    @Test void malformedContractsAreRejectedWithoutInvokingPublication() throws Exception {
        for (String path : new String[]{"/publications/parts/draft", "/query-publications/summary/draft"}) {
            http.perform(put("/api/open-platform" + path).contentType("application/json").content("{\"expectedRevision\":\"invalid\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("400"))
                    .andExpect(jsonPath("$.message").value("Publication request contains invalid values or types"));
        }
        verifyNoInteractions(resources, queries);
    }
    @Test void applicationScopeErrorsAre400AndPreserveTheirUsefulPublicReason() throws Exception {
        when(management.install(eq("application"), any())).thenThrow(new IllegalArgumentException("One or more scopes are not published by the Open API registry"));
        http.perform(post("/api/open-platform/applications/application/installations").contentType("application/json")
                .content("{\"environment\":\"development\",\"scopes\":[\"unpublished.scope\"],\"rateLimitPerMinute\":100}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("400"))
                .andExpect(jsonPath("$.message").value("One or more scopes are not published by the Open API registry"));
    }

    @Test void applicationErrorsKeepTheirOwnMalformedAndPermissionContract() throws Exception {
        http.perform(post("/api/open-platform/applications/application/installations").contentType("application/json")
                .content("{\"rateLimitPerMinute\":\"invalid\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid Open Platform management request"));
        when(management.listApplications()).thenThrow(new AccessDeniedException("private application actor"));
        http.perform(get("/api/open-platform/applications")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Application management permission required"));
    }

}
