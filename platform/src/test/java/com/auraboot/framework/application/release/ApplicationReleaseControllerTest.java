package com.auraboot.framework.application.release;

import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** MVC binding control contract only; platform-admin authentication is covered by the real-stack suite. */
class ApplicationReleaseControllerTest {
    private static final String RELEASE_ID = "01M3GNS9B7VCK4G88GPQXX941X";
    private static final String OPERATION_ID = "01M3H000000000000000000001";

    private final ApplicationReleaseRegistrationService registration = mock(ApplicationReleaseRegistrationService.class);
    private final ApplicationReleaseControlService control = mock(ApplicationReleaseControlService.class);
    private final DefinitionShadowComparisonService comparison = mock(DefinitionShadowComparisonService.class);
    private final TenantApplicationShadowBindingService shadowBindings =
            mock(TenantApplicationShadowBindingService.class);

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    void activatesOnlyAnExactStableShadowMatch() throws Exception {
        when(comparison.comparePublishedStable(42L, "aura-edu")).thenReturn(report(
                DefinitionShadowComparisonService.Classification.EXACT_MATCH));
        var binding = new ApplicationReleaseControlService.Binding(
                42L, 7L, RELEASE_ID, "sha256:" + "a".repeat(64), 1, "active", 2L);
        when(control.activateStableShadow(42L, "aura-edu", RELEASE_ID, 1L,
                "user:9:11", OPERATION_ID)).thenReturn(binding);
        MetaContext.setContext(9L, 11L, "admin", "Admin");

        standaloneSetup(new ApplicationReleaseController(registration, control, comparison, shadowBindings)).build()
                .perform(post("/api/admin/application-releases/aura-edu/tenant-bindings/42/activation")
                        .contentType("application/json")
                        .accept("application/json")
                        .content("""
                                {"expectedReleaseId":"%s","expectedVersion":1,"operationId":"%s"}
                                """.formatted(RELEASE_ID, OPERATION_ID)))
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.releaseId").value(RELEASE_ID))
                .andExpect(jsonPath("$.data.status").value("active"))
                .andExpect(jsonPath("$.data.version").value(2));

        verify(control).activateStableShadow(
                42L, "aura-edu", RELEASE_ID, 1L, "user:9:11", OPERATION_ID);
    }

    @Test
    void rejectsMissingLegacyDefinitionsBeforeBindingMutation() throws Exception {
        when(comparison.comparePublishedStable(42L, "aura-edu")).thenReturn(report(
                DefinitionShadowComparisonService.Classification.MISSING));
        MetaContext.setContext(9L, 11L, "admin", "Admin");

        standaloneSetup(new ApplicationReleaseController(registration, control, comparison, shadowBindings)).build()
                .perform(post("/api/admin/application-releases/aura-edu/tenant-bindings/42/activation")
                        .contentType("application/json")
                        .accept("application/json")
                        .content("""
                                {"expectedReleaseId":"%s","expectedVersion":1,"operationId":"%s"}
                                """.formatted(RELEASE_ID, OPERATION_ID)))
                .andExpect(jsonPath("$.code").value("409"))
                .andExpect(jsonPath("$.message").value("Exact stable definition shadow match is required"));

        verifyNoInteractions(control);
    }

    @Test
    void preparesOnlyAnExactStableShadowBinding() throws Exception {
        when(comparison.comparePublishedStable(42L, "aura-edu")).thenReturn(report(
                DefinitionShadowComparisonService.Classification.EXACT_MATCH));
        var binding = new TenantApplicationShadowBindingStore.Binding(
                42L, 7L, RELEASE_ID, "sha256:" + "b".repeat(64), 1, "shadow", 1L);
        when(shadowBindings.createPublishedStableShadow(42L, "aura-edu", RELEASE_ID, "sha256:" + "b".repeat(64),
                new TenantApplicationShadowBindingStore.AuditContext("user:9:11", OPERATION_ID)))
                .thenReturn(binding);
        MetaContext.setContext(9L, 11L, "admin", "Admin");

        standaloneSetup(new ApplicationReleaseController(registration, control, comparison, shadowBindings)).build()
                .perform(post("/api/admin/application-releases/aura-edu/tenant-bindings/42/shadow")
                        .contentType("application/json")
                        .accept("application/json")
                        .content("""
                                {"expectedReleaseId":"%s","operationId":"%s"}
                                """.formatted(RELEASE_ID, OPERATION_ID)))
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.releaseId").value(RELEASE_ID))
                .andExpect(jsonPath("$.data.status").value("shadow"))
                .andExpect(jsonPath("$.data.version").value(1));

        verify(shadowBindings).createPublishedStableShadow(42L, "aura-edu", RELEASE_ID, "sha256:" + "b".repeat(64),
                new TenantApplicationShadowBindingStore.AuditContext("user:9:11", OPERATION_ID));
    }

    @Test
    void rejectsNonExactDefinitionsBeforePreparingShadowBinding() throws Exception {
        when(comparison.comparePublishedStable(42L, "aura-edu")).thenReturn(report(
                DefinitionShadowComparisonService.Classification.DRIFTED));
        MetaContext.setContext(9L, 11L, "admin", "Admin");

        standaloneSetup(new ApplicationReleaseController(registration, control, comparison, shadowBindings)).build()
                .perform(post("/api/admin/application-releases/aura-edu/tenant-bindings/42/shadow")
                        .contentType("application/json")
                        .accept("application/json")
                        .content("""
                                {"expectedReleaseId":"%s","operationId":"%s"}
                                """.formatted(RELEASE_ID, OPERATION_ID)))
                .andExpect(jsonPath("$.code").value("409"))
                .andExpect(jsonPath("$.message").value("Exact stable definition shadow match is required"));

        verifyNoInteractions(shadowBindings);
    }

    @Test
    void reportsDisabledShadowWriterAsUnavailable() throws Exception {
        when(comparison.comparePublishedStable(42L, "aura-edu")).thenReturn(report(
                DefinitionShadowComparisonService.Classification.EXACT_MATCH));
        when(shadowBindings.createPublishedStableShadow(42L, "aura-edu", RELEASE_ID,
                "sha256:" + "b".repeat(64),
                new TenantApplicationShadowBindingStore.AuditContext("user:9:11", OPERATION_ID)))
                .thenThrow(new TenantApplicationShadowBindingService.ShadowBindingUnavailableException());
        MetaContext.setContext(9L, 11L, "admin", "Admin");

        standaloneSetup(new ApplicationReleaseController(registration, control, comparison, shadowBindings)).build()
                .perform(post("/api/admin/application-releases/aura-edu/tenant-bindings/42/shadow")
                        .contentType("application/json")
                        .accept("application/json")
                        .content("""
                                {"expectedReleaseId":"%s","operationId":"%s"}
                                """.formatted(RELEASE_ID, OPERATION_ID)))
                .andExpect(jsonPath("$.code").value("503"))
                .andExpect(jsonPath("$.message").value("Shadow binding connection is not enabled"));
    }

    private static DefinitionShadowComparisonService.Report report(
            DefinitionShadowComparisonService.Classification classification) {
        return new DefinitionShadowComparisonService.Report(
                42L, "aura-edu", RELEASE_ID, "sha256:" + "b".repeat(64), classification, 1, List.of());
    }
}
