package com.auraboot.framework.meta.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

/**
 * 动态更新请求DTO
 * 
 * @author AuraBoot Framework
 * @since 1.0.0
 */
@Data
public class DynamicUpdateRequest {
    
    /**
     * 页面ID
     */
    @NotBlank(message = "页面ID不能为空")
    private String pageId;
    
    /**
     * 数据内容
     */
    @NotNull(message = "数据内容不能为空")
    private Map<String, Object> data;
    
    /**
     * 是否验证数据
     */
    private Boolean validate = true;
    
    /**
     * 扩展属性
     */
    private Map<String, Object> metadata;
}