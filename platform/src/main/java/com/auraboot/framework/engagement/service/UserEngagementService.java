package com.auraboot.framework.engagement.service;

import com.auraboot.framework.engagement.dto.UserEngagementDTO;

import java.util.List;

public interface UserEngagementService {

    /**
     * List engagement records for a user, filtered by engagement type and optionally target type.
     *
     * @param userId         the current user ID
     * @param tenantId       the current tenant ID
     * @param engagementType required filter: favorite, recent, pinned
     * @param targetType     optional filter: menu, record, page
     * @return ordered list of engagement DTOs
     */
    List<UserEngagementDTO> list(Long userId, Long tenantId, String engagementType, String targetType);

    /**
     * Add or update an engagement record.
     * Identified by the composite key: userId + tenantId + targetType + targetId + engagementType.
     * For recent_view type, enforces a max of 20 records per user per tenant (oldest pruned).
     *
     * @param userId   the current user ID
     * @param tenantId the current tenant ID
     * @param dto      engagement data
     * @return the persisted DTO (with id and createdAt)
     */
    UserEngagementDTO upsert(Long userId, Long tenantId, UserEngagementDTO dto);

    /**
     * Delete an engagement record by ID.
     * Verifies ownership: only the owning user may delete.
     *
     * @param id     the record ID
     * @param userId the current user ID (for ownership check)
     */
    void delete(Long id, Long userId);

    /**
     * Reorder favorites or pinned items for the current user.
     * Sets sortOrder to the index of each ID in the provided list.
     *
     * @param userId     the current user ID
     * @param orderedIds IDs in the desired display order
     */
    void reorder(Long userId, List<Long> orderedIds);
}
