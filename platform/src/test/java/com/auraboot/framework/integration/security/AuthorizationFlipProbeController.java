package com.auraboot.framework.integration.security;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authorization flip probe for the staged default-deny migration (unannotated-mode).
 *
 * <p>Registered only in the {@code authz-flip-test} profile so the flip ITs
 * ({@link UnannotatedModeFlipDenyIT}, {@link UnannotatedModeShadowControlIT}) can assert the
 * exact 401/403/200 matrix against real MVC + PermissionInterceptor wiring without coupling to
 * business endpoints. {@link #unannotated()} deliberately carries NO guard annotation — that is
 * the surface {@code aura.security.authz.unannotated-mode} governs; it lives under src/test so
 * the controller-authz ratchet (which scans src/main/java only) is unaffected.
 */
@Profile("authz-flip-test")
@RestController
@RequestMapping("/api/authz-flip")
public class AuthorizationFlipProbeController {

    @PostMapping("/unannotated")
    public ApiResponse<String> unannotated() {
        return ApiResponse.success("reached");
    }

    @AuthenticatedAccess("probe counterpart: acknowledged authenticated-only surface")
    @PostMapping("/authenticated-access")
    public ApiResponse<String> authenticatedAccess() {
        return ApiResponse.success("reached");
    }
}
