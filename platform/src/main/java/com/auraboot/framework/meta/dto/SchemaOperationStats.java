package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 模式操作统计
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class SchemaOperationStats {
    
    /**
     * 执行的DDL语句数量
     */
    private Integer ddlCount;
    
    /**
     * 创建的表数量
     */
    private Integer tablesCreated;
    
    /**
     * 修改的表数量
     */
    private Integer tablesModified;
    
    /**
     * 删除的表数量
     */
    private Integer tablesDropped;
    
    /**
     * 添加的字段数量
     */
    private Integer fieldsAdded;
    
    /**
     * 修改的字段数量
     */
    private Integer fieldsModified;
    
    /**
     * 删除的字段数量
     */
    private Integer fieldsDropped;
    
    /**
     * 创建的索引数量
     */
    private Integer indexesCreated;
    
    /**
     * 删除的索引数量
     */
    private Integer indexesDropped;
    
    /**
     * 执行耗时（毫秒）
     */
    private Long executionTime;
}