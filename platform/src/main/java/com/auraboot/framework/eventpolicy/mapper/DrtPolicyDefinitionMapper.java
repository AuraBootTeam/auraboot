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

    @Select("SELECT COUNT(*) FROM ab_drt_policy_definition WHERE tenant_id = #{tenantId}")
    long countByTenant(@Param("tenantId") Long tenantId);

    /**
     * List definitions for the governance console with optional filters.
     */
    @Select("""
            <script>
            SELECT * FROM ab_drt_policy_definition
            WHERE tenant_id = #{tenantId}
            <if test="keyword != null and keyword != ''">
              AND (
                LOWER(policy_code) LIKE LOWER(CONCAT('%', #{keyword}, '%'))
                OR LOWER(policy_name) LIKE LOWER(CONCAT('%', #{keyword}, '%'))
                OR LOWER(event_type) LIKE LOWER(CONCAT('%', #{keyword}, '%'))
                OR LOWER(target_type) LIKE LOWER(CONCAT('%', #{keyword}, '%'))
                OR LOWER(target_key) LIKE LOWER(CONCAT('%', #{keyword}, '%'))
              )
            </if>
            <if test="eventType != null and eventType != ''">
              AND event_type = #{eventType}
            </if>
            <if test="targetType != null and targetType != ''">
              AND target_type = #{targetType}
            </if>
            <if test="targetKey != null and targetKey != ''">
              AND target_key = #{targetKey}
            </if>
            ORDER BY updated_at DESC, id DESC
            </script>
            """)
    List<DrtPolicyDefinitionEntity> listDefinitions(
            @Param("tenantId") Long tenantId,
            @Param("keyword") String keyword,
            @Param("eventType") String eventType,
            @Param("targetType") String targetType,
            @Param("targetKey") String targetKey);

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
