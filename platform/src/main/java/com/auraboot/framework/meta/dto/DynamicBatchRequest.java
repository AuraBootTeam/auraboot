package com.auraboot.framework.meta.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 动态批量操作请求DTO
 * 
 * @author AuraBoot Framework
 * @since 1.0.0
 */
@Data
public class DynamicBatchRequest {
    
    /**
     * 页面ID
     */
    @NotBlank(message = "页面ID不能为空")
    private String pageId;
    
    /**
     * 批量数据列表
     */
    @NotEmpty(message = "批量数据不能为空")
    private List<Map<String, Object>> dataList;
    
    /**
     * 是否验证数据
     */
    private Boolean validate = true;
    
    /**
     * 是否忽略错误继续处理
     */
    private Boolean ignoreErrors = false;
    
    /**
     * 扩展属性
     */
    private Map<String, Object> metadata;
}