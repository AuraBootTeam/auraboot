package com.auraboot.framework.application;

import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Hermetic serialization/cache contract; real authentication is covered by the packaged HTTP suite. */
class ApplicationCapabilitiesControllerTest {
    @ParameterizedTest
    @EnumSource(AgentAssembly.class)
    void serializesOnlyVerifiedInstallationFacts(AgentAssembly assembly) throws Exception {
        var verifier = mock(AgentAssemblyVerifier.class);
        when(verifier.verifiedAssembly()).thenReturn(Optional.of(assembly));
        var mvc = MockMvcBuilders.standaloneSetup(new ApplicationCapabilitiesController(verifier)).build();
        String state = assembly == AgentAssembly.WITH_AGENT ? "installed" : "not_installed";
        mvc.perform(get("/api/application/capabilities").header("If-None-Match", "*"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(content().json("{\"schemaVersion\":1,\"agent\":{\"state\":\"" + state + "\"}}", true));
        verify(verifier).verifiedAssembly();
        verifyNoMoreInteractions(verifier);
    }

    @Test
    void unverifiedCompositionIsUnavailableRatherThanAbsent() throws Exception {
        var verifier = mock(AgentAssemblyVerifier.class);
        when(verifier.verifiedAssembly()).thenReturn(Optional.empty());
        var mvc = MockMvcBuilders.standaloneSetup(new ApplicationCapabilitiesController(verifier)).build();
        mvc.perform(get("/api/application/capabilities"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(content().string(""));
    }

    @Test
    void endpointExplicitlyDeclaresItsAuthenticationOnlyBoundary() throws Exception {
        assertThat(ApplicationCapabilitiesController.class.getMethod("capabilities")
                .getAnnotation(AuthenticatedAccess.class)).isNotNull();
    }

    @Test
    void openApiVersionRemainsNumericAndInstallationFieldsAreRequired() {
        var model = new ModelConverters(false).resolveAsResolvedSchema(
                new AnnotatedType(ApplicationCapabilitiesController.ApplicationCapabilities.class));
        io.swagger.v3.oas.models.media.Schema<?> capabilities = model.schema;
        assertThat(capabilities.getRequired()).containsExactlyInAnyOrder("agent", "schemaVersion");
        var version = capabilities.getProperties().get("schemaVersion");
        assertThat(version.getType()).isEqualTo("integer");
        assertThat(version.getEnum()).isNull();
        assertThat(version.getMinimum()).isEqualTo(BigDecimal.ONE);
        assertThat(version.getMaximum()).isEqualTo(BigDecimal.ONE);
        assertThat(model.referencedSchemas.get("AgentCapability").getRequired()).containsExactly("state");
    }
}
