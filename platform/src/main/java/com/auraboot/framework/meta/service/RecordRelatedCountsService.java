package com.auraboot.framework.meta.service;

import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregate per-model record counts for the models that reference a given
 * record through reference fields — the backend half of the mobile detail
 * contract's {@code relatedCounts} block
 * (docs/system-reference/mobile/ux-specs/20-record-detail-data-api.md).
 *
 * <p>Discovery: every published model with a reference field whose
 * {@code refTarget.targetEntity/targetModel} points at the requested model.
 * Counting: one paginated dynamic-list query (pageSize 1) per referencing
 * model, reading only {@code total} — tenant scoping and data-scope guards
 * come from the dynamic query path for free.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordRelatedCountsService {

    private static final int MODEL_SCAN_PAGE_SIZE = 500;

    private final MetaModelService metaModelService;
    private final ModelFieldBindingService modelFieldBindingService;
    private final DynamicDataService dynamicDataService;

    /**
     * Count records per referencing model. Includes zero counts (Tab badges
     * need the model set, not just non-zero entries).
     */
    public Map<String, Long> relatedCounts(String modelCode, String recordPid) {
        Map<String, Long> counts = new LinkedHashMap<>();
        PageResult<MetaModelDTO> models = metaModelService.searchModels(
                1, MODEL_SCAN_PAGE_SIZE, null, null, null, null, null, null, null, null, true);
        if (models == null || models.getRecords() == null) {
            return counts;
        }
        for (MetaModelDTO model : models.getRecords()) {
            if (model.getCode() == null || model.getCode().equals(modelCode) || model.getPid() == null) {
                continue;
            }
            String referenceField = findReferenceFieldTo(modelCode, model);
            if (referenceField == null) {
                continue;
            }
            try {
                counts.put(model.getCode(), countReferences(model.getCode(), referenceField, recordPid));
            } catch (Exception e) {
                log.debug("related-counts failed for model {} field {}: {}",
                        model.getCode(), referenceField, e.getMessage());
            }
        }
        return counts;
    }

    /** First reference field on {@code referencingModel} that points at {@code targetModel}. */
    private String findReferenceFieldTo(String targetModel, MetaModelDTO referencingModel) {
        try {
            for (MetaFieldDTO field : modelFieldBindingService.getModelFields(referencingModel.getPid())) {
                Map<String, Object> refTarget = field.getRefTarget();
                if (refTarget == null) {
                    continue;
                }
                Object target = refTarget.get("targetEntity") != null
                        ? refTarget.get("targetEntity") : refTarget.get("targetModel");
                if (targetModel.equals(target)) {
                    return field.getCode();
                }
            }
        } catch (Exception e) {
            log.debug("reference-field scan failed for model {}: {}",
                    referencingModel.getCode(), e.getMessage());
        }
        return null;
    }

    private long countReferences(String modelCode, String fieldName, String recordPid) {
        QueryCondition condition = new QueryCondition();
        condition.setFieldName(fieldName);
        condition.setOperator(QueryCondition.Operator.EQ);
        condition.setValue(recordPid);
        DynamicQueryRequest request = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(1)
                .conditions(List.of(condition))
                .build();
        PaginationResult<Map<String, Object>> page = dynamicDataService.list(modelCode, request);
        return page != null && page.getTotal() != null ? page.getTotal() : 0L;
    }
}
