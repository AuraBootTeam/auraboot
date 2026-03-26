package com.auraboot.framework.meta.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 实体版本DTO
 */
@Data
@Schema(description = "实体版本DTO")
public class EntityVersionDTO {

    @Schema(description = "版本PID")
    private String pid;

    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "实体PID")
    private String entityPid;

    @Schema(description = "版本号")
    private String version;

    @Schema(description = "版本说明")
    private String versionNote;

    @Schema(description = "版本状态")
    private String status;

    @Schema(description = "是否为当前版本")
    private Boolean isCurrent;

    @Schema(description = "版本配置")
    private Map<String, Object> versionConfig;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;

    @Schema(description = "更新时间")
    private LocalDateTime updatedAt;

    @Schema(description = "创建人")
    private String createdBy;

    @Schema(description = "更新人")
    private String updatedBy;
}