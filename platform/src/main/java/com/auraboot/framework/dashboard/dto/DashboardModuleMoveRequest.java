package com.auraboot.framework.dashboard.dto;

import lombok.Data;

/**
 * Request to move a dashboard module (folder) under another parent. A null or
 * blank {@code targetParentPid} moves the folder to the tree root.
 *
 * @author AuraBoot Team
 * @since 4.2.0
 */
@Data
public class DashboardModuleMoveRequest {

    /**
     * PID of the new parent folder; null/blank moves the folder to the root.
     */
    private String targetParentPid;
}
