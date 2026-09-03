package com.auraboot.framework.dashboard.dto;

import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * Dashboard module (folder) DTO with nested tree children.
 *
 * @author AuraBoot Team
 * @since 4.2.0
 */
@Data
public class DashboardModuleDTO {

    private String pid;

    private String name;

    private String parentPid;

    private Integer sortOrder;

    private Instant createdAt;

    private Instant updatedAt;

    /**
     * Number of dashboards assigned to this folder.
     */
    private long dashboardCount;

    private List<DashboardModuleDTO> children;
}
