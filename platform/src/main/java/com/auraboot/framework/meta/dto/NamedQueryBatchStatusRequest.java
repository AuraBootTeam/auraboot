package com.auraboot.framework.meta.dto;

import lombok.Data;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;

/**
 * 命名查询批量状态更新请求DTO
 * 
 * @author AuraBoot
 * @since 2024-12-24
 */
@Data
public class NamedQueryBatchStatusRequest {

    /**
     * 查询PID列表
     */
    @NotEmpty(message = "查询PID列表不能为空")
    private List<String> pids;

    /**
     * 目标状态
     */
    @NotNull(message = "目标状态不能为空")
    @Pattern(regexp = "^(?i)(draft|testing|published|deprecated|archived)$", message = "目标状态必须是draft、testing、published、deprecated或archived")
    private String targetStatus;

    /**
     * 操作原因
     */
    private String reason;

    /**
     * 是否强制更新
     */
    private Boolean forceUpdate = false;

    /**
     * 是否跳过权限检查
     */
    private Boolean skipPermissionCheck = false;

    /**
     * 操作备注
     */
    private String notes;
}