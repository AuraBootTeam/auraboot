package com.auraboot.framework.meta.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 页面定义批量更新请求
 * 
 * @author AuraBoot Team
 * @since 1.0.0
 */
@Data
public class PageDefinitionBatchUpdateRequest {
    
    /**
     * 批量更新项列表
     */
    @NotEmpty(message = "更新项列表不能为空")
    @Valid
    private List<BatchUpdateItem> updates;
    
    /**
     * 是否跳过验证
     */
    private Boolean skipValidation = false;
    
    /**
     * 批量操作描述
     */
    private String batchDescription;
    
    /**
     * 批量更新项
     */
    @Data
    public static class BatchUpdateItem {
        
        /**
         * 页面定义业务主键
         */
        private String pid;
        
        /**
         * 更新数据
         */
        @Valid
        private PageDefinitionUpdateRequest updateData;
    }
}