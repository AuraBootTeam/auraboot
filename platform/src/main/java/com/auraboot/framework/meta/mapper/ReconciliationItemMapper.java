package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.ReconciliationItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for ab_reconciliation_item table.
 */
@Mapper
public interface ReconciliationItemMapper extends BaseMapper<ReconciliationItem> {

    @Select("SELECT * FROM ab_reconciliation_item WHERE run_id = #{runId} ORDER BY match_status, id")
    List<ReconciliationItem> findByRunId(@Param("runId") Long runId);

    @Select("SELECT * FROM ab_reconciliation_item WHERE run_id = #{runId} AND match_status = #{matchStatus} ORDER BY id LIMIT #{limit} OFFSET #{offset}")
    List<ReconciliationItem> findByRunIdAndStatus(
            @Param("runId") Long runId,
            @Param("matchStatus") String matchStatus,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM ab_reconciliation_item WHERE run_id = #{runId} AND match_status = #{matchStatus}")
    long countByRunIdAndStatus(@Param("runId") Long runId, @Param("matchStatus") String matchStatus);

    @Select("SELECT * FROM ab_reconciliation_item WHERE run_id = #{runId} AND resolution IS NULL ORDER BY match_status, id LIMIT #{limit} OFFSET #{offset}")
    List<ReconciliationItem> findUnresolved(
            @Param("runId") Long runId,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM ab_reconciliation_item WHERE run_id = #{runId} AND resolution IS NULL")
    long countUnresolved(@Param("runId") Long runId);
}
