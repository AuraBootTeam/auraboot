package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.AffectedModel;
import com.auraboot.framework.meta.dto.FieldModification;
import com.auraboot.framework.meta.dto.ModificationImpact;
import com.auraboot.framework.meta.service.FieldImpactAnalysisService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Field impact analysis controller
 * Provides REST API for field modification impact analysis
 * 
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Slf4j
@RestController
@RequestMapping("/api/meta/fields/{fieldPid}/impact")
@RequiredArgsConstructor
public class FieldImpactController {

    private final FieldImpactAnalysisService impactAnalysisService;

    /**
     * Analyze modification impact
     * POST /api/meta/fields/{fieldPid}/impact/analyze
     * 
     * @param fieldPid Field PID
     * @param modification Field modification details
     * @return Impact analysis result
     */
    @PostMapping("/analyze")
    @RequirePermission(MetaPermission.FIELD_READ)
    public ApiResponse<ModificationImpact> analyzeModificationImpact(
            @PathVariable String fieldPid,
            @RequestBody FieldModification modification) {
        
        log.info("Analyzing modification impact: fieldPid={}", fieldPid);
        
        ModificationImpact impact = impactAnalysisService.analyzeModificationImpact(fieldPid, modification);
        
        return ApiResponse.success(impact);
    }

    /**
     * Check if modification is breaking change
     * POST /api/meta/fields/{fieldPid}/impact/check-breaking
     * 
     * @param fieldPid Field PID
     * @param modification Field modification details
     * @return true if breaking change, false otherwise
     */
    @PostMapping("/check-breaking")
    @RequirePermission(MetaPermission.FIELD_READ)
    public ApiResponse<Boolean> isBreakingChange(
            @PathVariable String fieldPid,
            @RequestBody FieldModification modification) {
        
        log.info("Checking if modification is breaking change: fieldPid={}", fieldPid);
        
        boolean isBreaking = impactAnalysisService.isBreakingChange(modification);
        
        return ApiResponse.success(isBreaking);
    }

    /**
     * Get affected models for modification
     * GET /api/meta/fields/{fieldPid}/impact/affected-models
     * 
     * @param fieldPid Field PID
     * @return List of affected models
     */
    @GetMapping("/affected-models")
    @RequirePermission(MetaPermission.FIELD_READ)
    public ApiResponse<List<AffectedModel>> getAffectedModels(@PathVariable String fieldPid) {
        log.info("Getting affected models: fieldPid={}", fieldPid);
        
        // Create a placeholder modification for getting all affected models
        FieldModification modification = FieldModification.builder()
            .fieldPid(fieldPid)
            .build();
        
        List<AffectedModel> affectedModels = impactAnalysisService.getAffectedModels(fieldPid, modification);
        
        return ApiResponse.success(affectedModels);
    }
}
