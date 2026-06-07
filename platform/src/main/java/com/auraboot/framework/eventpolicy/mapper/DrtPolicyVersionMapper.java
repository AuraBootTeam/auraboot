package com.auraboot.framework.eventpolicy.mapper;

import com.auraboot.framework.eventpolicy.entity.DrtPolicyVersionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Mapper for {@link DrtPolicyVersionEntity}.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Mapper
public interface DrtPolicyVersionMapper extends BaseMapper<DrtPolicyVersionEntity> {

    /**
     * Find the highest version number for a (tenant, policy_code) combination.
     * Returns null when no versions exist.
     */
    @Select("SELECT MAX(version) FROM ab_drt_policy_version WHERE tenant_id = #{tenantId} AND policy_code = #{policyCode}")
    Integer findMaxVersion(
            @Param("tenantId") Long tenantId,
            @Param("policyCode") String policyCode);

    /**
     * Find the single PUBLISHED version for a (tenant, policy_code).
     * There should be at most one published version at a time by convention.
     */
    @Select("SELECT * FROM ab_drt_policy_version WHERE tenant_id = #{tenantId} AND policy_code = #{policyCode} AND status = 'PUBLISHED' LIMIT 1")
    DrtPolicyVersionEntity findPublished(
            @Param("tenantId") Long tenantId,
            @Param("policyCode") String policyCode);

    /**
     * Find a specific version by (tenant, policy_code, version number).
     */
    @Select("SELECT * FROM ab_drt_policy_version WHERE tenant_id = #{tenantId} AND policy_code = #{policyCode} AND version = #{version}")
    DrtPolicyVersionEntity findByTenantCodeVersion(
            @Param("tenantId") Long tenantId,
            @Param("policyCode") String policyCode,
            @Param("version") Integer version);
}
