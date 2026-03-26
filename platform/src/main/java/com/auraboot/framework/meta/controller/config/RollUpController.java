package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.entity.Field;
import com.auraboot.framework.meta.entity.payload.FieldFeatureBean;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelFieldBindingMapper;
import com.auraboot.framework.meta.service.impl.RollUpFieldRegistry;
import com.auraboot.framework.meta.service.impl.RollUpSummaryService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Controller for Roll-Up Summary field operations.
 * Provides batch recalculation endpoint for data migration or fixing inconsistencies.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Slf4j
@RestController
@RequestMapping("/api/meta/rollup")
@RequiredArgsConstructor
public class RollUpController {

    private final RollUpSummaryService rollUpSummaryService;
    private final RollUpFieldRegistry rollUpFieldRegistry;
    private final MetaFieldMapper metaFieldMapper;
    private final MetaModelFieldBindingMapper modelFieldBindingMapper;

    /**
     * Batch recalculate a roll-up field for all parent records.
     * Useful for data migration, fixing inconsistencies, or initial population.
     */
    @PostMapping("/recalculate")
    public ApiResponse<Map<String, Object>> recalculate(@RequestBody RecalculateRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();

        // Look up the field to find its rollUp config
        Field field = metaFieldMapper.findCurrentByCode(request.getFieldCode());
        if (field == null) {
            return ApiResponse.error("Field not found: " + request.getFieldCode());
        }

        FieldFeatureBean feature = field.getFeature();
        if (feature == null || feature.getRollUp() == null) {
            return ApiResponse.error("Field '" + request.getFieldCode() + "' does not have rollUp configuration");
        }

        FieldFeatureBean.RollUpConfig rollUp = feature.getRollUp();

        // Determine parent model
        String parentModel = request.getParentModel();
        if (parentModel == null) {
            parentModel = modelFieldBindingMapper.findModelCodeByFieldId(field.getId());
        }
        if (parentModel == null) {
            return ApiResponse.error("Cannot determine parent model for field: " + request.getFieldCode());
        }

        String function = rollUp.getFunction() != null ? rollUp.getFunction().toUpperCase() : "sum";

        int updated = rollUpSummaryService.batchRecalculate(
                parentModel,
                request.getFieldCode(),
                rollUp.getChildModel(),
                rollUp.getChildField(),
                rollUp.getChildFk(),
                function,
                rollUp.getChildFilter(),
                tenantId
        );

        log.info("Batch recalculate rollUp {}.{}: {} records updated", parentModel, request.getFieldCode(), updated);

        return ApiResponse.success(Map.of(
                "parentModel", parentModel,
                "fieldCode", request.getFieldCode(),
                "recordsUpdated", updated
        ));
    }

    /**
     * Invalidate the roll-up field registry cache. Useful after metadata changes.
     */
    @PostMapping("/cache/invalidate")
    public ApiResponse<String> invalidateCache() {
        rollUpFieldRegistry.invalidate();
        return ApiResponse.success("Roll-up field registry cache invalidated");
    }

    @Data
    public static class RecalculateRequest {
        /** Parent model code (optional — auto-detected from field binding) */
        private String parentModel;
        /** Field code on the parent model with rollUp config */
        private String fieldCode;
    }
}
