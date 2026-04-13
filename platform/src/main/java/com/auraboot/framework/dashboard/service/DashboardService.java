package com.auraboot.framework.dashboard.service;

import com.auraboot.framework.dashboard.dto.*;

import java.util.List;
import java.util.Map;

/**
 * Dashboard Service Interface
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
public interface DashboardService {

    /**
     * Create a new dashboard
     *
     * @param request create request
     * @return created dashboard DTO
     */
    DashboardDTO create(DashboardCreateRequest request);

    /**
     * Get dashboard by PID
     *
     * @param pid dashboard PID
     * @return dashboard DTO or null if not found
     */
    DashboardDTO findByPid(String pid);

    /**
     * Get dashboard by code within current tenant
     *
     * @param code dashboard code
     * @return dashboard DTO or null if not found
     */
    DashboardDTO findByCode(String code);

    /**
     * Update an existing dashboard
     *
     * @param pid dashboard PID
     * @param request update request
     * @return updated dashboard DTO
     */
    DashboardDTO update(String pid, DashboardUpdateRequest request);

    /**
     * Delete a dashboard (soft delete)
     *
     * @param pid dashboard PID
     */
    void delete(String pid);

    /**
     * Get all accessible dashboards for current user
     * Includes personal, team, and global dashboards
     *
     * @param request query request
     * @return list of accessible dashboards
     */
    List<DashboardDTO> getAccessibleDashboards(DashboardQueryRequest request);

    /**
     * Get personal dashboards for current user
     *
     * @return list of personal dashboards
     */
    List<DashboardDTO> getPersonalDashboards();

    /**
     * Get global dashboards
     *
     * @return list of global dashboards
     */
    List<DashboardDTO> getGlobalDashboards();

    /**
     * Get default dashboard for current user
     *
     * @return default dashboard DTO or null if none set
     */
    DashboardDTO getDefaultDashboard();

    /**
     * Set a dashboard as default for current user
     *
     * @param pid dashboard PID
     * @return updated dashboard DTO
     */
    DashboardDTO setAsDefault(String pid);

    /**
     * Publish a dashboard
     *
     * @param pid dashboard PID
     * @return updated dashboard DTO
     */
    DashboardDTO publish(String pid);

    /**
     * Unpublish a dashboard
     *
     * @param pid dashboard PID
     * @return updated dashboard DTO
     */
    DashboardDTO unpublish(String pid);

    /**
     * Duplicate a dashboard
     *
     * @param pid source dashboard PID
     * @param newTitle new dashboard title
     * @return duplicated dashboard DTO
     */
    DashboardDTO duplicate(String pid, String newTitle);

    /**
     * Check if dashboard code is unique within current tenant
     *
     * @param code dashboard code
     * @param excludePid PID to exclude (for updates)
     * @return true if unique
     */
    boolean isCodeUnique(String code, String excludePid);

    /**
     * Get or create the personal workbench for the current user.
     * If no workbench exists, creates one from the default template.
     *
     * @return workbench dashboard DTO (always non-null)
     */
    DashboardDTO getOrCreateWorkbench();

    /**
     * Mount a dashboard to sidebar menu
     *
     * @param dashboardPid dashboard PID
     * @param request mount request with parent code, icon, and order
     */
    void mountToMenu(String dashboardPid, MountMenuRequest request);

    /**
     * Unmount a dashboard from sidebar menu
     *
     * @param dashboardPid dashboard PID
     */
    void unmountFromMenu(String dashboardPid);
}
