package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 页面定义更新请求
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PageDefinitionUpdateRequest extends AbstractUpdateRequest {
    
    /**
     * 页面键
     */
    private String code;
    
    /**
     * 页面定义
     */
    private Map<String, Object> definition;
}