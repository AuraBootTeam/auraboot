package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 页面定义响应DTO
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PageDefinitionResponse extends AbstractResponse {
    
    /**
     * 页面定义键
     */
    private String code;
    
    /**
     * 页面定义内容
     */
    private Map<String, Object> definition;
}