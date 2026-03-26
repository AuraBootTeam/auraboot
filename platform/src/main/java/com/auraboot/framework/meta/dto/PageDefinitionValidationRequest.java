package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 页面定义验证请求
 */

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PageDefinitionValidationRequest {
    
    private Long id;
    
    private Long tenantId;
    
    private String name;
    
    private String pageType;
    
    private Map<String, Object> pageConfig;
    
    private Map<String, Object> layoutConfig;
    
    private Map<String, Object> componentConfig;
    
    private Map<String, Object> dataSourceConfig;
    
    private String validationType;
    

}