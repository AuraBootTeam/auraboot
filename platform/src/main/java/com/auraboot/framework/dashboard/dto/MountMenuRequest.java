package com.auraboot.framework.dashboard.dto;

import lombok.Data;

/**
 * Request DTO for mounting a dashboard to sidebar menu
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
public class MountMenuRequest {

    /**
     * Parent menu code (directory) to mount under
     */
    private String parentCode;

    /**
     * Menu icon name (defaults to bar-chart)
     */
    private String icon = "bar-chart";

    /**
     * Menu sort order (defaults to 50)
     */
    private Integer orderNo = 50;
}
