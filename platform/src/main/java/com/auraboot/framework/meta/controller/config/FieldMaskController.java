package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.entity.FieldMaskConfig;
import com.auraboot.framework.meta.service.FieldMaskService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for field mask configuration management.
 *
 * <p>Provides CRUD operations for configurable field-level data masking rules.
 * Each rule defines how a specific field in a model should be masked in
 * list views, detail views, and/or exports.
 *
 * @since 5.2.0
 */
@RestController
@RequestMapping("/api/field-mask/config")
@RequiredArgsConstructor
@RequirePermission(MetaPermission.PERMISSION_MANAGE)
public class FieldMaskController {

    private final FieldMaskService fieldMaskService;

    /**
     * List mask configs for a model (including disabled).
     * GET /api/field-mask/config?modelCode=xxx
     */
    @GetMapping
    public ApiResponse<List<FieldMaskConfig>> list(@RequestParam String modelCode) {
        return ApiResponse.success(fieldMaskService.listConfigs(modelCode));
    }

    /**
     * Create or update a mask config (upsert by model_code + field_code).
     * POST /api/field-mask/config
     */
    @PostMapping
    public ApiResponse<FieldMaskConfig> save(@RequestBody FieldMaskConfig config) {
        return ApiResponse.success(fieldMaskService.saveConfig(config));
    }

    /**
     * Delete a mask config by ID.
     * DELETE /api/field-mask/config/{id}
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id) {
        fieldMaskService.deleteConfig(id);
        return ApiResponse.success(Map.of("success", true, "id", id));
    }

    /**
     * Preview masking result for a given value and mask type.
     * GET /api/field-mask/config/preview?value=xxx&maskType=PHONE&maskPattern=&replacementChar=*
     */
    @GetMapping("/preview")
    public ApiResponse<Map<String, Object>> preview(
            @RequestParam String value,
            @RequestParam String maskType,
            @RequestParam(required = false) String maskPattern,
            @RequestParam(required = false, defaultValue = "*") String replacementChar) {
        String masked = fieldMaskService.maskValue(value, maskType, maskPattern, replacementChar);
        return ApiResponse.success(Map.of(
                "original", value,
                "masked", masked,
                "maskType", maskType
        ));
    }

    /**
     * Evict mask config cache for a model.
     * POST /api/field-mask/config/evict-cache?modelCode=xxx
     */
    @PostMapping("/evict-cache")
    public ApiResponse<Map<String, Object>> evictCache(@RequestParam String modelCode) {
        fieldMaskService.evictCache(modelCode);
        return ApiResponse.success(Map.of("success", true, "modelCode", modelCode));
    }
}
