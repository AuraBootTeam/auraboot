package com.auraboot.framework.meta.mapper;

import com.auraboot.framework.meta.entity.ReconciliationRun;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Mapper for ab_reconciliation_run table.
 */
@Mapper
public interface ReconciliationRunMapper extends BaseMapper<ReconciliationRun> {

    @Select("SELECT * FROM ab_reconciliation_run WHERE run_code = #{runCode}")
    ReconciliationRun findByRunCode(@Param("runCode") String runCode);

    @Select("SELECT * FROM ab_reconciliation_run WHERE profile_id = #{profileId} ORDER BY created_at DESC LIMIT #{limit}")
    List<ReconciliationRun> findByProfileId(@Param("profileId") Long profileId, @Param("limit") int limit);

    @Select("SELECT * FROM ab_reconciliation_run ORDER BY created_at DESC LIMIT #{limit} OFFSET #{offset}")
    List<ReconciliationRun> listRuns(@Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM ab_reconciliation_run")
    long countRuns();
}
