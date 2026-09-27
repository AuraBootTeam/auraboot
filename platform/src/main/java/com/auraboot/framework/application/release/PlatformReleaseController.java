package com.auraboot.framework.application.release;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePlatformAdmin;
import org.springframework.web.bind.annotation.*;

/** Registers immutable platform content without granting publication or deployment authority. */
@RequirePlatformAdmin
@RestController
@RequestMapping("/api/admin/platform-releases")
public class PlatformReleaseController {
    private final PlatformReleaseRegistrationService service;
    private final com.fasterxml.jackson.databind.ObjectReader contentReader;

    public PlatformReleaseController(PlatformReleaseRegistrationService service, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.service = service;
        var strictMapper = mapper.copy()
                .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(com.fasterxml.jackson.databind.MapperFeature.ALLOW_COERCION_OF_SCALARS);
        var textCoercion = strictMapper.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual);
        for (var shape : java.util.List.of(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
                com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float,
                com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean)) {
            textCoercion.setCoercion(shape, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
        }
        contentReader = strictMapper.readerFor(PlatformReleaseRegistrationService.Content.class);
    }

    @PostMapping("/{platformCode}")
    public ApiResponse<PlatformReleaseRegistrationService.Registration> register(
            @PathVariable String platformCode, @RequestBody String body) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        if (tenantId == null || userId == null) {
            return ApiResponse.error(403, "Authenticated registration actor required", null);
        }
        PlatformReleaseRegistrationService.Content content;
        try { content = contentReader.readValue(body); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("Invalid platform registration JSON");
        }
        return ApiResponse.success(service.register(platformCode, content, "user:" + tenantId + ":" + userId));
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
