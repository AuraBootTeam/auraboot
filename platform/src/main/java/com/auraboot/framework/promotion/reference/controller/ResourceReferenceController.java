package com.auraboot.framework.promotion.reference.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.promotion.reference.dao.entity.ResourceReference;
import com.auraboot.framework.promotion.reference.service.ResourceReferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Reverse-reference impact analysis endpoint (env-layering #11 — Diff Viewer impact sidebar).
 *
 * <p>Honors the current MetaContext envId via the @EnvScoped interceptor. To go cross-env,
 * callers can pass {@code ?env=<code>} on the request which the EnvironmentResolverInterceptor
 * picks up.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/references")
@RequiredArgsConstructor
public class ResourceReferenceController {

    private final ResourceReferenceService referenceService;

    /**
     * @param targetType MODEL | FIELD
     * @param targetCode the model code or field code to look up
     * @return list of references whose target matches; each carries source page pid + ref location
     */
    @GetMapping("/impact")
    public ApiResponse<List<ResourceReference>> impact(
            @RequestParam("type") String targetType,
            @RequestParam("code") String targetCode) {
        return ApiResponse.success(referenceService.findReferencingPages(targetType, targetCode));
    }
}
