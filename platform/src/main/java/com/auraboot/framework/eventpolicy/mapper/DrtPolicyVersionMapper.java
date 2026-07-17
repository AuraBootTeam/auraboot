package com.auraboot.framework.eventpolicy.mapper;

import com.auraboot.framework.eventpolicy.entity.DrtPolicyVersionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
     * Find the active PUBLISHED version for a (tenant, policy_code).
     * Re-imports and historical publishes can leave multiple published rows, so runtime
     * resolution must prefer the highest version number deterministically.
     */
    @Select("SELECT * FROM ab_drt_policy_version WHERE tenant_id = #{tenantId} AND policy_code = #{policyCode} AND status = 'PUBLISHED' ORDER BY version DESC LIMIT 1")
    DrtPolicyVersionEntity findPublished(
            @Param("tenantId") Long tenantId,
            @Param("policyCode") String policyCode);

    /**
     * Keep EventPolicy publishing aligned with the rest of rule assets: a newly
     * published version becomes the single active PUBLISHED row, while older
     * published rows remain immutable history as DEPRECATED.
     */
    @Update("""
            UPDATE ab_drt_policy_version
            SET status = 'DEPRECATED'
            WHERE tenant_id = #{tenantId}
              AND policy_code = #{policyCode}
              AND status = 'PUBLISHED'
              AND pid <> #{currentPid}
            """)
    int deprecateOtherPublished(
            @Param("tenantId") Long tenantId,
            @Param("policyCode") String policyCode,
            @Param("currentPid") String currentPid);

    /**
     * Find the latest version by version number for a (tenant, policy_code).
     */
    @Select("SELECT * FROM ab_drt_policy_version WHERE tenant_id = #{tenantId} AND policy_code = #{policyCode} ORDER BY version DESC LIMIT 1")
    DrtPolicyVersionEntity findLatest(
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
