package com.auraboot.framework.decision.mapper;

import com.auraboot.framework.decision.entity.DrtLogEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for {@link DrtLogEntity}.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Mapper
public interface DrtLogMapper extends BaseMapper<DrtLogEntity> {

    /**
     * Find all log entries for a given trace_id (may span multiple decisions in one trace).
     */
    @Select("SELECT * FROM ab_drt_log WHERE trace_id = #{traceId} ORDER BY created_at DESC")
    List<DrtLogEntity> findByTraceId(@Param("traceId") String traceId);

    /**
     * Find recent log entries for a (tenant, decision_code), newest first.
     */
    @Select("SELECT * FROM ab_drt_log WHERE tenant_id = #{tenantId} AND decision_code = #{decisionCode} ORDER BY created_at DESC LIMIT #{limit}")
    List<DrtLogEntity> findByCode(
            @Param("tenantId") Long tenantId,
            @Param("decisionCode") String decisionCode,
            @Param("limit") int limit);
}
