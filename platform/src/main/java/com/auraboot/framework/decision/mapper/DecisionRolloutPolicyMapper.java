package com.auraboot.framework.decision.mapper;

import com.auraboot.framework.decision.entity.DecisionRolloutPolicyEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

@Mapper
public interface DecisionRolloutPolicyMapper extends BaseMapper<DecisionRolloutPolicyEntity> {

    @Select("""
            SELECT * FROM ab_drt_rollout_policy
            WHERE tenant_id = #{tenantId}
              AND decision_code = #{decisionCode}
            ORDER BY created_at DESC
            """)
    List<DecisionRolloutPolicyEntity> findByDecision(
            @Param("tenantId") Long tenantId,
            @Param("decisionCode") String decisionCode);

    @Select("""
            SELECT * FROM ab_drt_rollout_policy
            WHERE tenant_id = #{tenantId}
              AND decision_code = #{decisionCode}
              AND status = 'ACTIVE'
            ORDER BY started_at DESC NULLS LAST, created_at DESC
            LIMIT 1
            """)
    DecisionRolloutPolicyEntity findActive(
            @Param("tenantId") Long tenantId,
            @Param("decisionCode") String decisionCode);

    @Select("""
            SELECT * FROM ab_drt_rollout_policy
            WHERE tenant_id = #{tenantId}
              AND decision_code = #{decisionCode}
              AND status IN ('ACTIVE', 'PROMOTED', 'ROLLED_BACK')
            ORDER BY
              CASE WHEN status = 'ACTIVE' THEN 0 ELSE 1 END,
              ended_at DESC NULLS LAST,
              started_at DESC NULLS LAST,
              updated_at DESC NULLS LAST,
              created_at DESC
            LIMIT 1
            """)
    DecisionRolloutPolicyEntity findServing(
            @Param("tenantId") Long tenantId,
            @Param("decisionCode") String decisionCode);

    @Select("""
            SELECT MAX(updated_at) FROM ab_drt_rollout_policy
            WHERE tenant_id = #{tenantId}
              AND decision_code = #{decisionCode}
              AND status IN ('ACTIVE', 'PROMOTED', 'ROLLED_BACK')
            """)
    Instant findServingUpdatedAt(
            @Param("tenantId") Long tenantId,
            @Param("decisionCode") String decisionCode);

    @Select("SELECT * FROM ab_drt_rollout_policy WHERE tenant_id = #{tenantId} AND pid = #{pid}")
    DecisionRolloutPolicyEntity findByPid(
            @Param("tenantId") Long tenantId,
            @Param("pid") String pid);
}
