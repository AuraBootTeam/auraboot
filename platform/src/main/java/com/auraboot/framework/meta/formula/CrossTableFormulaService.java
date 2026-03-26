package com.auraboot.framework.meta.formula;

import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Cross-table formula service for LOOKUP, VLOOKUP, and RELATED functions.
 *
 * These functions allow computed fields to reference data from other models,
 * enabling cross-table data retrieval in formulas.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CrossTableFormulaService {

    private final DynamicDataService dynamicDataService;

    /**
     * LOOKUP - Find a single value from another model
     */
    public Object lookup(String targetModel, String lookupField, Object lookupValue, String returnField) {
        if (targetModel == null || lookupField == null || lookupValue == null || returnField == null) {
            return null;
        }

        try {
            DynamicQueryRequest request = DynamicQueryRequest.builder()
                    .pageNum(1)
                    .pageSize(100)
                    .build();

            PaginationResult<Map<String, Object>> result = dynamicDataService.list(targetModel, request);

            if (result != null && result.getRecords() != null) {
                String searchValue = lookupValue.toString();
                for (Map<String, Object> record : result.getRecords()) {
                    Object fieldValue = record.get(lookupField);
                    if (fieldValue != null && fieldValue.toString().equals(searchValue)) {
                        return record.get(returnField);
                    }
                }
            }

            return null;
        } catch (Exception e) {
            log.warn("LOOKUP failed: targetModel={}, lookupField={}, error={}", targetModel, lookupField, e.getMessage());
            return null;
        }
    }

    /**
     * VLOOKUP - Vertical lookup with exact or fuzzy matching
     */
    public Object vlookup(Object lookupValue, String targetModel, String lookupField, String returnField, boolean exactMatch) {
        if (lookupValue == null || targetModel == null || lookupField == null || returnField == null) {
            return null;
        }

        try {
            DynamicQueryRequest request = DynamicQueryRequest.builder()
                    .pageNum(1)
                    .pageSize(100)
                    .build();

            PaginationResult<Map<String, Object>> result = dynamicDataService.list(targetModel, request);

            if (result != null && result.getRecords() != null) {
                String searchValue = lookupValue.toString();
                for (Map<String, Object> record : result.getRecords()) {
                    Object fieldValue = record.get(lookupField);
                    if (fieldValue == null) continue;

                    String fieldStr = fieldValue.toString();
                    boolean matches = exactMatch
                            ? fieldStr.equals(searchValue)
                            : fieldStr.contains(searchValue);

                    if (matches) {
                        return record.get(returnField);
                    }
                }
            }

            return null;
        } catch (Exception e) {
            log.warn("VLOOKUP failed: targetModel={}, error={}", targetModel, e.getMessage());
            return null;
        }
    }

    /**
     * RELATED - Get all values from a related model
     */
    public List<Object> related(String targetModel, String foreignKey, Object currentId, String returnField) {
        if (targetModel == null || foreignKey == null || currentId == null || returnField == null) {
            return Collections.emptyList();
        }

        try {
            DynamicQueryRequest request = DynamicQueryRequest.builder()
                    .pageNum(1)
                    .pageSize(500)
                    .build();

            PaginationResult<Map<String, Object>> result = dynamicDataService.list(targetModel, request);

            List<Object> values = new ArrayList<>();
            if (result != null && result.getRecords() != null) {
                String currentIdStr = currentId.toString();
                for (Map<String, Object> record : result.getRecords()) {
                    Object fkValue = record.get(foreignKey);
                    if (fkValue != null && fkValue.toString().equals(currentIdStr)) {
                        Object value = record.get(returnField);
                        if (value != null) {
                            values.add(value);
                        }
                    }
                }
            }

            return values;
        } catch (Exception e) {
            log.warn("RELATED failed: targetModel={}, error={}", targetModel, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * COUNTIF - Count records matching a condition
     */
    public long countIf(String targetModel, String condField, Object condValue) {
        if (targetModel == null || condField == null || condValue == null) {
            return 0;
        }

        try {
            DynamicQueryRequest request = DynamicQueryRequest.builder()
                    .pageNum(1)
                    .pageSize(1000)
                    .build();

            PaginationResult<Map<String, Object>> result = dynamicDataService.list(targetModel, request);

            if (result != null && result.getRecords() != null) {
                String searchValue = condValue.toString();
                return result.getRecords().stream()
                        .filter(r -> {
                            Object v = r.get(condField);
                            return v != null && v.toString().equals(searchValue);
                        })
                        .count();
            }

            return 0;
        } catch (Exception e) {
            log.warn("COUNTIF failed: targetModel={}, error={}", targetModel, e.getMessage());
            return 0;
        }
    }

    /**
     * SUMIF - Sum values matching a condition
     */
    public double sumIf(String targetModel, String condField, Object condValue, String sumField) {
        if (targetModel == null || condField == null || condValue == null || sumField == null) {
            return 0;
        }

        try {
            DynamicQueryRequest request = DynamicQueryRequest.builder()
                    .pageNum(1)
                    .pageSize(1000)
                    .build();

            PaginationResult<Map<String, Object>> result = dynamicDataService.list(targetModel, request);

            if (result != null && result.getRecords() != null) {
                String searchValue = condValue.toString();
                return result.getRecords().stream()
                        .filter(r -> {
                            Object v = r.get(condField);
                            return v != null && v.toString().equals(searchValue);
                        })
                        .mapToDouble(r -> {
                            Object v = r.get(sumField);
                            if (v instanceof Number) return ((Number) v).doubleValue();
                            try {
                                return Double.parseDouble(v.toString());
                            } catch (Exception ex) {
                                return 0;
                            }
                        })
                        .sum();
            }

            return 0;
        } catch (Exception e) {
            log.warn("SUMIF failed: targetModel={}, error={}", targetModel, e.getMessage());
            return 0;
        }
    }
}
