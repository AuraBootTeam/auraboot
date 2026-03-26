package com.auraboot.framework.consistency.service;

import com.auraboot.framework.consistency.dto.*;
import com.auraboot.framework.meta.dto.PaginationResult;

import java.util.List;

/**
 * Service for managing consistency rules and evaluating them.
 */
public interface ConsistencyRuleService {

    /**
     * List rules with optional source model filter.
     */
    PaginationResult<ConsistencyRuleResponse> listRules(String sourceModel, int page, int size);

    /**
     * Get a single rule by ID.
     */
    ConsistencyRuleResponse getRuleById(Long id);

    /**
     * Create a new consistency rule.
     */
    ConsistencyRuleResponse createRule(ConsistencyRuleRequest request);

    /**
     * Update an existing consistency rule.
     */
    ConsistencyRuleResponse updateRule(Long id, ConsistencyRuleRequest request);

    /**
     * Soft-delete a consistency rule.
     */
    boolean deleteRule(Long id);

    /**
     * Validate consistency for a specific record in a model.
     * Returns list of violations (empty if all rules pass).
     */
    List<ConsistencyViolation> validate(String modelCode, String recordId);

    /**
     * Validate consistency for a record and throw exception if violations found.
     * Used in command pipeline integration.
     */
    void validateAndThrow(String sourceModel, String linkFieldValue, Long tenantId);

    /**
     * Validate consistency for multiple records in a model.
     * Returns all violations across the specified records.
     */
    List<ConsistencyViolation> validateBatch(String modelCode, List<String> recordIds);

    /**
     * Validate consistency using payload data from command pipeline.
     * Used for pre-save validation when no recordId is available yet.
     */
    List<ConsistencyViolation> validateForPipeline(String modelCode, java.util.Map<String, Object> payload,
            java.util.Map<String, Object> fieldMapResults, Long tenantId);
}
