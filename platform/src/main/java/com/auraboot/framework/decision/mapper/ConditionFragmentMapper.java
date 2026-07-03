package com.auraboot.framework.decision.mapper;

import com.auraboot.framework.decision.entity.ConditionFragmentEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

/**
 * Mapper for Decision Runtime condition fragments.
 */
@Mapper
public interface ConditionFragmentMapper extends BaseMapper<ConditionFragmentEntity> {

    @Select("""
            SELECT * FROM ab_drt_condition_fragment
            WHERE tenant_id = #{tenantId}
              AND fragment_code = #{fragmentCode}
            ORDER BY version DESC
            LIMIT 1
            """)
    ConditionFragmentEntity findLatestByTenantAndCode(
            @Param("tenantId") Long tenantId,
            @Param("fragmentCode") String fragmentCode);

    @Select("""
            SELECT MAX(version) FROM ab_drt_condition_fragment
            WHERE tenant_id = #{tenantId}
              AND fragment_code = #{fragmentCode}
            """)
    Integer findMaxVersion(
            @Param("tenantId") Long tenantId,
            @Param("fragmentCode") String fragmentCode);

    @Select("""
            SELECT * FROM ab_drt_condition_fragment
            WHERE tenant_id = #{tenantId}
              AND pid = #{pid}
            """)
    ConditionFragmentEntity findByTenantAndPid(
            @Param("tenantId") Long tenantId,
            @Param("pid") String pid);

    @Select("""
            SELECT * FROM ab_drt_condition_fragment
            WHERE tenant_id = #{tenantId}
              AND fragment_code = #{fragmentCode}
            ORDER BY version DESC
            """)
    List<ConditionFragmentEntity> findAllByTenantAndCode(
            @Param("tenantId") Long tenantId,
            @Param("fragmentCode") String fragmentCode);

    @Select("""
            SELECT * FROM ab_drt_condition_fragment
            WHERE tenant_id = #{tenantId}
              AND fragment_code = #{fragmentCode}
              AND status IN ('PUBLISHED', 'DEPRECATED')
              AND enabled = TRUE
            ORDER BY CASE WHEN status = 'PUBLISHED' THEN 0 ELSE 1 END, version DESC
            LIMIT 1
            """)
    ConditionFragmentEntity findLatestBindableByTenantAndCode(
            @Param("tenantId") Long tenantId,
            @Param("fragmentCode") String fragmentCode);

    @Update("""
            UPDATE ab_drt_condition_fragment
            SET status = 'DEPRECATED',
                updated_by = #{updatedBy},
                updated_at = #{updatedAt}
            WHERE tenant_id = #{tenantId}
              AND fragment_code = #{fragmentCode}
              AND status = 'PUBLISHED'
              AND pid <> #{currentPid}
            """)
    int deprecateOtherPublished(
            @Param("tenantId") Long tenantId,
            @Param("fragmentCode") String fragmentCode,
            @Param("currentPid") String currentPid,
            @Param("updatedBy") String updatedBy,
            @Param("updatedAt") Instant updatedAt);
}
