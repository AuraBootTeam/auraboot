package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 数据源更新请求
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DataSourceUpdateRequest extends AbstractUpdateRequest {
    
    /**
     * 数据源键（映射到name字段）
     */
    private String code;
    
    /**
     * 数据源类型
     */
    private String type;
    
    /**
     * 数据项配置
     */
    private Map<String, Object> items;
}