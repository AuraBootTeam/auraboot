package com.auraboot.framework.permission.service;

import com.auraboot.framework.permission.dto.*;
import java.util.List;
import java.util.Map;

/**
 * SubjectPermission Service Interface (V4)
 * 
 * Provides business logic for Subject-Permission declarations.
 * 
 * Key Features:
 * - Unified Subject abstraction (MENU, PAGE, BUTTON, QUERY, WORKFLOW)
 * - Logic group management (AND/OR)
 * - Visibility evaluation
 * - Tenant isolation
 * 
 * @author AuraBoot Platform
 * @version 4.0.0
 * @since 2025-01-07
 */
public interface SubjectPermissionService {
    
    /**
     * Add permission declaration to subject
     * 
     * @param request Create request
     * @return Created declaration DTO
     */
    SubjectPermissionDTO addPermission(SubjectPermissionCreateRequest request);
    
    /**
     * Batch add permission declarations
     * 
     * @param subjectType Subject type
     * @param subjectId Subject ID
     * @param requests List of create requests
     * @return List of created declarations
     */
    List<SubjectPermissionDTO> batchAddPermissions(
        String subjectType,
        Long subjectId,
        List<SubjectPermissionCreateRequest> requests
    );
    
    /**
     * Remove permission declaration
     * 
     * @param id Declaration ID
     */
    void removePermission(Long id);
    
    /**
     * Remove all declarations for a subject
     * 
     * @param subjectType Subject type
     * @param subjectId Subject ID
     */
    void removeAllPermissions(String subjectType, Long subjectId);
    
    /**
     * Find all declarations for a subject
     * 
     * @param subjectType Subject type
     * @param subjectId Subject ID
     * @return List of declarations
     */
    List<SubjectPermissionDTO> findBySubject(String subjectType, Long subjectId);
    
    /**
     * Find declarations by subject code
     * 
     * @param subjectType Subject type
     * @param subjectCode Subject code
     * @return List of declarations
     */
    List<SubjectPermissionDTO> findBySubjectCode(String subjectType, String subjectCode);
    
    /**
     * Evaluate subject visibility for user
     * 
     * @param subjectType Subject type
     * @param subjectId Subject ID
     * @param userId User ID
     * @return true if visible, false otherwise
     */
    boolean evaluateVisibility(String subjectType, Long subjectId, Long userId);
    
    /**
     * Batch evaluate visibility for multiple subjects
     * 
     * @param subjectType Subject type
     * @param subjectIds List of subject IDs
     * @param userId User ID
     * @return Map of subject ID to visibility result
     */
    Map<Long, Boolean> batchEvaluateVisibility(
        String subjectType,
        List<Long> subjectIds,
        Long userId
    );
    
    /**
     * Validate logic group consistency
     * 
     * @param subjectType Subject type
     * @param subjectId Subject ID
     * @param logicGroup Logic group number
     * @return true if consistent, false otherwise
     */
    boolean validateLogicGroupConsistency(
        String subjectType,
        Long subjectId,
        Integer logicGroup
    );
    
    /**
     * Evict subject evaluation cache
     * 
     * @param subjectType Subject type
     * @param subjectId Subject ID
     */
    void evictSubjectEvaluations(String subjectType, Long subjectId);
}
