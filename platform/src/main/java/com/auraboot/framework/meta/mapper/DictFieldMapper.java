package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.Field;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 字段定义Mapper接口
 * 对应表：ab_meta_field
 */
@Mapper
public interface DictFieldMapper extends BaseMapper<Field> {

    /**
     * 根据业务主键查询字段定义
     * @param pid 业务主键
     * @return 字段定义
     */
    @Select("SELECT * FROM ab_meta_field WHERE pid = #{pid} AND deleted_flag = false")
    Field findByPid(@Param("pid") String pid);

    /**
     * 查询指定租户下的所有字段定义
     * @param tenantId 租户ID
     * @return 字段定义列表
     */
    @Select("SELECT * FROM ab_meta_field WHERE  deleted_flag = false ORDER BY created_at DESC")
    List<Field> findByTenantId(@Param("tenantId") Long tenantId);



}
