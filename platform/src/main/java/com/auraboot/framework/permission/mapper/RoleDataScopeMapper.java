package com.auraboot.framework.permission.mapper;

import com.auraboot.framework.permission.entity.RoleDataScope;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for ab_role_data_scope table.
 * Tenant isolation is managed manually (tenant_id in queries), so we ignore the tenant interceptor.
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface RoleDataScopeMapper extends BaseMapper<RoleDataScope> {

    /**
     * Find data scope entries matching any of the given role IDs for a specific resource and action.
     */
    @Select("<script>" +
            "SELECT * FROM ab_role_data_scope " +
            "WHERE role_id IN " +
            "<foreach item='id' collection='roleIds' open='(' separator=',' close=')'>" +
            "#{id}" +
            "</foreach>" +
            " AND resource_code = #{resourceCode}" +
            " AND action_code = #{actionCode}" +
            "</script>")
    List<RoleDataScope> findByRoleIdsAndResource(
            @Param("roleIds") List<Long> roleIds,
            @Param("resourceCode") String resourceCode,
            @Param("actionCode") String actionCode);

    /**
     * Find all data scope entries for a specific role in a tenant.
     */
    @Select("SELECT * FROM ab_role_data_scope WHERE tenant_id = #{tenantId} AND role_id = #{roleId}")
    List<RoleDataScope> findByTenantAndRole(
            @Param("tenantId") Long tenantId,
            @Param("roleId") Long roleId);
}
