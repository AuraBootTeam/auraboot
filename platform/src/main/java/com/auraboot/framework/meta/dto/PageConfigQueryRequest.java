package com.auraboot.framework.meta.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 页面配置查询请求
 */

@Data
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode(callSuper=false)
public class PageConfigQueryRequest extends PaginationRequest {
    
    private Long tenantId;
    
    private Long pageDefinitionId;
    
    private String configType;
    
    private String configKey;
    
    private String keyword;
    

}