package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.meta.dto.FieldRecommendation;
import com.auraboot.framework.meta.dto.FieldSearchRequest;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.service.FieldLibraryService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Field library controller
 * Provides REST API for field library management
 * 
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@RestController
@RequestMapping("/api/meta/field-library")
@RequiredArgsConstructor
public class FieldLibraryController {

    private final FieldLibraryService fieldLibraryService;

    /**
     * List all fields grouped by semantic type
     * GET /api/meta/field-library
     * 
     * @return Fields grouped by semantic type
     */
    @GetMapping
    @RequirePermission(MetaPermission.FIELD_READ)
    public ApiResponse<Map<String, List<MetaFieldDTO>>> listFieldsBySemanticType() {
        log.info("Listing fields by semantic type");
        
        Map<String, List<MetaFieldDTO>> result = fieldLibraryService.listFieldsBySemanticType();
        
        return ApiResponse.success(result);
    }

    /**
     * Search fields with advanced filters
     * POST /api/meta/field-library/search
     * 
     * @param request Search request with filters
     * @return Paginated field list
     */
    @PostMapping("/search")
    @RequirePermission(MetaPermission.FIELD_READ)
    public ApiResponse<PageResult<MetaFieldDTO>> searchFields(@RequestBody FieldSearchRequest request) {
        log.info("Searching fields: keyword={}, baseType={}, semanticType={}", 
            request.getKeyword(), request.getBaseType(), request.getSemanticType());
        
        PageResult<MetaFieldDTO> result = fieldLibraryService.searchFields(request);
        
        return ApiResponse.success(result);
    }

    /**
     * Get field recommendations for model binding
     * GET /api/meta/field-library/recommendations
     * 
     * @param modelPid Model PID
     * @param semanticType Semantic type filter (optional)
     * @return List of recommended fields
     */
    @GetMapping("/recommendations")
    @RequirePermission(MetaPermission.FIELD_READ)
    public ApiResponse<List<FieldRecommendation>> getRecommendations(
            @RequestParam String modelPid,
            @RequestParam(required = false) String semanticType) {
        
        log.info("Getting field recommendations: modelPid={}, semanticType={}", modelPid, semanticType);
        
        List<FieldRecommendation> recommendations = 
            fieldLibraryService.getFieldRecommendations(modelPid, semanticType);
        
        return ApiResponse.success(recommendations);
    }

    /**
     * Get system fields
     * GET /api/meta/field-library/system-fields
     * 
     * @return List of system fields
     */
    @GetMapping("/system-fields")
    @RequirePermission(MetaPermission.FIELD_READ)
    public ApiResponse<List<MetaFieldDTO>> getSystemFields() {
        log.info("Getting system fields");
        
        List<MetaFieldDTO> systemFields = fieldLibraryService.getSystemFields();
        
        return ApiResponse.success(systemFields);
    }

    /**
     * Get common business fields
     * GET /api/meta/field-library/common-fields
     * 
     * @return List of commonly used business fields
     */
    @GetMapping("/common-fields")
    @RequirePermission(MetaPermission.FIELD_READ)
    public ApiResponse<List<MetaFieldDTO>> getCommonBusinessFields() {
        log.info("Getting common business fields");
        
        List<MetaFieldDTO> commonFields = fieldLibraryService.getCommonBusinessFields();
        
        return ApiResponse.success(commonFields);
    }

    /**
     * Get unused fields
     * GET /api/meta/field-library/unused-fields
     * 
     * @return List of unused fields
     */
    @GetMapping("/unused-fields")
    @RequirePermission(MetaPermission.FIELD_READ)
    public ApiResponse<List<MetaFieldDTO>> getUnusedFields() {
        log.info("Getting unused fields");
        
        List<MetaFieldDTO> unusedFields = fieldLibraryService.getUnusedFields();
        
        return ApiResponse.success(unusedFields);
    }
}
