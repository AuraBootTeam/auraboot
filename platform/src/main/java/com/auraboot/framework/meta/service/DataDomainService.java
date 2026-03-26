package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.entity.DataDomain;
import com.auraboot.framework.meta.entity.UserDataDomain;

import java.util.List;
import java.util.Set;

/**
 * Service for data domain management and domain-based data isolation.
 *
 * <p>Data domains represent business units / subsidiaries / factories within
 * the same tenant. When domain isolation is enabled on a model, data is
 * filtered by the user's assigned domain(s).
 *
 * @since 5.2.0
 */
public interface DataDomainService {

    // ==================== Domain CRUD ====================

    List<DataDomain> listDomains();

    DataDomain getDomain(Long id);

    DataDomain getDomainByCode(String domainCode);

    DataDomain createDomain(DataDomain domain);

    DataDomain updateDomain(Long id, DataDomain domain);

    void deleteDomain(Long id);

    /**
     * Get child domains of a parent domain.
     */
    List<DataDomain> getChildren(Long parentDomainId);

    // ==================== User-Domain Bindings ====================

    /**
     * Assign a user to a domain.
     */
    UserDataDomain assignUser(Long domainId, Long userId, boolean isPrimary);

    /**
     * Remove a user from a domain.
     */
    void removeUser(Long domainId, Long userId);

    /**
     * Get all domains assigned to a user.
     */
    List<DataDomain> getUserDomains(Long userId);

    /**
     * Get all domain IDs assigned to a user (including descendant domains).
     * This is the set used for data filtering.
     */
    Set<Long> getUserDomainIdsWithDescendants(Long userId);

    /**
     * Get all user IDs assigned to a domain.
     */
    List<Long> getDomainUserIds(Long domainId);

    // ==================== Domain Filtering ====================

    /**
     * Build SQL WHERE clause fragment for domain isolation.
     * Returns empty string if the user has no domain restrictions or domain isolation
     * is not enabled for the model.
     *
     * @param modelCode model code
     * @param userId    current user ID
     * @return SQL fragment like "AND domain_id IN (1, 2, 3)" or empty string
     */
    String buildDomainFilter(String modelCode, Long userId);

    /**
     * Filter records post-query by domain.
     */
    List<java.util.Map<String, Object>> filterByDomain(String modelCode, Long userId,
                                                        List<java.util.Map<String, Object>> records);

    /**
     * Evict domain caches.
     */
    void evictCache();
}
