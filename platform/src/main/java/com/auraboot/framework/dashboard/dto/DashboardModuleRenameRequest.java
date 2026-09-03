package com.auraboot.framework.dashboard.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Request to rename a dashboard module (folder).
 *
 * @author AuraBoot Team
 * @since 4.2.0
 */
@Data
public class DashboardModuleRenameRequest {

    @NotBlank(message = "Folder name is required")
    @Size(max = 200, message = "Folder name must be at most 200 characters")
    private String name;
}
