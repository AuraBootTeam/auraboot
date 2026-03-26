package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * 索引定义
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class IndexDefinition {
    
    /**
     * 索引名称
     */
    private String name;
    
    /**
     * 索引类型
     */
    private IndexType type;
    
    /**
     * 索引字段列表
     */
    private List<String> fields;
    
    /**
     * 是否唯一索引
     */
    @Builder.Default
    private Boolean unique = false;
    
    /**
     * 描述
     */
    private String description;
    
    public enum IndexType {
        NORMAL,     // 普通索引
        UNIQUE,     // 唯一索引
        PRIMARY,    // 主键索引
        FULLTEXT,   // 全文索引
        SPATIAL     // 空间索引
    }
}