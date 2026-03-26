package com.auraboot.framework.meta.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * 索引信息
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
@Data
@Builder
public class IndexInfo {
    
    /**
     * 索引名称
     */
    private String indexName;
    
    /**
     * 索引类型
     */
    private String indexType;
    
    /**
     * 是否唯一
     */
    private Boolean unique;
    
    /**
     * 索引字段列表
     */
    private List<String> columns;
    
    /**
     * 索引注释
     */
    private String comment;
    
    /**
     * 索引大小（字节）
     */
    private Long indexSize;
    
    /**
     * 基数（唯一值数量）
     */
    private Long cardinality;
}