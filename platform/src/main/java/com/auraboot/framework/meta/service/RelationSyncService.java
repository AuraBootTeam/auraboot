package com.auraboot.framework.meta.service;

import java.util.List;
import java.util.Map;

/**
 * Service for synchronizing bidirectional relations
 *
 * @author AuraBoot Team
 * @since 3.0.0
 */
public interface RelationSyncService {

    /**
     * Sync inverse side when owning side is updated
     *
     * @param modelCode The model code of the owning side
     * @param recordId The record ID being updated
     * @param fieldCode The relation field code
     * @param oldTargetIds Previous target record IDs
     * @param newTargetIds New target record IDs
     */
    void syncInverseSide(String modelCode, String recordId, String fieldCode,
                         List<String> oldTargetIds, List<String> newTargetIds);

    /**
     * Get all inverse relation fields for a model
     *
     * @param modelCode The model code
     * @return Map of fieldCode -> inverse field info
     */
    Map<String, InverseFieldInfo> getInverseFields(String modelCode);

    /**
     * Validate bidirectional relation configuration
     * Checks that both sides are properly configured
     *
     * @param modelCode The model code
     * @param fieldCode The field code
     * @return List of validation errors (empty if valid)
     */
    List<String> validateBidirectionalConfig(String modelCode, String fieldCode);

    /**
     * Info about an inverse relation field
     */
    record InverseFieldInfo(
        String targetModelCode,
        String targetFieldCode,
        String relationType,
        boolean isOwningSide
    ) {}
}
