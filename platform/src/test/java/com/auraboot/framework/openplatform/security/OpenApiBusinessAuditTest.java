package com.auraboot.framework.openplatform.security;

import com.auraboot.framework.openplatform.service.OpenApiCapabilityRegistry.Capability;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.assertj.core.api.Assertions.*;

class OpenApiBusinessAuditTest {
    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }

    @Test void capturesPublicRequestAndActualCreateReplayWithoutInputOrSecrets() {
        var request = request("POST", "/api/open/v1/resources/example.parts", "example.parts.create");
        request.addHeader("X-Actor-Kind", "human");
        request.addHeader("Authorization", "Bearer private-sentinel");
        request.setContent("{\"secret\":\"private-sentinel\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        OpenApiBusinessAudit.result("example.parts", "create", "record-pid", true);
        var details = OpenApiBusinessAudit.details(request);
        assertThat(details.path("actorKind").asText()).isEqualTo("machine");
        assertThat(details.path("capability").asText()).isEqualTo("example.parts.create");
        assertThat(details.path("resource").asText()).isEqualTo("example.parts");
        assertThat(details.path("operation").asText()).isEqualTo("create");
        assertThat(details.path("recordPid").asText()).isEqualTo("record-pid");
        assertThat(details.path("idempotentReplay").booleanValue()).isTrue();
        assertThat(details.size()).isEqualTo(6);
        assertThat(details.toString()).doesNotContain("private-sentinel", "Authorization", "secret");
        ((com.fasterxml.jackson.databind.node.ObjectNode) details).put("actorKind", "human");
        assertThat(OpenApiBusinessAudit.details(request).path("actorKind").asText()).isEqualTo("machine");
    }

    @Test void deniedRecordRequestRetainsPublicIdentityWithoutInventingReplay() {
        var request = request("PATCH", "/api/open/v1/resources/example.parts/record-pid", "example.parts.update");
        var details = OpenApiBusinessAudit.details(request);
        assertThat(details.path("recordPid").asText()).isEqualTo("record-pid");
        assertThat(details.path("operation").asText()).isEqualTo("update");
        assertThat(details.has("idempotentReplay")).isFalse();
        request = request("GET", "/api/open/v1/resources/example.parts/1234", "example.parts.get");
        assertThat(OpenApiBusinessAudit.details(request).has("recordPid")).isFalse();
    }

    @Test void queryAndCommandDescriptorsUsePublicCodesAndActualResult() {
        var request = request("POST", "/api/open/v1/queries/example.lookup:execute", "query.example.lookup.execute");
        assertThat(OpenApiBusinessAudit.details(request).path("resource").asText()).isEqualTo("example.lookup");
        assertThat(OpenApiBusinessAudit.details(request).path("operation").asText()).isEqualTo("execute");
        request = request("POST", "/api/open/v1/commands/assets.assign:execute", "assets.assign");
        OpenApiBusinessAudit.result("assets", "execute", "asset-pid", false);
        var details = OpenApiBusinessAudit.details(request);
        assertThat(details.path("capability").asText()).isEqualTo("assets.assign");
        assertThat(details.path("resource").asText()).isEqualTo("assets");
        assertThat(details.path("recordPid").asText()).isEqualTo("asset-pid");
        assertThat(details.path("idempotentReplay").booleanValue()).isFalse();
    }

    @Test void resultOutsideHttpDoesNotCreateAThreadLocalActor() {
        OpenApiBusinessAudit.result("example.parts", "create", "record-pid", false);
        assertThat(RequestContextHolder.getRequestAttributes()).isNull();
    }

    private MockHttpServletRequest request(String method, String path, String capability) {
        var request = new MockHttpServletRequest(method, path);
        OpenApiBusinessAudit.capture(request, new Capability(capability, HttpMethod.valueOf(method), path, "scope", "policy", 1));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }
}
