package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.DataPermissionPolicy;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * Mapper for DataPermissionPolicy entity.
 *
 * @since 5.1.0
 */
@Mapper
public interface DataPermissionPolicyMapper extends BaseMapper<DataPermissionPolicy> {

    @Select("""
        SELECT * FROM ab_data_permission_policy
        WHERE tenant_id = #{tenantId} AND pid = #{pid}
        """)
    DataPermissionPolicy findByPid(@Param("tenantId") Long tenantId, @Param("pid") String pid);

    @Select("""
        SELECT * FROM ab_data_permission_policy
        WHERE tenant_id = #{tenantId} AND model_code = #{modelCode} AND enabled = TRUE
        ORDER BY priority DESC, created_at ASC
        """)
    List<DataPermissionPolicy> findByModelCode(@Param("tenantId") Long tenantId,
                                               @Param("modelCode") String modelCode);

    @Select("""
        SELECT * FROM ab_data_permission_policy
        WHERE tenant_id = #{tenantId} AND enabled = TRUE
        ORDER BY model_code, priority DESC
        """)
    List<DataPermissionPolicy> findAllEnabled(@Param("tenantId") Long tenantId);

    /**
     * Find effective policies for a member through their roles.
     */
    @Select("""
        SELECT DISTINCT p.* FROM ab_data_permission_policy p
        INNER JOIN ab_data_permission_role_binding rb ON rb.policy_pid = p.pid AND rb.tenant_id = p.tenant_id
        INNER JOIN ab_role r ON r.pid = rb.role_pid AND r.tenant_id = p.tenant_id
        INNER JOIN ab_user_role ur ON ur.role_id = r.id AND ur.tenant_id = p.tenant_id
        WHERE p.tenant_id = #{tenantId}
          AND p.model_code = #{modelCode}
          AND p.enabled = TRUE
          AND ur.member_id = #{memberId}
        ORDER BY p.priority DESC, p.created_at ASC
        """)
    List<DataPermissionPolicy> findEffectivePolicies(@Param("tenantId") Long tenantId,
                                                     @Param("modelCode") String modelCode,
                                                     @Param("memberId") Long memberId);

    @Update("""
        UPDATE ab_data_permission_policy
        SET enabled = #{enabled}, updated_at = now()
        WHERE tenant_id = #{tenantId} AND pid = #{pid}
        """)
    int updateEnabled(@Param("tenantId") Long tenantId, @Param("pid") String pid,
                      @Param("enabled") boolean enabled);

    @Delete("DELETE FROM ab_data_permission_policy WHERE tenant_id = #{tenantId} AND pid = #{pid}")
    int deleteByPid(@Param("tenantId") Long tenantId, @Param("pid") String pid);
}
