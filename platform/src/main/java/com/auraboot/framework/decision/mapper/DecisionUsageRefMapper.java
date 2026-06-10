package com.auraboot.framework.decision.mapper;

import com.auraboot.framework.decision.entity.DecisionUsageRefEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for the Decision Runtime usage index.
 */
@Mapper
public interface DecisionUsageRefMapper extends BaseMapper<DecisionUsageRefEntity> {

    @Delete("DELETE FROM ab_drt_usage_ref WHERE tenant_id = #{tenantId}")
    int deleteByTenant(@Param("tenantId") Long tenantId);

    @Delete("""
            DELETE FROM ab_drt_usage_ref
            WHERE tenant_id = #{tenantId}
              AND source_type = #{sourceType}
              AND source_pid = #{sourcePid}
            """)
    int deleteBySource(
            @Param("tenantId") Long tenantId,
            @Param("sourceType") String sourceType,
            @Param("sourcePid") String sourcePid);

    @Select("""
            SELECT * FROM ab_drt_usage_ref
            WHERE tenant_id = #{tenantId}
              AND target_type = 'DECISION'
              AND target_code = #{decisionCode}
            ORDER BY source_type, source_code, source_version
            """)
    List<DecisionUsageRefEntity> findIncomingDecisionRefs(
            @Param("tenantId") Long tenantId,
            @Param("decisionCode") String decisionCode);

    @Select("""
            SELECT * FROM ab_drt_usage_ref
            WHERE tenant_id = #{tenantId}
              AND source_type = 'DECISION_VERSION'
              AND source_code = #{decisionCode}
            ORDER BY target_type, target_code, target_path
            """)
    List<DecisionUsageRefEntity> findOutgoingDecisionRefs(
            @Param("tenantId") Long tenantId,
            @Param("decisionCode") String decisionCode);

    @Select("""
            SELECT * FROM ab_drt_usage_ref
            WHERE tenant_id = #{tenantId}
              AND target_type = 'FIELD'
              AND target_path = #{fieldRef}
            ORDER BY source_type, source_code, source_version
            """)
    List<DecisionUsageRefEntity> findFieldRefs(
            @Param("tenantId") Long tenantId,
            @Param("fieldRef") String fieldRef);

    @Select("""
            SELECT * FROM ab_drt_usage_ref
            WHERE tenant_id = #{tenantId}
              AND target_type = #{targetType}
              AND target_code = #{targetCode}
            ORDER BY source_type, source_code, source_version, target_path
            """)
    List<DecisionUsageRefEntity> findTargetRefs(
            @Param("tenantId") Long tenantId,
            @Param("targetType") String targetType,
            @Param("targetCode") String targetCode);
}
