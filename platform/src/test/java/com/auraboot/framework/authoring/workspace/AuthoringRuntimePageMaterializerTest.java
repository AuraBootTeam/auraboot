package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.environment.service.EnvironmentService;
import com.auraboot.framework.meta.dto.PageSchemaDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthoringRuntimePageMaterializerTest {

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    void resolvesTenantDefaultEnvironmentForAnAuthenticatedRuntimeRead() {
        AuthoringActiveReleaseResolver releaseResolver = mock(AuthoringActiveReleaseResolver.class);
        EnvironmentService environmentService = mock(EnvironmentService.class);
        MetaContext.setCurrentTenantId(41L);
        when(environmentService.findOrCreateDefaultId(41L)).thenReturn(73L);
        PageSchemaDTO baseline = new PageSchemaDTO();
        baseline.setPid("page-runtime-pid");
        baseline.setPageKey("crm_lead_desk_workbench");
        baseline.setRowVersion(3);

        PageSchemaDTO result = new AuthoringRuntimePageMaterializer(
                releaseResolver, environmentService, new ObjectMapper()).materialize(baseline);

        assertThat(result.getRuntime().source()).isEqualTo("PAGE_SCHEMA");
        assertThat(MetaContext.getCurrentEnvironmentId()).isEqualTo(73L);
        verify(environmentService).findOrCreateDefaultId(41L);
        verify(releaseResolver).findByResource(41L, 73L, "PAGE_SCHEMA", "page-runtime-pid");
    }
}
