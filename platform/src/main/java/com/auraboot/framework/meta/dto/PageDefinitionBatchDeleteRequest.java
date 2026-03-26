package com.auraboot.framework.meta.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 页面定义批量删除请求
 * 
 * @author AuraBoot Team
 * @since 1.0.0
 */
@Data
public class PageDefinitionBatchDeleteRequest {
    
    /**
     * 页面定义业务主键列表
     */
    @NotEmpty(message = "页面定义ID列表不能为空")
    private List<String> pids;
    
    /**
     * 是否强制删除（忽略依赖检查）
     */
    private Boolean forceDelete = false;
    
    /**
     * 删除原因
     */
    private String deleteReason;
}