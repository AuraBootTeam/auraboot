package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 页面定义查询请求DTO
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PageDefinitionQueryRequest extends AbstractQueryRequest {
    
    /**
     * 页面键
     */
    private String code;
}