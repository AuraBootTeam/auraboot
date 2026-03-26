package com.auraboot.framework.base.dao;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 基础Mapper接口
 * @param <T> 实体类型
 */
public interface BaseMapper<T> {
    
    /**
     * 插入记录
     * @param entity 实体对象
     * @return 影响行数
     */
    int insert(T entity);
    
    /**
     * 根据ID删除记录
     * @param id 主键ID
     * @return 影响行数
     */
    int deleteById(@Param("id") Long id);
    
    /**
     * 根据ID更新记录
     * @param entity 实体对象
     * @return 影响行数
     */
    int updateById(T entity);
    
    /**
     * 根据ID查询记录
     * @param id 主键ID
     * @return 实体对象
     */
    T selectById(@Param("id") Long id);
    
    /**
     * 查询所有记录
     * @return 实体列表
     */
    List<T> selectAll();
    
    /**
     * 根据条件查询记录列表
     * @param params 查询参数
     * @return 实体列表
     */
    List<T> selectByConditions(@Param("params") Map<String, Object> params);
    
    /**
     * 根据条件统计记录数
     * @param params 查询参数
     * @return 记录数
     */
    int countByConditions(@Param("params") Map<String, Object> params);
    
    /**
     * 批量插入记录
     * @param entities 实体列表
     * @return 影响行数
     */
    int batchInsert(@Param("entities") List<T> entities);
    
    /**
     * 批量删除记录
     * @param ids ID列表
     * @return 影响行数
     */
    int batchDeleteByIds(@Param("ids") List<Long> ids);
    
    /**
     * 批量更新记录
     * @param entities 实体列表
     * @return 影响行数
     */
    int batchUpdate(@Param("entities") List<T> entities);
}