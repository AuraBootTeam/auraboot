package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.DataPermissionRoleBinding;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Mapper for DataPermissionRoleBinding entity.
 *
 * @since 5.1.0
 */
@Mapper
public interface DataPermissionRoleBindingMapper extends BaseMapper<DataPermissionRoleBinding> {

    @Insert("""
        INSERT INTO ab_data_permission_role_binding (tenant_id, policy_pid, role_pid)
        VALUES (#{tenantId}, #{policyPid}, #{rolePid})
        ON CONFLICT (tenant_id, policy_pid, role_pid) DO NOTHING
        """)
    int insertBinding(@Param("tenantId") Long tenantId,
                      @Param("policyPid") String policyPid,
                      @Param("rolePid") String rolePid);

    @Delete("""
        DELETE FROM ab_data_permission_role_binding
        WHERE tenant_id = #{tenantId} AND policy_pid = #{policyPid} AND role_pid = #{rolePid}
        """)
    int deleteBinding(@Param("tenantId") Long tenantId,
                      @Param("policyPid") String policyPid,
                      @Param("rolePid") String rolePid);

    @Delete("DELETE FROM ab_data_permission_role_binding WHERE tenant_id = #{tenantId} AND policy_pid = #{policyPid}")
    int deleteByPolicyPid(@Param("tenantId") Long tenantId, @Param("policyPid") String policyPid);

    /**
     * List all role bindings for a specific policy.
     */
    @Select("""
        SELECT * FROM ab_data_permission_role_binding
        WHERE tenant_id = #{tenantId} AND policy_pid = #{policyPid}
        ORDER BY created_at ASC
        """)
    List<DataPermissionRoleBinding> findByPolicyPid(@Param("tenantId") Long tenantId,
                                                     @Param("policyPid") String policyPid);

    /**
     * List all policies bound to a specific role.
     */
    @Select("""
        SELECT * FROM ab_data_permission_role_binding
        WHERE tenant_id = #{tenantId} AND role_pid = #{rolePid}
        ORDER BY created_at ASC
        """)
    List<DataPermissionRoleBinding> findByRolePid(@Param("tenantId") Long tenantId,
                                                   @Param("rolePid") String rolePid);
}
