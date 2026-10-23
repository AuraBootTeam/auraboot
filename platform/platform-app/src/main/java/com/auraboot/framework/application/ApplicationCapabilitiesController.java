package com.auraboot.framework.application;

import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
    @Operation(summary = "Discover verified application composition",
            description = "Installation facts only; does not report provider configuration, entitlement or execution permission. Responses must not be cached.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Verified installation facts"),
            @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content),
            @ApiResponse(responseCode = "403", description = "Authentication context rejected", content = @Content),
            @ApiResponse(responseCode = "503", description = "Assembly not yet verified; retry later", content = @Content)
    })
    public ResponseEntity<ApplicationCapabilities> capabilities() {
        return verifier.verifiedAssembly()
                .map(assembly -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .body(new ApplicationCapabilities(1, new AgentCapability(
                                assembly == AgentAssembly.WITH_AGENT
                                        ? InstallationState.INSTALLED : InstallationState.NOT_INSTALLED))))
                .orElseGet(() -> ResponseEntity.status(503).cacheControl(CacheControl.noStore())
                        .header("Retry-After", "1").build());
    }

    public record ApplicationCapabilities(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "1") int schemaVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AgentCapability agent) { }

    public record AgentCapability(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) InstallationState state) { }

    public enum InstallationState {
        @JsonProperty("installed") INSTALLED,
        @JsonProperty("not_installed") NOT_INSTALLED
    }
}
