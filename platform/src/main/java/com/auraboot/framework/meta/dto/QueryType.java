package com.auraboot.framework.meta.dto;

/**
 * 查询类型枚举
 * 
 * @author AuraBoot Team
 * @since 2.0.0
 */
public enum QueryType {
    
    /**
     * 查询所有记录
     */
    SELECT_ALL,
    
    /**
     * 根据ID查询
     */
    SELECT_BY_ID,
    
    /**
     * 分页查询
     */
    SELECT_PAGE,
    
    /**
     * 条件查询
     */
    SELECT_BY_CONDITION,
    
    /**
     * 统计查询
     */
    SELECT_COUNT,
    
    /**
     * 聚合查询
     */
    SELECT_AGGREGATE,
    
    /**
     * 关联查询
     */
    SELECT_WITH_RELATIONS,
    
    /**
     * 插入查询
     */
    INSERT,
    
    /**
     * 更新查询
     */
    UPDATE,
    
    /**
     * 删除查询
     */
    DELETE
}