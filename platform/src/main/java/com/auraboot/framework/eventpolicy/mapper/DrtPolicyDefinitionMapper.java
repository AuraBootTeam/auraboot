package com.auraboot.framework.eventpolicy.mapper;

import com.auraboot.framework.eventpolicy.entity.DrtPolicyDefinitionEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for {@link DrtPolicyDefinitionEntity}.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Mapper
public interface DrtPolicyDefinitionMapper extends BaseMapper<DrtPolicyDefinitionEntity> {

    /**
     * Find a definition by (tenant_id, policy_code).
     */
    @Select("SELECT * FROM ab_drt_policy_definition WHERE tenant_id = #{tenantId} AND policy_code = #{policyCode}")
    DrtPolicyDefinitionEntity findByTenantAndCode(
            @Param("tenantId") Long tenantId,
            @Param("policyCode") String policyCode);

    /**
     * Find all enabled definitions matching a given (tenant, event_type, target_type, target_key).
     * target_type and target_key may be null for wildcard match; this query requires exact match.
     */
    @Select("SELECT * FROM ab_drt_policy_definition " +
            "WHERE tenant_id = #{tenantId} AND event_type = #{eventType} " +
            "  AND target_type = #{targetType} AND target_key = #{targetKey} " +
            "  AND enabled = TRUE")
    List<DrtPolicyDefinitionEntity> findByEventAndTarget(
            @Param("tenantId") Long tenantId,
            @Param("eventType") String eventType,
            @Param("targetType") String targetType,
            @Param("targetKey") String targetKey);
}
