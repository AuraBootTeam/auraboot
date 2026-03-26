package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.entity.FieldMaskConfig;

import java.util.List;
import java.util.Map;

/**
 * Service for configurable field-level data masking.
 *
 * <p>Provides mask configuration CRUD and runtime masking for list views,
 * detail views, and exports.
 *
 * @since 5.2.0
 */
public interface FieldMaskService {

    // ==================== Configuration CRUD ====================

    /**
     * Get all mask configs for a model (including disabled).
     */
    List<FieldMaskConfig> listConfigs(String modelCode);

    /**
     * Get enabled mask configs for a model (cached per tenant + model).
     */
    List<FieldMaskConfig> getEnabledConfigs(String modelCode);

    /**
     * Create or update a mask config (upsert by model_code + field_code).
     */
    FieldMaskConfig saveConfig(FieldMaskConfig config);

    /**
     * Delete a mask config by ID.
     */
    void deleteConfig(Long id);

    // ==================== Runtime Masking ====================

    /**
     * Apply masking to a list of records for list view.
     *
     * @param modelCode model code
     * @param records   list of records (mutated in place)
     * @param userId    current user ID (for exempt role check)
     * @return masked records
     */
    List<Map<String, Object>> applyMaskingForList(String modelCode,
                                                   List<Map<String, Object>> records,
                                                   Long userId);

    /**
     * Apply masking to a single record for detail view.
     */
    Map<String, Object> applyMaskingForDetail(String modelCode,
                                               Map<String, Object> record,
                                               Long userId);

    /**
     * Apply masking to a list of records for export.
     */
    List<Map<String, Object>> applyMaskingForExport(String modelCode,
                                                     List<Map<String, Object>> records,
                                                     Long userId);

    /**
     * Apply a single mask rule to a string value.
     *
     * @param value           the original value
     * @param maskType        mask type (PHONE, EMAIL, etc.)
     * @param maskPattern     optional pattern for PARTIAL/CUSTOM
     * @param replacementChar replacement character (default '*')
     * @return masked value
     */
    String maskValue(String value, String maskType, String maskPattern, String replacementChar);

    /**
     * Evict the mask config cache for a model.
     */
    void evictCache(String modelCode);
}
