package com.auraboot.framework.openplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class OpenApiEndpointRegistryTest {
    static class Routes {
        @OpenApiEndpointCandidate(code="fixture.read", adapter=OpenApiEndpointKind.RESOURCE_GET, version="1", name="Read device", chineseName="读取设备")
        public void read() { }
        public void unadapted() { }
        @OpenApiEndpointCandidate(code="installed-integration.execute", adapter=OpenApiEndpointKind.RESOURCE_GET, version="1", name="Collision", chineseName="重复")
        public void collision() { }
        @OpenApiEndpointCandidate(code="fixture.invalid", adapter=OpenApiEndpointKind.RESOURCE_GET, version="", name="", chineseName="")
        public void invalid() { }
    }
    private final RequestMappingHandlerMapping mapping = new RequestMappingHandlerMapping();
    private final OpenApiEndpointRegistry registry = new OpenApiEndpointRegistry(mapping, new OpenApiProtocolHash(new ObjectMapper(), new OpenPlatformSecretCodec()));
    private void route(String path, String method) throws Exception {
        mapping.registerMapping(RequestMappingInfo.paths(path).methods(RequestMethod.GET).build(), new Routes(), Routes.class.getMethod(method));
    }
    @Test void actualRoutesAreInventoriedButOnlyDeveloperAdaptedMethodsAreExecutable() throws Exception {
        route("/api/devices/{recordPid}","read"); route("/api/admin/secret","unadapted"); route("/health","unadapted"); route("/api/open/v1/endpoints/{code}","unadapted");
        var catalog = registry.candidates(null); assertThat(catalog).hasSize(3);
        assertThat(registry.require(OpenApiSynchronousIntegrationService.CANDIDATE).adapterCode()).isEqualTo("integration.execute");
        assertThat(catalog.getFirst().available()).isTrue(); assertThat(catalog.getFirst().internalPath()).isEqualTo("/api/devices/{recordPid}");
        assertThat(catalog.getFirst().fingerprint()).matches("[a-f0-9]{64}"); assertThat(catalog.getFirst().targetRequired()).isTrue();
        assertThat(catalog.getLast().available()).isFalse(); assertThat(catalog.getLast().reason()).isEqualTo("machine_adapter_required");
        assertThat(registry.candidates("ADMIN")).hasSize(1); assertThat(registry.require("fixture.read")).isEqualTo(catalog.getFirst());
        assertThatThrownBy(() -> registry.require("/api/admin/secret")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.candidates("a".repeat(201))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void ambiguousRegistrationCannotSelectTheFirstMethodByAccident() throws Exception {
        route("/api/a","read"); route("/api/b","read");
        assertThatThrownBy(() -> registry.candidates(null)).isInstanceOf(IllegalStateException.class).hasMessageContaining("exactly one");
    }
    @Test void controllerCannotShadowTheBuiltInIntegrationCandidateEvenUnderFilteredSearch() throws Exception {
        route("/api/collision","collision");
        assertThatThrownBy(() -> registry.candidates("fixture")).isInstanceOf(IllegalStateException.class).hasMessageContaining("exactly one");
    }
    @Test void declaredVersionAndLocalizedIdentityAreMandatory() throws Exception {
        route("/api/invalid","invalid"); assertThatThrownBy(() -> registry.candidates(null)).isInstanceOf(IllegalStateException.class);
    }
    @Test void routeChangesChangeTheSourceFingerprint() throws Exception {
        route("/api/a","read"); String before = registry.require("fixture.read").fingerprint();
        var old = mapping.getHandlerMethods().keySet().iterator().next(); mapping.unregisterMapping(old);route("/api/b","read");
        assertThat(registry.require("fixture.read").fingerprint()).isNotEqualTo(before);
    }
}
