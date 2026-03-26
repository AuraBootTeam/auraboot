package com.auraboot.framework.meta.service;

import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.meta.dto.FieldRecommendation;
import com.auraboot.framework.meta.dto.FieldSearchRequest;
import com.auraboot.framework.meta.dto.MetaFieldDTO;

import java.util.List;
import java.util.Map;

/**
 * Field library service interface
 * Provides field library management and advanced query capabilities
 * 
 * @author AuraBoot Team
 * @since 2.2.0
 */
public interface FieldLibraryService {

    /**
     * List all fields grouped by semantic type
     * Groups fields by their semantic type for library view
     * 
     * @return Map of semantic type to field list
     */
    Map<String, List<MetaFieldDTO>> listFieldsBySemanticType();

    /**
     * Search fields with advanced filters
     * Supports multi-dimensional filtering including usage count
     * 
     * @param request Search request with filters
     * @return Paginated field results
     */
    PageResult<MetaFieldDTO> searchFields(FieldSearchRequest request);

    /**
     * Get field recommendations for model binding
     * Recommends fields based on semantic type similarity and usage frequency
     * 
     * @param modelPid Model PID
     * @param semanticType Semantic type filter (optional)
     * @return List of recommended fields with relevance scores
     */
    List<FieldRecommendation> getFieldRecommendations(String modelPid, String semanticType);

    /**
     * Get system fields
     * Returns predefined system fields (id, created_at, updated_at, tenant_id, deleted)
     * 
     * @return List of system fields
     */
    List<MetaFieldDTO> getSystemFields();

    /**
     * Get commonly used business fields
     * Returns fields with high usage count
     * 
     * @return List of common business fields
     */
    List<MetaFieldDTO> getCommonBusinessFields();

    /**
     * Get unused fields
     * Returns fields with zero usage count
     * 
     * @return List of unused fields
     */
    List<MetaFieldDTO> getUnusedFields();
}
