package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 数据源查询请求
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DataSourceQueryRequest extends AbstractQueryRequest {
    
    /**
     * 数据源键
     */
    private String code;
    
    /**
     * 数据源类型
     */
    private String type;
    
    /**
     * 标签
     */
    private String tags;
    
    /**
     * 关键字
     */
    private String keyword;
}