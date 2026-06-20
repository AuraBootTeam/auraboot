package com.auraboot.framework.agent.trace.mapper;

import com.auraboot.framework.agent.trace.dto.GenAiUsageSummary;
import com.auraboot.framework.agent.trace.entity.GenAiUsageRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for the durable LLM usage/cost ledger (A-G6, P1). BaseMapper.insert
 * auto-maps the {@code @TableName} entity columns.
 */
@Mapper
public interface GenAiUsageMapper extends BaseMapper<GenAiUsageRecord> {

    /**
     * Per-model usage/cost rollup for a tenant (A-G6 cost view). Column aliases are
     * snake_case for mapUnderscoreToCamelCase to fill {@link GenAiUsageSummary}.
     * Tenant scoping is also enforced by the platform tenant interceptor.
     */
    @Select("""
            SELECT request_model AS model,
                   COALESCE(SUM(input_tokens), 0)  AS total_input_tokens,
                   COALESCE(SUM(output_tokens), 0) AS total_output_tokens,
                   COALESCE(SUM(amount), 0)        AS total_amount,
                   COUNT(*)                        AS call_count
            FROM ab_gen_ai_usage
            WHERE tenant_id = #{tenantId}
            GROUP BY request_model
            ORDER BY total_amount DESC
            """)
    List<GenAiUsageSummary> summaryByModel(@Param("tenantId") Long tenantId);
}
