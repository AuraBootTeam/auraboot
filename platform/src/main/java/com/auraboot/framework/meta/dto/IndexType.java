package com.auraboot.framework.meta.dto;

/**
 * 索引类型枚举
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
public enum IndexType {
    
    /**
     * 普通索引
     */
    NORMAL,
    
    /**
     * 唯一索引
     */
    UNIQUE,
    
    /**
     * 主键索引
     */
    PRIMARY,
    
    /**
     * 全文索引
     */
    FULLTEXT,
    
    /**
     * 空间索引
     */
    SPATIAL,
    
    /**
     * 复合索引
     */
    COMPOSITE
}