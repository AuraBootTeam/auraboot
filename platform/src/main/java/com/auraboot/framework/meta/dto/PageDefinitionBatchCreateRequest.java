package com.auraboot.framework.meta.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 页面定义批量创建请求
 * 
 * @author AuraBoot Team
 * @since 1.0.0
 */
@Data
public class PageDefinitionBatchCreateRequest {
    
    /**
     * 页面定义创建请求列表
     */
    @NotEmpty(message = "页面定义列表不能为空")
    @Valid
    private List<PageDefinitionCreateRequest> pageDefinitions;
    
    /**
     * 是否跳过重复检查
     */
    private Boolean skipDuplicateCheck = false;
    
    /**
     * 批量操作描述
     */
    private String batchDescription;
}