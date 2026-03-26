package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.FieldForkRequest;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.entity.FieldForkHistory;
import com.auraboot.framework.meta.service.FieldForkService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Field fork controller
 * Provides REST API for field fork operations
 * 
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@RestController
@RequestMapping("/api/meta/fields/{fieldPid}/fork")
@RequiredArgsConstructor
public class FieldForkController {

    private final FieldForkService fieldForkService;

    /**
     * Fork a field to create a variant
     * POST /api/meta/fields/{fieldPid}/fork
     * 
     * @param fieldPid Original field PID
     * @param request Fork request with modifications
     * @return Forked field
     */
    @PostMapping
    @RequirePermission(MetaPermission.FIELD_MANAGE)
    public ApiResponse<MetaFieldDTO> forkField(
            @PathVariable String fieldPid,
            @RequestBody FieldForkRequest request) {
        
        log.info("Forking field: originalFieldPid={}, newCode={}", fieldPid, request.getNewCode());
        
        MetaFieldDTO forkedField = fieldForkService.forkField(fieldPid, request);
        
        return ApiResponse.success(forkedField);
    }

    /**
     * Get fork history for a field
     * GET /api/meta/fields/{fieldPid}/fork/history
     * 
     * @param fieldPid Field PID
     * @return List of fork history records
     */
    @GetMapping("/history")
    @RequirePermission(MetaPermission.FIELD_READ)
    public ApiResponse<List<FieldForkHistory>> getForkHistory(@PathVariable String fieldPid) {
        log.info("Getting fork history: fieldPid={}", fieldPid);
        
        List<FieldForkHistory> history = fieldForkService.getForkHistory(fieldPid);
        
        return ApiResponse.success(history);
    }

    /**
     * Get original field for a forked field
     * GET /api/meta/fields/{fieldPid}/fork/original
     * 
     * @param fieldPid Forked field PID
     * @return Original field
     */
    @GetMapping("/original")
    @RequirePermission(MetaPermission.FIELD_READ)
    public ApiResponse<MetaFieldDTO> getOriginalField(@PathVariable String fieldPid) {
        log.info("Getting original field: forkedFieldPid={}", fieldPid);
        
        return fieldForkService.getOriginalField(fieldPid)
            .map(ApiResponse::success)
            .orElse(ApiResponse.success(null));
    }

    /**
     * Get all forked variants of a field
     * GET /api/meta/fields/{fieldPid}/fork/variants
     * 
     * @param fieldPid Original field PID
     * @return List of forked fields
     */
    @GetMapping("/variants")
    @RequirePermission(MetaPermission.FIELD_READ)
    public ApiResponse<List<MetaFieldDTO>> getForkedVariants(@PathVariable String fieldPid) {
        log.info("Getting forked variants: originalFieldPid={}", fieldPid);
        
        List<MetaFieldDTO> variants = fieldForkService.getForkedVariants(fieldPid);
        
        return ApiResponse.success(variants);
    }
}
