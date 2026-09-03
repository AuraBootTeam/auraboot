package com.auraboot.framework.dashboard.service;

import com.auraboot.framework.dashboard.dto.DashboardModuleCreateRequest;
import com.auraboot.framework.dashboard.dto.DashboardModuleDTO;
import com.auraboot.framework.dashboard.dto.DashboardModuleMoveRequest;
import com.auraboot.framework.dashboard.dto.DashboardModuleRenameRequest;

import java.util.List;

/**
 * Dashboard module (folder tree) service.
 *
 * @author AuraBoot Team
 * @since 4.2.0
 */
public interface DashboardModuleService {

    /**
     * Create a folder, optionally under a parent folder.
     */
    DashboardModuleDTO create(DashboardModuleCreateRequest request);

    /**
     * Rename a folder.
     */
    DashboardModuleDTO rename(String pid, DashboardModuleRenameRequest request);

    /**
     * Soft-delete a folder. Refuses when the folder still has child folders
     * or assigned dashboards.
     */
    void delete(String pid);

    /**
     * Full folder tree for the current tenant, with per-folder dashboard
     * counts.
     */
    List<DashboardModuleDTO> tree();

    /**
     * Per-folder dashboard counts for the current tenant, keyed by folder PID.
     */
    List<DashboardModuleDTO> moduleCounts();

    /**
     * Move a folder under another parent (or to the root when the target is
     * blank). Rejects moving a folder under itself or one of its descendants.
     */
    DashboardModuleDTO move(String pid, DashboardModuleMoveRequest request);
}
