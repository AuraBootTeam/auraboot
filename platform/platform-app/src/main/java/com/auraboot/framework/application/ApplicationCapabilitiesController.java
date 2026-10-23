package com.auraboot.framework.application;

import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reports verified application composition, independently of permission and provider readiness. */
@RestController
public final class ApplicationCapabilitiesController {
    private final AgentAssemblyVerifier verifier;

    ApplicationCapabilitiesController(AgentAssemblyVerifier verifier) {
        this.verifier = verifier;
    }

    @GetMapping(value = "/api/application/capabilities", produces = MediaType.APPLICATION_JSON_VALUE)
    @AuthenticatedAccess("read-only verified installation facts; grants no AI execution authority")
    public ResponseEntity<ApplicationCapabilities> capabilities() {
        return verifier.verifiedAssembly()
                .map(assembly -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .body(new ApplicationCapabilities(1, new AgentCapability(
                                assembly == AgentAssembly.WITH_AGENT
                                        ? InstallationState.INSTALLED : InstallationState.NOT_INSTALLED))))
                .orElseGet(() -> ResponseEntity.status(503).cacheControl(CacheControl.noStore())
                        .header("Retry-After", "1").build());
    }

    public record ApplicationCapabilities(int schemaVersion, AgentCapability agent) { }

    public record AgentCapability(InstallationState state) { }

    public enum InstallationState {
        @JsonProperty("installed") INSTALLED,
        @JsonProperty("not_installed") NOT_INSTALLED
    }
}
