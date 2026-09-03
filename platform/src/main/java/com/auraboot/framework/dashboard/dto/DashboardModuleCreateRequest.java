package com.auraboot.framework.dashboard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request to create a dashboard module (folder). An absent or blank
 * {@code parentPid} creates a root-level folder.
 *
 * @author AuraBoot Team
 * @since 4.2.0
 */
@Data
public class DashboardModuleCreateRequest {

    @NotBlank(message = "Folder name is required")
    @Size(max = 200, message = "Folder name must be at most 200 characters")
    private String name;

    /**
     * PID of the parent folder; null/blank creates a root folder.
     */
    private String parentPid;
}
