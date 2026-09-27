package com.auraboot.framework.application.release;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import org.springframework.web.bind.annotation.*;
import com.auraboot.framework.permission.annotation.RequirePlatformAdmin;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;

/** Platform-admin release registration, publication and exact-match tenant binding control. */
@RequirePlatformAdmin
@RestController
@RequestMapping("/api/admin/application-releases")
public class ApplicationReleaseController {
    private final ApplicationReleaseRegistrationService service;
    private final ApplicationReleaseControlService control;
    private final DefinitionShadowComparisonService shadowComparison;
    private final TenantApplicationShadowBindingService shadowBindings;

    public ApplicationReleaseController(ApplicationReleaseRegistrationService service,
                                        ApplicationReleaseControlService control,
                                        DefinitionShadowComparisonService shadowComparison,
                                        TenantApplicationShadowBindingService shadowBindings) {
        this.service = service;
        this.control = control;
        this.shadowComparison = shadowComparison;
        this.shadowBindings = shadowBindings;
    }

    public record RegisterRequest(String registrationKey, ApplicationReleaseRegistrationService.Content content) {}

    public record CreateApplicationRequest(String code, String name) {}
    public record PublishRequest(String operationId) {}
    public record StableRequest(String releaseId, Long expectedVersion, String operationId) {}
    public record PrepareTenantBindingRequest(String expectedReleaseId, String operationId) {}
    public record ActivateTenantBindingRequest(String expectedReleaseId, Long expectedVersion, String operationId) {}

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

    @PostMapping("/{applicationCode}/{releaseId}/publication")
    public ApiResponse<ApplicationReleaseControlService.Publication> publish(
            @PathVariable String applicationCode, @PathVariable String releaseId,
            @RequestBody PublishRequest request) {
        return ApiResponse.success(control.publish(applicationCode, releaseId, actor(), request.operationId()));
    }

    @PostMapping("/{applicationCode}/channels/stable")
    public ApiResponse<ApplicationReleaseControlService.ChannelTarget> promoteStable(
            @PathVariable String applicationCode, @RequestBody StableRequest request) {
        return ApiResponse.success(control.promoteStable(applicationCode, request.releaseId(),
                request.expectedVersion(), actor(), request.operationId()));
    }

    @GetMapping("/{applicationCode}/definition-shadow-report")
    public ApiResponse<DefinitionShadowComparisonService.Report> definitionShadowReport(
            @PathVariable String applicationCode, @RequestParam long tenantId) {
        return ApiResponse.success(shadowComparison.comparePublishedStable(tenantId, applicationCode));
    }

    @PostMapping("/{applicationCode}/tenant-bindings/{tenantId}/shadow")
    public ApiResponse<TenantApplicationShadowBindingStore.Binding> prepareTenantBinding(
            @PathVariable String applicationCode, @PathVariable long tenantId,
            @RequestBody PrepareTenantBindingRequest request) {
        var report = shadowComparison.comparePublishedStable(tenantId, applicationCode);
        if (report.classification() != DefinitionShadowComparisonService.Classification.EXACT_MATCH
                || !report.releaseId().equals(request.expectedReleaseId())) {
            throw new IllegalStateException("Exact stable definition shadow match is required");
        }
        return ApiResponse.success(shadowBindings.createPublishedStableShadow(
                tenantId, applicationCode, report.releaseId(), report.releaseDigest(),
                new TenantApplicationShadowBindingStore.AuditContext(actor(), request.operationId())));
    }

    @PostMapping("/{applicationCode}/tenant-bindings/{tenantId}/activation")
    @RequirePermission(MetaPermission.APPLICATION_BINDING_ACTIVATE)
    public ApiResponse<ApplicationReleaseControlService.Binding> activateTenantBinding(
            @PathVariable String applicationCode, @PathVariable long tenantId,
            @RequestBody ActivateTenantBindingRequest request) {
        var report = shadowComparison.comparePublishedStable(tenantId, applicationCode);
        if (report.classification() != DefinitionShadowComparisonService.Classification.EXACT_MATCH
                || !report.releaseId().equals(request.expectedReleaseId())) {
            throw new IllegalStateException("Exact stable definition shadow match is required");
        }
        return ApiResponse.success(control.activateStableShadow(tenantId, applicationCode,
                request.expectedReleaseId(), request.expectedVersion(), actor(), request.operationId()));
    }

    private static String actor() {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        if (tenantId == null || userId == null) throw new IllegalArgumentException("Authenticated control actor required");
        return "user:" + tenantId + ":" + userId;
    }

    @ExceptionHandler(ApplicationReleaseRegistrationService.RegistrationUnavailableException.class)
    public ApiResponse<Void> registrationUnavailable() {
        return ApiResponse.error(503, "Registry registration connection is not enabled", null);
    }

    @ExceptionHandler(TenantApplicationShadowBindingService.ShadowBindingUnavailableException.class)
    public ApiResponse<Void> shadowBindingUnavailable() {
        return ApiResponse.error(503, "Shadow binding connection is not enabled", null);
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
