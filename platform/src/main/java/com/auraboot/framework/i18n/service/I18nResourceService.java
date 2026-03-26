package com.auraboot.framework.i18n.service;

import com.auraboot.framework.i18n.entity.I18nResource;
import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;
import java.util.Map;

/**
 * I18n Resource Service Interface
 *
 * @author AuraBoot
 */
public interface I18nResourceService {

    // ==================== Basic CRUD ====================

    /**
     * Create a new i18n resource
     */
    I18nResource create(I18nResource resource);

    /**
     * Update an existing i18n resource
     */
    I18nResource update(String pid, I18nResource resource);

    /**
     * Delete an i18n resource by PID
     */
    void delete(String pid);

    /**
     * Find by PID
     */
    I18nResource findByPid(String pid);

    /**
     * Find by key and language
     */
    I18nResource findByKeyAndLang(String key, String lang);

    // ==================== Batch Operations ====================

    /**
     * Upsert a resource (insert or update on conflict)
     */
    I18nResource upsert(I18nResource resource);

    /**
     * Batch upsert resources
     */
    int batchUpsert(List<I18nResource> resources);

    /**
     * Batch insert resources (skip on conflict)
     */
    int batchInsert(List<I18nResource> resources);

    // ==================== Query Operations ====================

    /**
     * Get all resources for a language (for compilation)
     */
    List<I18nResource> findAllByLang(String lang);

    /**
     * Get resources by key prefix (scope query)
     */
    List<I18nResource> findByKeyPrefix(String lang, String prefix);

    /**
     * Get resources by source
     */
    List<I18nResource> findBySource(String source);

    /**
     * Get resources by reference (model/field/page derived keys)
     */
    List<I18nResource> findByRef(String refType, Long refId);

    /**
     * Paginated query with filters
     */
    IPage<I18nResource> findPage(int pageNum, int pageSize, String lang, String source, String status, String keyPrefix, String keyword);

    // ==================== Compilation ====================

    /**
     * Get all resources for a language as a flat map (for JSON compilation)
     * @return Map<key, value>
     */
    Map<String, String> getResourceMapByLang(String lang);

    /**
     * Get resources for a language as a nested map (for JSON compilation)
     * @return Nested map following key structure
     */
    Map<String, Object> getNestedResourceMapByLang(String lang);

    // ==================== Statistics ====================

    /**
     * Get all distinct languages
     */
    List<String> getDistinctLangs();

    /**
     * Count resources by language
     */
    Map<String, Long> countByLang();

    /**
     * Count resources by source
     */
    Map<String, Long> countBySource();

    // ==================== Model Integration ====================

    /**
     * Generate i18n keys from model/field displayName
     * Called when model/field is created or updated
     */
    void syncFromModel(Long modelId, String modelCode, String displayName);

    /**
     * Generate i18n keys from field
     * Called when field is created or updated
     */
    void syncFromField(Long fieldId, String modelCode, String fieldCode, String displayName, String placeholder, String description);

    /**
     * Delete i18n keys when model/field is deleted
     */
    void deleteByRef(String refType, Long refId);

    // ==================== Workflow Operations ====================

    /**
     * Submit a DRAFT translation for review (DRAFT → REVIEW).
     * Throws BusinessException if current status is not DRAFT.
     */
    I18nResource submitReview(String pid);

    /**
     * Approve a translation under review (REVIEW → APPROVED).
     * Throws BusinessException if current status is not REVIEW.
     */
    I18nResource approve(String pid);

    /**
     * Reject a translation under review, reverting to DRAFT (REVIEW → DRAFT).
     * Throws BusinessException if current status is not REVIEW.
     *
     * @param pid    Resource PID
     * @param reason Human-readable rejection reason (required)
     */
    I18nResource reject(String pid, String reason);

    /**
     * Update resource status directly (admin convenience method).
     */
    I18nResource updateStatus(String pid, String newStatus);
}
