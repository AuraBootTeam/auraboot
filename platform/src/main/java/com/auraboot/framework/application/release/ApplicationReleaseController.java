package com.auraboot.framework.application.release;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;
import com.auraboot.framework.permission.annotation.RequirePlatformAdmin;

/** Platform-admin content registration. Registration does not grant publication qualification. */
@RequirePlatformAdmin
@RestController
@RequestMapping("/api/admin/application-releases")
public class ApplicationReleaseController {
    private final ApplicationReleaseRegistrationService service;

    public ApplicationReleaseController(ApplicationReleaseRegistrationService service) {
        this.service = service;
    }

    public record RegisterRequest(String registrationKey, ApplicationReleaseRegistrationService.Content content) {}

    public record CreateApplicationRequest(String code, String name) {}

    @PostMapping
    public ApiResponse<ApplicationReleaseRegistrationService.Application> createApplication(
            @RequestBody CreateApplicationRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        if (tenantId == null || userId == null) {
            return ApiResponse.error(403, "Authenticated creation actor required", null);
        }
        return ApiResponse.success(service.createApplication(request.code(), request.name(),
                "user:" + tenantId + ":" + userId));
    }

    @PostMapping("/{applicationCode}")
    public ApiResponse<ApplicationReleaseRegistrationService.Registration> register(
            @PathVariable String applicationCode, @RequestBody RegisterRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        if (tenantId == null || userId == null) {
            return ApiResponse.error(403, "Authenticated registration actor required", null);
        }
        return ApiResponse.success(service.register(applicationCode, request.registrationKey(), request.content(),
                "user:" + tenantId + ":" + userId));
    }

    @ExceptionHandler(ApplicationReleaseRegistrationService.RegistrationUnavailableException.class)
    public ApiResponse<Void> registrationUnavailable() {
        return ApiResponse.error(503, "Registry registration connection is not enabled", null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> invalidRequest(IllegalArgumentException failure) {
        return ApiResponse.error(400, failure.getMessage(), null);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ApiResponse<Void> conflictingRegistration(IllegalStateException failure) {
        return ApiResponse.error(409, failure.getMessage(), null);
    }
}
