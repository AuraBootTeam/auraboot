package com.auraboot.framework.tenant.dao.mapper;

import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 租户数据访问层
 */
@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {

    /**
     * 根据租户名称查询租户
     */
    @Select("SELECT * FROM ab_tenant WHERE name = #{name} AND deleted_flag = FALSE")
    Tenant findByName(@Param("name") String name);

    /**
     * 根据状态查询租户列表
     */
    @Select("SELECT * FROM ab_tenant WHERE status = #{status}   ORDER BY created_at DESC")
    List<Tenant> findByStatus(@Param("status") String status);

    /**
     * 统计租户数量
     */
    @Select("SELECT COUNT(*) FROM ab_tenant WHERE status = #{status}  ")
    long countByStatus(@Param("status") String status);
}