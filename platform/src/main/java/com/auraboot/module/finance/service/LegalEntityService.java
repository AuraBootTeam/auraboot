package com.auraboot.module.finance.service;

import com.auraboot.module.finance.dto.LegalEntityCreateRequest;
import com.auraboot.module.finance.dto.LegalEntityTree;
import com.auraboot.module.finance.entity.LegalEntity;

import java.util.List;

/**
 * Service for managing legal entities within a tenant (group/conglomerate).
 *
 * <p>A legal entity represents a company or subsidiary in the group structure.
 * The hierarchy of entities is used to drive consolidated financial reporting.
 */
public interface LegalEntityService {

    /**
     * Create a new legal entity for the current tenant.
     *
     * @param req creation request
     * @return the persisted entity
     * @throws IllegalArgumentException if {@code entityCode} is already taken within the tenant
     */
    LegalEntity create(LegalEntityCreateRequest req);

    /**
     * Update an existing legal entity.
     *
     * @param id  entity id
     * @param req update data
     * @return the updated entity
     * @throws jakarta.persistence.EntityNotFoundException if the entity does not exist in the current tenant
     */
    LegalEntity update(Long id, LegalEntityCreateRequest req);

    /**
     * Return all legal entities for the current tenant, ordered by entity code.
     */
    List<LegalEntity> findAll(Long tenantId);

    /**
     * Find a legal entity by its id.
     *
     * @throws jakarta.persistence.EntityNotFoundException if not found or belongs to a different tenant
     */
    LegalEntity findById(Long id);

    /**
     * Delete a legal entity.
     * Entities with children cannot be deleted; remove children first.
     *
     * @throws IllegalStateException if the entity has child entities
     */
    void delete(Long id);

    /**
     * Build a hierarchical tree of all legal entities for the given tenant,
     * starting from root entities (those with {@code parentId == null}).
     *
     * @param tenantId tenant id
     * @return list of root nodes; each node may have nested children
     */
    List<LegalEntityTree> buildHierarchy(Long tenantId);
}
