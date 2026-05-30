package com.auraboot.framework.chatbi.v2.mapper;

import com.auraboot.framework.chatbi.v2.entity.ChatBiLlmAudit;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;

/** Mapper for {@link ChatBiLlmAudit}. */
@Mapper
public interface ChatBiLlmAuditMapper extends BaseMapper<ChatBiLlmAudit> {

    /**
     * Sum total tokens billed against {@code tenantId} since {@code since}.
     * Powers the W4 cost dashboard + the tenant-level budget guard.
     */
    @Select("SELECT COALESCE(SUM(total_tokens), 0) FROM chatbi_llm_audit "
          + "WHERE tenant_id = #{tenantId} AND ts >= #{since}")
    long sumTokensSince(@Param("tenantId") Long tenantId,
                        @Param("since") Instant since);

    @Select("SELECT COALESCE(SUM(cost_cents), 0) FROM chatbi_llm_audit "
          + "WHERE tenant_id = #{tenantId} AND ts >= #{since}")
    java.math.BigDecimal sumCostCentsSince(@Param("tenantId") Long tenantId,
                                           @Param("since") Instant since);
}
