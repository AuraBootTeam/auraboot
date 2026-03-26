package com.auraboot.framework.meta.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 页面定义创建请求
 */
@Data
public class PageDefinitionCreateRequest   {
    
    /**
     * 页面键
     */
    @NotBlank(message = "页面键不能为空")
    private String code;
    
    /**
     * 页面定义
     */
    private Map<String, Object> definition;

}