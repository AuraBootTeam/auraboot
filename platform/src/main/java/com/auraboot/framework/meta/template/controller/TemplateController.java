package com.auraboot.framework.meta.template.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.template.dto.CrudTemplateConfig;
import com.auraboot.framework.meta.template.dto.TemplateGenerationResult;
import com.auraboot.framework.meta.template.service.TemplateGeneratorService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * Template Controller
 *
 * Provides REST API for CRUD template generation
 *
 * @author AuraBoot
 */
@Slf4j
@RestController
@RequestMapping("/api/templates")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateGeneratorService templateGeneratorService;

    /**
     * Generate CRUD template for a model
     *
     * @param request Template generation request containing modelCode and config
     * @return Generation result
     */
    @PostMapping("/crud/generate")
    @RequirePermission(MetaPermission.PAGE_MANAGE)
    public ApiResponse<TemplateGenerationResult> generateCrudTemplate(
        @Valid @RequestBody TemplateGenerationRequest request
    ) {
        log.info("Generating CRUD template for model: {}", request.getModelCode());

        TemplateGenerationResult result = templateGeneratorService.generateCrudPages(
            request.getModelCode(),
            request.getConfig()
        );

        log.info("CRUD template generated successfully for model: {}", request.getModelCode());
        return ApiResponse.success(result);
    }

    /**
     * Template Generation Request
     */
    @Data
    public static class TemplateGenerationRequest {
        @jakarta.validation.constraints.NotBlank(message = "Model code is required")
        private String modelCode;

        @jakarta.validation.constraints.NotNull(message = "Config is required")
        private CrudTemplateConfig config;
    }
}
