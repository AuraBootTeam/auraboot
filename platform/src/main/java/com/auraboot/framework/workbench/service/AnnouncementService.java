package com.auraboot.framework.workbench.service;

import com.auraboot.framework.workbench.dto.AnnouncementDTO;
import com.auraboot.framework.workbench.dto.AnnouncementRequest;

import java.util.List;

/**
 * Service for workbench announcements.
 *
 * @since 6.5.0
 */
public interface AnnouncementService {

    /**
     * List active announcements for current tenant.
     */
    List<AnnouncementDTO> listActive(int limit);

    /**
     * Create a new announcement.
     */
    AnnouncementDTO create(AnnouncementRequest request);

    /**
     * Update an existing announcement.
     */
    AnnouncementDTO update(Long id, AnnouncementRequest request);

    /**
     * Soft-delete an announcement.
     */
    void delete(Long id);
}
