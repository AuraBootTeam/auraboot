package com.auraboot.framework.view.service;

import com.auraboot.framework.view.dto.AutoSaveViewRequest;
import com.auraboot.framework.view.dto.SavedViewCreateRequest;
import com.auraboot.framework.view.dto.SavedViewDTO;
import com.auraboot.framework.view.dto.SavedViewUpdateRequest;

import java.util.List;

/**
 * SavedView Service Interface
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
public interface SavedViewService {

    /**
     * Create a new saved view
     *
     * @param request create request
     * @return created view DTO
     */
    SavedViewDTO create(SavedViewCreateRequest request);

    /**
     * Get view by PID
     *
     * @param pid view PID
     * @return view DTO or null if not found
     */
    SavedViewDTO findByPid(String pid);

    /**
     * Update an existing view
     *
     * @param pid view PID
     * @param request update request
     * @return updated view DTO
     */
    SavedViewDTO update(String pid, SavedViewUpdateRequest request);

    /**
     * Delete a view (soft delete)
     *
     * @param pid view PID
     */
    void delete(String pid);

    /**
     * Get all accessible views for current user
     * Includes personal, team, and global views
     *
     * @param modelCode model code
     * @param pageKey page key (optional)
     * @return list of accessible views
     */
    List<SavedViewDTO> getAccessibleViews(String modelCode, String pageKey);

    /**
     * Get personal views for current user
     *
     * @param modelCode model code
     * @param pageKey page key (optional)
     * @return list of personal views
     */
    List<SavedViewDTO> getPersonalViews(String modelCode, String pageKey);

    /**
     * Get global views
     *
     * @param modelCode model code
     * @param pageKey page key (optional)
     * @return list of global views
     */
    List<SavedViewDTO> getGlobalViews(String modelCode, String pageKey);

    /**
     * Get default view for current user
     *
     * @param modelCode model code
     * @param pageKey page key (optional)
     * @return default view DTO or null if none set
     */
    SavedViewDTO getDefaultView(String modelCode, String pageKey);

    /**
     * Set a view as default for current user
     *
     * @param pid view PID
     * @return updated view DTO
     */
    SavedViewDTO setAsDefault(String pid);

    /**
     * Duplicate a view
     *
     * @param pid source view PID
     * @param newName new view name
     * @return duplicated view DTO
     */
    SavedViewDTO duplicate(String pid, String newName);

    /**
     * Auto-save view configuration with atomic upsert.
     * Finds existing implicit view for current user/model/page and updates its config,
     * or creates a new implicit view if none exists.
     *
     * @param request auto-save request with modelCode, pageKey, viewConfig
     * @return saved view DTO
     */
    SavedViewDTO autoSave(AutoSaveViewRequest request);

    /**
     * Check if view name is unique for current user
     *
     * @param modelCode model code
     * @param pageKey page key (optional)
     * @param name view name
     * @param excludePid PID to exclude (for updates)
     * @return true if unique
     */
    boolean isNameUnique(String modelCode, String pageKey, String name, String excludePid);
}
