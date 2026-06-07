package com.auraboot.framework.decision.mapper;

import com.auraboot.framework.decision.entity.DrtVersionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Mapper for {@link DrtVersionEntity}.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Mapper
public interface DrtVersionMapper extends BaseMapper<DrtVersionEntity> {

    /**
     * Find the highest version number for a (tenant, code) combination.
     * Returns null when no versions exist.
     */
    @Select("SELECT MAX(version) FROM ab_drt_version WHERE tenant_id = #{tenantId} AND decision_code = #{decisionCode}")
    Integer findMaxVersion(
            @Param("tenantId") Long tenantId,
            @Param("decisionCode") String decisionCode);

    /**
     * Find the single PUBLISHED version for a (tenant, code).
     * There should be at most one published version at a time by convention.
     */
    @Select("SELECT * FROM ab_drt_version WHERE tenant_id = #{tenantId} AND decision_code = #{decisionCode} AND status = 'PUBLISHED' LIMIT 1")
    DrtVersionEntity findPublished(
            @Param("tenantId") Long tenantId,
            @Param("decisionCode") String decisionCode);

    /**
     * Find a specific version by (tenant, code, version number).
     */
    @Select("SELECT * FROM ab_drt_version WHERE tenant_id = #{tenantId} AND decision_code = #{decisionCode} AND version = #{version}")
    DrtVersionEntity findByTenantCodeVersion(
            @Param("tenantId") Long tenantId,
            @Param("decisionCode") String decisionCode,
            @Param("version") Integer version);

    /** All versions for a (tenant, decisionCode) — candidates for VersionSelector binding resolution. */
    @Select("SELECT * FROM ab_drt_version WHERE tenant_id = #{tenantId} AND decision_code = #{decisionCode}")
    java.util.List<DrtVersionEntity> findAllByCode(
            @Param("tenantId") Long tenantId,
            @Param("decisionCode") String decisionCode);
}
