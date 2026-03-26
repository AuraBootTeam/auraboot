package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 页面配置响应
 */

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PageConfigResponse {
    
    private Long id;
    
    private Long tenantId;
    
    private Long pageDefinitionId;
    
    private String configType;
    
    private String configKey;
    
    private Map<String, Object> configValue;
    
    private String description;
    
    private Boolean enabled;
    
    private Integer sortOrder;
    
    private LocalDateTime createdAt;
    
    private LocalDateTime updatedAt;
    
    private String createdBy;
    
    private String updatedBy;
    

}